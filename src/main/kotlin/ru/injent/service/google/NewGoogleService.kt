package ru.injent.service.google

import com.google.api.client.http.InputStreamContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.*
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.injent.dto.FileStatus
import ru.injent.dto.SheetsFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Clock
import kotlin.time.Instant

class NewGoogleService(
    private val drive: Drive,
    private val sheets: Sheets,
    private val logger: Logger,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val fileMutexes = mutableMapOf<String, Mutex>()
    private val fileMutexesGuard = Mutex()
    private val validationJobs = mutableMapOf<String, Job>()
    private val validationJobsGuard = Mutex()

    val files: StateFlow<List<SheetsFile>>
        field = MutableStateFlow(emptyList())

    suspend fun loadFiles(): Result<Unit> {
        val folder = getWorkspaceFolder().getOrElse { return Result.failure(it) }
        val loadedFiles = getAllFiles(folder.id).getOrElse { return Result.failure(it) }

        files.value = loadedFiles.map(GoogleFile::toSheetsFile)
        return Result.success(Unit)
    }

    suspend fun export(fileId: String) = withFileLock(fileId) {
        runResulting("download '$fileId'") {
            drive.files()
                .export(fileId, XLSX_MIME)
                .executeMediaAsInputStream()
                .readBytes()
        }
    }

    suspend fun exportAsXlsxTo(fileId: String, output: OutputStream) = withFileLock(fileId) {
        runResulting("download '$fileId'") {
            drive.files()
                .export(fileId, XLSX_MIME)
                .executeMediaAndDownloadTo(output)
        }
    }

    suspend fun exportAsZipTo(fileIds: Collection<String>, output: OutputStream) =
        withFileLocks(fileIds) {
            runResulting("export zip $fileIds") {
            val deferredFiles = fileIds.map { id ->
                async(ioDispatcher) {
                    id to downloadFileWithRetry(id).getOrElse { return@async null }
                }
            }

            val downloadedFiles = deferredFiles.awaitAll().filterNotNull()

            ZipOutputStream(output).use { zipOut ->
                for ((fileId, bytes) in downloadedFiles) {
                    val entry = ZipEntry(getFileName(fileId) ?: fileId)
                    zipOut.putNextEntry(entry)
                    zipOut.write(bytes)
                    zipOut.closeEntry()
                }
            }
        }
    }

    suspend fun updateFileContent(
        fileId: String,
        configure: UpdateFileContentScope.() -> Unit
    ) = withFileLock(fileId) {
        val contentScope = UpdateFileContentScope().apply(configure)

        val previousFile = files.value.firstOrNull { it.fileId == fileId }
        applyOptimisticFileUpdate(fileId, contentScope)

        val content = GoogleFile().apply {
            contentScope.name?.let { this.name = it }
            contentScope.appProperties.takeIf { it.isNotEmpty() }?.let { this.appProperties = it }
        }
        val mediaContent = contentScope.inputStream?.let {
            InputStreamContent(SPREADSHEET_MIME, it)
        }

        runResulting("update file content '$fileId'") {
            drive.files()
                .run {
                    if (mediaContent != null) {
                        update(fileId, content, mediaContent)
                    } else {
                        update(fileId, content)
                    }
                }
                .setFields("id")
                .execute()
        }.onFailure {
            rollbackOptimisticFileUpdate(fileId, previousFile)
        }
    }

    suspend fun freeFiles(fileIds: Collection<String>) = runResulting("free files = $fileIds") {
        fileIds.distinct()
            .map { fileId ->
                async {
                    updateFileContent(fileId) {
                        appProperties[KEY_STATUS] = FileStatus.EMPTY.toString()
                    }.getOrThrow()
                }
            }
            .awaitAll()
        Unit
    }

    /**
     * Проверяет валидность таблицы по правилам валидаторов
     * Если вызвать повторно когда старая операция еще не закончилась, то она прервется и начнется новая
     */
    suspend fun test(
        fileId: String,
        validators: Collection<SheetValidator>
    ): Result<Unit> = coroutineScope {
        val currentJob = coroutineContext.job
        val previousJob = validationJobsGuard.withLock {
            validationJobs.put(fileId, currentJob)
        }
        previousJob?.cancelAndJoin()

        try {
            runResulting("validating $fileId") {
                val sheet = getSheet(fileId).getOrThrow()

                val scope = SheetValidatorScope(sheet)
                validators.forEach { validator ->
                    with(validator) {
                        scope.validate()
                    }
                }
                val accumulatedErrors = scope.getAccumulatedErrors().map { cellError ->
                    InvalidCellRequest(
                        sheetId = sheet.properties.sheetId,
                        colIdx = cellError.colIdx,
                        rowIdx = cellError.rowIdx,
                        comment = cellError.comment
                    )
                }
                val fixedErrors = scope.getFixedErrors().map { cellError ->
                    ValidCellRequest(
                        sheetId = sheet.properties.sheetId,
                        colIdx = cellError.colIdx,
                        rowIdx = cellError.rowIdx
                    )
                }
                val updateSheetsDeferred = async {
                    sheets.spreadsheets()
                        .batchUpdate(
                            fileId,
                            BatchUpdateSpreadsheetRequest()
                                .setRequests(accumulatedErrors + fixedErrors)
                                .also { if (it.requests.isEmpty()) return@async }
                        )
                        .execute()
                }
                val updateStatusDeferred = async {
                    val newStatus = if (accumulatedErrors.isEmpty()) FileStatus.VALID else FileStatus.INVALID
                    if (files.value.find { it.fileId == fileId }?.status == newStatus) return@async
                    
                    updateFileContent(fileId) {
                        appProperties[KEY_STATUS] = newStatus.toString()
                    }
                }
                listOf(updateStatusDeferred, updateSheetsDeferred).awaitAll()
                Unit
            }
        } finally {
            validationJobsGuard.withLock {
                if (validationJobs[fileId] == currentJob) {
                    validationJobs.remove(fileId)
                }
            }
        }
    }

    private suspend fun getSheet(fileId: String) = runResulting("get sheet '$fileId'") {
        sheets.spreadsheets()
            .get(fileId)
            .setIncludeGridData(true)
            .execute()
            .sheets[0]
    }

    private fun getFileName(fileId: String): String? = files.value.find { fileId == it.fileId }?.name

    private suspend fun getFileMutex(fileId: String): Mutex =
        fileMutexesGuard.withLock {
            fileMutexes.getOrPut(fileId) { Mutex() }
        }

    private suspend fun <T> withFileLock(
        fileId: String,
        block: suspend CoroutineScope.() -> T,
    ): T = coroutineScope {
        getFileMutex(fileId).withLock {
            block()
        }
    }

    private suspend fun <T> withFileLocks(
        fileIds: Collection<String>,
        block: suspend CoroutineScope.() -> T,
    ): T = coroutineScope {
        val mutexes = fileIds.distinct().sorted().map { getFileMutex(it) }
        mutexes.forEach { it.lock() }
        try {
            block()
        } finally {
            mutexes.asReversed().forEach { it.unlock() }
        }
    }

    private fun applyOptimisticFileUpdate(
        fileId: String,
        contentScope: UpdateFileContentScope,
    ) {
        files.update { currentFiles ->
            currentFiles.map { file ->
                if (file.fileId != fileId) return@map file

                file.copy(
                    name = contentScope.name ?: file.name,
                    modifiedTime = Clock.System.now(),
                    uploadTime = contentScope.appProperties[KEY_UPLOAD_TIME]
                        ?.let(String::toLongOrNull)
                        ?.let(Instant::fromEpochMilliseconds)
                        ?: file.uploadTime,
                    status = contentScope.appProperties[KEY_STATUS]
                        ?.let { runCatching { FileStatus.valueOf(it) }.getOrNull() }
                        ?: file.status,
                )
            }
        }
    }

    private fun rollbackOptimisticFileUpdate(
        fileId: String,
        previousFile: SheetsFile?,
    ) {
        files.update { currentFiles ->
            if (previousFile == null) {
                currentFiles.filterNot { it.fileId == fileId }
            } else {
                currentFiles.map { file ->
                    if (file.fileId == fileId) previousFile else file
                }
            }
        }
    }

    private suspend fun downloadFileWithRetry(
        fileId: String,
        maxRetries: Int = 1
    ): Result<ByteArray> = coroutineScope {
        var attempt = 0
        var lastException: Throwable? = null

        while (attempt < maxRetries) {
            try {
                val bytes = drive.files()
                    .get(fileId)
                    .executeMediaAsInputStream()
                    .use { it.readBytes() }

                return@coroutineScope Result.success(bytes)
            } catch (e: IOException) {
                attempt++
                lastException = e
            }
        }

        Result.failure(lastException ?: Exception("Error downloading file '$fileId'"))
    }

    private suspend fun getAllFiles(folderId: String) = runResulting("get all files") {
        val query = "'$folderId' in parents and mimeType = '$SPREADSHEET_MIME' and trashed = false"
        drive.files()
            .list()
            .setQ(query)
            .setFields("files(id, name, appProperties, modifiedTime)")
            .execute()
            .files
    }

    private suspend fun getWorkspaceFolder() = runResulting("get workspace folder") {
        val query = "mimeType = '$DRIVE_FOLDER_MIME' and name = '$WORKSPACE_FOLDER_NAME' and trashed = false"
        drive.files()
            .list()
            .setQ(query)
            .setSpaces("drive")
            .execute()
            .files
            .firstOrNull()
            .let { requireNotNull(it) { "Folder '$DRIVE_FOLDER_MIME' not found" } }
    }

    private suspend fun <T> runResulting(
        operation: String,
        block: suspend CoroutineScope.() -> T,
    ): Result<T> = withContext(ioDispatcher) {
        try {
            logger.debug("starting '$operation'")
            val result = block()
            logger.info("completed '$operation'")
            Result.success(result)
        } catch (error: CancellationException) {
            logger.info("cancelled '$operation'")
            throw error
        } catch (error: Throwable) {
            logger.error("failed '$operation'", error)
            Result.failure(error)
        }
    }

    class UpdateFileContentScope {
        var name: String? = null
        var inputStream: InputStream? = null
        var appProperties: MutableMap<String, String> = mutableMapOf()
    }
}

private typealias GoogleFile = File

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val WORKSPACE_FOLDER_NAME = "Расписание"
private const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"
private const val SPREADSHEET_MIME = "application/vnd.google-apps.spreadsheet"
private const val KEY_STATUS = "status"
private const val KEY_UPLOAD_TIME = "uploadTime"

private val GoogleFile.status: FileStatus
    get() = runCatching { appProperties?.get(KEY_STATUS)?.let(FileStatus::valueOf) }.getOrNull() ?: FileStatus.EMPTY

private val GoogleFile.modifiedAtTime: Instant
    get() = modifiedTime?.value?.let(Instant::fromEpochMilliseconds) ?: Clock.System.now()

private val GoogleFile.uploadTime: Instant
    get() = appProperties?.get(KEY_UPLOAD_TIME)
        ?.let(String::toLongOrNull)
        ?.let(Instant::fromEpochMilliseconds)
        ?: Clock.System.now()

private fun GoogleFile.toSheetsFile() = SheetsFile(
    fileId = id,
    name = name,
    modifiedTime = modifiedAtTime,
    uploadTime = uploadTime,
    status = status,
)

@Suppress("FunctionName")
private fun ValidCellRequest(sheetId: Int, colIdx: Int, rowIdx: Int) = Request().setRepeatCell(
    RepeatCellRequest()
        .setCell(
            CellData()
                .setNote(null)
                .setUserEnteredFormat(
                    CellFormat().setBackgroundColor(null)
                )
        )
        .setRange(
            GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(rowIdx)
                .setEndRowIndex(rowIdx + 1)
                .setStartColumnIndex(colIdx)
                .setEndColumnIndex(colIdx + 1)
        )
        .setFields("note, userEnteredFormat.backgroundColor")
)

@Suppress("FunctionName")
private fun InvalidCellRequest(
    sheetId: Int,
    colIdx: Int,
    rowIdx: Int,
    comment: String
) = Request().setRepeatCell(
    RepeatCellRequest()
        .setCell(
            CellData()
                .setNote(comment)
                .setUserEnteredFormat(
                    CellFormat().setBackgroundColor(ErrorBackgroundColor)
                )
        )
        .setRange(
            GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(rowIdx)
                .setEndRowIndex(rowIdx + 1)
                .setStartColumnIndex(colIdx)
                .setEndColumnIndex(colIdx + 1)
        )
        .setFields("note, userEnteredFormat.backgroundColor")
)

private val ErrorBackgroundColor: Color = Color()
    .setRed(0.957f)
    .setGreen(0.78f)
    .setBlue(0.765f)
