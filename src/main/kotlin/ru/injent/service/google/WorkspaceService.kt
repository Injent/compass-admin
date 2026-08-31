package ru.injent.service.google

import com.google.api.client.http.InputStreamContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.*
import io.ktor.util.logging.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.injent.dto.FileStatus
import ru.injent.dto.SheetsFile
import ru.injent.service.ScheduleGroup
import ru.injent.service.ScheduleGroupService
import ru.injent.service.normalizedGroupName
import ru.injent.service.validator.LegendValidator
import ru.injent.service.validator.LessonValidator
import ru.injent.service.validator.TeacherValidator
import ru.injent.service.wordcorrection.WordCorrectionService
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
    private val legendValidator: LegendValidator,
    private val lessonValidator: LessonValidator,
    private val teacherValidator: TeacherValidator,
    private val scheduleGroupService: ScheduleGroupService,
) {

    private val fileMutexes = mutableMapOf<String, Mutex>()
    private val fileMutexesGuard = Mutex()
    private val validationJobs = mutableMapOf<String, Job>()
    private val validationJobsGuard = Mutex()
    private val groupConflictsMutex = Mutex()
    private val scheduleGroupVersion = MutableStateFlow(0)

    val files: StateFlow<List<SheetsFile>>
        field = MutableStateFlow(emptyList())

    val filesLoaded: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val scheduleUpdates: Flow<List<SheetsFile>>
        get() = merge(files, scheduleGroupVersion.map { files.value })

    suspend fun loadFiles(): Result<Unit> {
        val folder = getWorkspaceFolder().getOrElse { return Result.failure(it) }
        val loadedFiles = getAllFiles(folder.id).getOrElse { return Result.failure(it) }

        files.value = loadedFiles.map(GoogleFile::toSheetsFile)
        scheduleGroupService.markFilesDeleted(
            files.value
                .filter { file -> file.status == FileStatus.EMPTY }
                .map { file -> file.fileId }
        )
        notifyScheduleGroupUpdate()
        syncLoadedFileGroups()
        refreshGroupConflicts()
        filesLoaded.value = true
        notifyScheduleGroupUpdate()
        return Result.success(Unit)
    }

    suspend fun exportAsXlsxTo(fileId: String, output: OutputStream) = withFileLock(fileId) {
        runResulting("download '$fileId'") {
            drive.files()
                .export(fileId, XLSX_MIME)
                .executeMediaAndDownloadTo(output)
        }
    }

    fun exportAsXlsx(fileId: String) = drive.files()
        .export(fileId, XLSX_MIME)
        .executeMediaAsInputStream()

    suspend fun exportAsZipTo(fileNamesById: Map<String, String>, output: OutputStream) =
        withFileLocks(fileNamesById.keys) {
            runResulting("export zip ${fileNamesById.keys}") {
            val deferredFiles = fileNamesById.keys.map { id ->
                async(ioDispatcher) {
                    id to downloadFileWithRetry(id).getOrElse { return@async null }
                }
            }

            val downloadedFiles = deferredFiles.awaitAll().filterNotNull()

            ZipOutputStream(output).use { zipOut ->
                for ((fileId, bytes) in downloadedFiles) {
                    val entry = ZipEntry(fileNamesById[fileId] ?: fileId)
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

        val mediaContent = contentScope.inputStream?.let {
            InputStreamContent(SPREADSHEET_MIME, it)
        }

        runResulting("update file content '$fileId'") {
            val content = GoogleFile().apply {
                contentScope.name?.let { this.name = it }
                mergeAppProperties(fileId, contentScope.appProperties)
                    .takeIf { it.isNotEmpty() }
                    ?.let { this.appProperties = it }
            }

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
                        appProperties[KEY_CONFLICT_GROUPS] = encodeConflictGroups(emptyList())
                    }.getOrThrow()
                }
            }
            .awaitAll()
        scheduleGroupService.markFilesDeleted(fileIds)
        notifyScheduleGroupUpdate()
        refreshGroupConflicts()
        Unit
    }

    fun groupsToRemove(): List<String> = scheduleGroupService.groupsToRemove()

    fun deleteSyncedGroups(normalizedGroupNames: Collection<String>) {
        scheduleGroupService.deleteSyncedGroups(normalizedGroupNames)
        notifyScheduleGroupUpdate()
    }

    suspend fun refreshScheduleGroups(): Result<Unit> = runResulting("sync schedule groups") {
        files.value
            .filter { file -> file.status != FileStatus.EMPTY }
            .forEach { file ->
                val sheet = getSheet(file.fileId).getOrThrow()
                scheduleGroupService.syncGroups(
                    fileId = file.fileId,
                    groupNames = SheetValidatorScope(sheet).scheduleGroupNames(),
                )
            }
        notifyScheduleGroupUpdate()
        refreshGroupConflicts()
        Unit
    }

    suspend fun restore(fileId: String): Result<Unit> {
        val sheetValidators = listOf(legendValidator, lessonValidator, teacherValidator)
        return test(fileId, sheetValidators)
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
                val groupNames = scope.scheduleGroupNames()
                var canFixWithAi = false
                validators.forEach { validator ->
                    val errorsBefore = scope.getAccumulatedErrors().size
                    with(validator) {
                        scope.validate()
                    }
                    if (validator === lessonValidator && scope.getAccumulatedErrors().size > errorsBefore) {
                        canFixWithAi = true
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
                    val currentFile = files.value.find { it.fileId == fileId }
                    if (currentFile?.status == newStatus && currentFile.canFixWithAi == canFixWithAi) return@async

                    updateFileContent(fileId) {
                        appProperties[KEY_STATUS] = newStatus.toString()
                        appProperties[KEY_CAN_FIX_WITH_AI] = canFixWithAi.toString()
                    }
                }
                listOf(updateStatusDeferred, updateSheetsDeferred).awaitAll()
                scheduleGroupService.syncGroups(fileId, groupNames)
                notifyScheduleGroupUpdate()
                refreshGroupConflicts()
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

    suspend fun suggestLessonCorrections(
        fileId: String,
        wordCorrectionService: WordCorrectionService,
    ): Result<List<CellCorrectionSuggestion>> = runResulting("suggest lesson corrections '$fileId'") {
        val sheet = getSheet(fileId).getOrThrow()
        val scope = SheetValidatorScope(sheet)

        with(lessonValidator) {
            scope.validate()
        }

        val cellsByPosition = scope.rows
            .flatten()
            .associateBy { it.rowIdx to it.colIdx }
        val invalidCells = scope.getAccumulatedErrors()
            .distinctBy { it.rowIdx to it.colIdx }
            .mapNotNull { error ->
                cellsByPosition[error.rowIdx to error.colIdx]
                    ?.takeIf { cell -> !cell.value.isNullOrBlank() }
            }

        val indexedCellGroups = invalidCells
            .groupBy { cell -> cell.value.orEmpty() }
            .values
            .mapIndexed { index, cells -> index + 1 to cells }
            .toMap()
        val corrections = wordCorrectionService.correctWords(
            indexedCellGroups.mapValues { (_, cells) -> cells.first().value.orEmpty() }
        )

        corrections.mapNotNull { (key, correctedValue) ->
            val cells = indexedCellGroups[key].orEmpty().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val cell = cells.first()
            CellCorrectionSuggestion(
                key = key,
                rowIdx = cell.rowIdx,
                colIdx = cell.colIdx,
                oldValue = cell.value.orEmpty(),
                newValue = correctedValue,
                replacements = cells.map { invalidCell ->
                    CellReplacement(
                        rowIdx = invalidCell.rowIdx,
                        colIdx = invalidCell.colIdx,
                        value = correctedValue
                    )
                }
            )
        }
    }

    suspend fun applyLessonCorrections(
        fileId: String,
        replacements: Collection<CellReplacement>,
    ): Result<Unit> = runResulting("apply lesson corrections '$fileId'") {
        if (replacements.isEmpty()) return@runResulting

        val sheet = getSheet(fileId).getOrThrow()
        sheets.spreadsheets()
            .batchUpdate(
                fileId,
                BatchUpdateSpreadsheetRequest()
                    .setRequests(
                        replacements.map { replacement ->
                            CellValueRequest(
                                sheetId = sheet.properties.sheetId,
                                colIdx = replacement.colIdx,
                                rowIdx = replacement.rowIdx,
                                value = replacement.value
                            )
                        }
                    )
            )
            .execute()
    }

    private suspend fun getSheet(fileId: String) = runResulting("get sheet '$fileId'") {
        sheets.spreadsheets()
            .get(fileId)
            .setIncludeGridData(true)
            .execute()
            .sheets[0]
    }

    private fun mergeAppProperties(
        fileId: String,
        appPropertiesPatch: Map<String, String?>,
    ): Map<String, String?> {
        if (appPropertiesPatch.isEmpty()) return emptyMap()

        val currentAppProperties = drive.files()
            .get(fileId)
            .setFields("appProperties")
            .execute()
            .appProperties
            .orEmpty()

        return currentAppProperties + appPropertiesPatch
    }

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
                    canFixWithAi = contentScope.appProperties[KEY_CAN_FIX_WITH_AI]
                        ?.toBooleanStrictOrNull()
                        ?: file.canFixWithAi,
                    conflictGroups = contentScope.appProperties[KEY_CONFLICT_GROUPS]
                        ?.let(::decodeConflictGroups)
                        ?: file.conflictGroups,
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
                    .export(fileId, XLSX_MIME)
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

    private suspend fun refreshGroupConflicts() = groupConflictsMutex.withLock {
        val activeFiles = files.value.filter { file -> file.status != FileStatus.EMPTY }
        if (activeFiles.isEmpty()) return@withLock

        val groupsByFile = mutableMapOf<String, List<ScheduleGroup>>()
        for (file in activeFiles) {
            val groups = readGroupNames(file.fileId) ?: continue

            groupsByFile[file.fileId] = groups.map { groupName ->
                ScheduleGroup(
                    name = groupName,
                    normalizedName = groupName.normalizedGroupName(),
                )
            }
        }

        val conflictingGroupNames = groupsByFile
            .flatMap { (fileId, groups) ->
                groups.map { group -> group.normalizedName to fileId }
            }
            .groupBy({ it.first }, { it.second })
            .filterValues { fileIds -> fileIds.distinct().size > 1 }
            .keys

        activeFiles.forEach { file ->
            val groups = groupsByFile[file.fileId] ?: return@forEach
            val conflictGroups = groups
                .filter { group -> group.normalizedName in conflictingGroupNames }
                .map(ScheduleGroup::name)
                .distinct()
                .sorted()

            if (file.conflictGroups == conflictGroups) return@forEach

            try {
                updateFileContent(file.fileId) {
                    appProperties[KEY_CONFLICT_GROUPS] = encodeConflictGroups(conflictGroups)
                }.getOrThrow()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.error("failed to update group conflicts for '${file.fileId}'", error)
            }
        }
    }

    private suspend fun syncLoadedFileGroups() {
        files.value
            .filter { file -> file.status != FileStatus.EMPTY }
            .forEach { file ->
                val groupNames = readGroupNames(file.fileId) ?: return@forEach
                try {
                    scheduleGroupService.syncGroups(file.fileId, groupNames)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.error("failed to sync groups for '${file.fileId}'", error)
                }
            }
        notifyScheduleGroupUpdate()
    }

    private fun notifyScheduleGroupUpdate() {
        scheduleGroupVersion.update { version -> version + 1 }
    }

    private suspend fun readGroupNames(fileId: String): List<String>? = try {
        getSheet(fileId).getOrNull()?.let { sheet ->
            SheetValidatorScope(sheet).scheduleGroupNames()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logger.error("failed to extract groups from '$fileId'", error)
        null
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

    class UpdateFileContentScope() {
        var name: String? = null
        var inputStream: InputStream? = null
        var appProperties: MutableMap<String, String?> = mutableMapOf()
    }
}

data class CellCorrectionSuggestion(
    val key: Int,
    val rowIdx: Int,
    val colIdx: Int,
    val oldValue: String,
    val newValue: String,
    val replacements: List<CellReplacement>,
)

data class CellReplacement(
    val rowIdx: Int,
    val colIdx: Int,
    val value: String,
)

private typealias GoogleFile = File

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val WORKSPACE_FOLDER_NAME = "Рабочее пространство"
private const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"
private const val SPREADSHEET_MIME = "application/vnd.google-apps.spreadsheet"
private const val KEY_STATUS = "status"
private const val KEY_UPLOAD_TIME = "uploadTime"
private const val KEY_CAN_FIX_WITH_AI = "canFixWithAi"
private const val KEY_CONFLICT_GROUPS = "conflictGroups"

private val GoogleFile.status: FileStatus
    get() = runCatching { appProperties?.get(KEY_STATUS)?.let(FileStatus::valueOf) }.getOrNull() ?: FileStatus.EMPTY

private val GoogleFile.canFixWithAi: Boolean
    get() = appProperties?.get(KEY_CAN_FIX_WITH_AI)?.toBooleanStrictOrNull() ?: false

private val GoogleFile.conflictGroups: List<String>
    get() = decodeConflictGroups(appProperties?.get(KEY_CONFLICT_GROUPS))

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
    canFixWithAi = canFixWithAi,
    conflictGroups = conflictGroups,
)

private fun encodeConflictGroups(groups: List<String>): String =
    Json.encodeToString(groups)

private fun decodeConflictGroups(value: String?): List<String> =
    value?.let { encoded ->
        runCatching { Json.decodeFromString<List<String>>(encoded) }
            .getOrDefault(emptyList())
    }.orEmpty()

@Suppress("FunctionName")
private fun CellValueRequest(
    sheetId: Int,
    colIdx: Int,
    rowIdx: Int,
    value: String,
) = Request().setRepeatCell(
    RepeatCellRequest()
        .setCell(
            CellData()
                .setUserEnteredValue(
                    ExtendedValue().setStringValue(value)
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
        .setFields("userEnteredValue")
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
