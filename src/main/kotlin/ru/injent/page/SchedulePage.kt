package ru.injent.page

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import ru.injent.dto.FileStatus
import ru.injent.service.config.AppConfig
import ru.injent.service.config.CompassApiConfig
import ru.injent.service.google.CellReplacement
import ru.injent.service.google.NewGoogleService
import ru.injent.service.google.SheetValidator
import ru.injent.service.wordcorrection.WordCorrectionService
import java.net.URLEncoder
import kotlin.time.Clock

fun Routing.schedulePage(
    googleService: NewGoogleService,
    wordCorrectionService: WordCorrectionService,
    sheetValidators: Collection<SheetValidator>,
    appConfig: AppConfig,
    httpClient: HttpClient,
    applicationScope: CoroutineScope,
) {
    val approvalState = MutableStateFlow(ScheduleApprovalState.idle())

    get("/schedule") {
        if (!call.isHtmxRequest) {
            call.respond(FreeMarkerContent("index.html", indexModel(call)))
            return@get
        }

        call.respond(
            FreeMarkerContent(
                "schedule/schedule.html",
                scheduleModel(googleService.files.value, filter = call.scheduleFilter)
            )
        )
    }

    get("/schedule/list") {
        call.respond(
            FreeMarkerContent(
                "schedule/schedule_list_container.html",
                scheduleModel(googleService.files.value, filter = call.scheduleFilter)
            )
        )
    }

    post("/schedule/upload") {
        val uploadResult = uploadFilesToFreeSlots(
            googleService = googleService,
            multipart = call.receiveMultipart(),
            sheetValidators = sheetValidators,
            applicationScope = applicationScope,
        )
        call.respond(
            status = if (uploadResult.error == null) HttpStatusCode.OK else HttpStatusCode.BadRequest,
            message = FreeMarkerContent(
                "schedule/schedule_list_container.html",
                scheduleModel(googleService.files.value, uploadResult.error, call.scheduleFilter)
            )
        )
    }

    post("/schedule/delete") {
        val selectedIds = call.receiveParameters()
            .getAll("fileId")
            .orEmpty()
            .distinct()

        val existingIds = googleService.files.value
            .map { file -> file.fileId }
            .toSet()
        val fileIds = selectedIds.filter(existingIds::contains)

        val error = when {
            fileIds.isEmpty() -> "Выберите файлы для удаления"
            else -> googleService.freeFiles(fileIds).exceptionOrNull()?.message
        }

        call.respond(
            FreeMarkerContent(
                "schedule/schedule_list_container.html",
                scheduleModel(googleService.files.value, error, call.scheduleFilter)
            )
        )
    }

    post("/schedule/restore/{fileId}") {
        val fileId = call.parameters["fileId"].orEmpty()
        val existingFile = googleService.files.value.firstOrNull { file -> file.fileId == fileId }

        val error = when {
            existingFile == null -> "Файл не найден"
            existingFile.status != FileStatus.EMPTY -> "Файл уже восстановлен"
            else -> try {
                googleService.restore(fileId)
                null
            } catch (error: Exception) {
                error.message
            }
        }

        call.respond(
            FreeMarkerContent(
                "schedule/schedule_list_container.html",
                scheduleModel(googleService.files.value, error, call.scheduleFilter)
            )
        )
    }

    get("/schedule/download") {
        val files = googleService.files.value
            .filter { file -> file.status != FileStatus.EMPTY }

        if (files.isEmpty()) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            contentDisposition(scheduleArchiveFileName())
        )
        call.respondOutputStream(ZipContentType) {
            googleService.exportAsZipTo(
                files.associate { file ->
                    file.fileId to file.name.withInvalidPrefix(file.status).ensureXlsxExtension()
                },
                this
            ).getOrThrow()
        }
    }

    get("/schedule/download/{fileId}") {
        val fileId = call.parameters["fileId"].orEmpty()
        val file = googleService.files.value.firstOrNull { file -> file.fileId == fileId }

        if (file == null || file.status == FileStatus.EMPTY) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            contentDisposition(file.name.ensureXlsxExtension())
        )
        call.respondOutputStream(XlsxContentType) {
            googleService.exportAsXlsxTo(fileId, this).getOrThrow()
        }
    }

    sse("/schedule/list/sse") {
        googleService.files
            .map { files -> scheduleModel(files, filter = call.scheduleFilter) }
            .onStart { emit(scheduleModel(googleService.files.value, filter = call.scheduleFilter)) }
            .drop(1)
            .collectLatest { model ->
                send(
                    data = renderTemplate("schedule/schedule_list.html", model),
                    event = "ScheduleListUpdate"
                )
            }
    }

    post("/schedule/approve") {
        if (approvalState.value.status == ScheduleApprovalStatus.RUNNING) {
            call.respond(HttpStatusCode.Accepted)
            return@post
        }

        approvalState.value = ScheduleApprovalState(
            status = ScheduleApprovalStatus.RUNNING,
            progress = 0,
            message = "Файлы отправляются"
        )

        applicationScope.launch {
            sendApprovedScheduleFiles(
                googleService = googleService,
                appConfig = appConfig,
                httpClient = httpClient,
                approvalState = approvalState
            )
        }

        call.respond(HttpStatusCode.Accepted)
    }

    sse("/schedule/approve/sse") {
        approvalState
            .onStart { emit(approvalState.value) }
            .collectLatest { state ->
                send(
                    data = renderTemplate("schedule/approval_snackbar.html", state.toModel()),
                    event = "ScheduleApprovalSnackbar"
                )
            }
    }

    get("/schedule/corrections/{fileId}") {
        val fileId = call.parameters["fileId"].orEmpty()
        call.respond(
            FreeMarkerContent(
                "schedule/correction_pane.html",
                correctionPaneModel(fileId)
            )
        )
    }

    post("/schedule/corrections/{fileId}/suggest") {
        val fileId = call.parameters["fileId"].orEmpty()
        val result = googleService.suggestLessonCorrections(fileId, wordCorrectionService)

        call.respond(
            status = if (result.isSuccess) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
            message = FreeMarkerContent(
                "schedule/correction_results.html",
                correctionResultsModel(
                    fileId = fileId,
                    suggestions = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.message
                )
            )
        )
    }

    post("/schedule/corrections/{fileId}/apply") {
        val fileId = call.parameters["fileId"].orEmpty()
        val replacements = call.receiveParameters().toCellReplacements()
        val result = googleService.applyLessonCorrections(fileId, replacements)
            .fold(
                onSuccess = { googleService.test(fileId, sheetValidators) },
                onFailure = { Result.failure(it) }
            )

        call.respond(
            status = if (result.isSuccess) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
            message = FreeMarkerContent(
                "schedule/correction_apply_result.html",
                correctionApplyResultModel(
                    appliedCount = if (result.isSuccess) replacements.size else 0,
                    error = result.exceptionOrNull()?.message
                )
            )
        )
    }
}

private suspend fun sendApprovedScheduleFiles(
    googleService: NewGoogleService,
    appConfig: AppConfig,
    httpClient: HttpClient,
    approvalState: MutableStateFlow<ScheduleApprovalState>,
) {
    runCatching {
        val files = googleService.files.value
            .filter { file -> file.status != FileStatus.EMPTY }

        require(files.isNotEmpty()) { "Нет файлов для отправки" }
        require(files.all { file ->
            file.status == FileStatus.VALID && file.conflictGroups.isEmpty()
        }) { "Все файлы должны быть проверены без ошибок и не содержать конфликтующих групп" }

        approvalState.value = ScheduleApprovalState.running(10)
        httpClient.post(appConfig.compassApiConfig.approveScheduleUrl()) {
            header(HttpHeaders.Authorization, "Bearer ${appConfig.compassApiConfig.apiKey}")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        files.forEach { file ->
                            val fileName = file.name.ensureXlsxExtension()
                            append(
                                "files",
                                InputProvider {
                                    googleService.exportAsXlsx(file.fileId).asInput()
                                },
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, multipartFileDisposition("files", fileName))
                                    append(HttpHeaders.ContentType, XlsxContentType.toString())
                                }
                            )
                        }
                    }
                )
            )
        }

        approvalState.value = ScheduleApprovalState(
            status = ScheduleApprovalStatus.SUCCESS,
            progress = 100,
            message = "Расписание отправлено"
        )
    }.onFailure { error ->
        approvalState.value = ScheduleApprovalState(
            status = ScheduleApprovalStatus.ERROR,
            progress = approvalState.value.progress,
            message = error.message ?: "Отправка расписания прервана"
        )
    }
}

private fun CompassApiConfig.approveScheduleUrl(): String =
    host.trimEnd('/') + "/uploadNewSchedules"

private fun multipartFileDisposition(name: String, fileName: String): String {
    val fallback = fileName.replace(Regex("""[^\w.\- ]"""), "_")
    val encoded = URLEncoder.encode(fileName, Charsets.UTF_8).replace("+", "%20")
    return """form-data; name="$name"; filename="$fallback"; filename*=UTF-8''$encoded"""
}

private data class ScheduleApprovalState(
    val status: ScheduleApprovalStatus,
    val progress: Int,
    val message: String? = null,
) {
    fun toModel(): Map<String, Any?> =
        mapOf(
            "status" to status.name,
            "progress" to progress,
            "message" to message
        )

    companion object {
        fun idle(): ScheduleApprovalState =
            ScheduleApprovalState(ScheduleApprovalStatus.IDLE, 0)

        fun running(progress: Int): ScheduleApprovalState =
            ScheduleApprovalState(ScheduleApprovalStatus.RUNNING, progress, "Файлы отправляются")
    }
}

private enum class ScheduleApprovalStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    ERROR,
}

private fun Parameters.toCellReplacements(): List<CellReplacement> {
    val rowIndexes = getAll("rowIdx").orEmpty()
    val colIndexes = getAll("colIdx").orEmpty()
    val values = getAll("value").orEmpty()
    val count = minOf(rowIndexes.size, colIndexes.size, values.size)

    return (0 until count).mapNotNull { index ->
        val rowIdx = rowIndexes[index].toIntOrNull() ?: return@mapNotNull null
        val colIdx = colIndexes[index].toIntOrNull() ?: return@mapNotNull null
        val value = values[index].trim().takeIf(String::isNotBlank) ?: return@mapNotNull null

        CellReplacement(
            rowIdx = rowIdx,
            colIdx = colIdx,
            value = value
        )
    }
}

private suspend fun uploadFilesToFreeSlots(
    googleService: NewGoogleService,
    multipart: MultiPartData,
    sheetValidators: Collection<SheetValidator>,
    applicationScope: CoroutineScope,
): UploadResult {
    val freeFiles = googleService.files.value
        .filter { file -> file.status == FileStatus.EMPTY }
        .toMutableList()

    if (freeFiles.isEmpty()) {
        return UploadResult(error = "Нет свободных слотов для загрузки")
    }

    var uploadedCount = 0
    var rejectedCount = 0

    multipart.forEachPart { part ->
        try {
            if (part !is PartData.FileItem) return@forEachPart

            val fileName = part.originalFileName.orEmpty()
            if (!fileName.hasSpreadsheetExtension()) {
                rejectedCount++
                return@forEachPart
            }

            val target = freeFiles.removeFirstOrNull()
            if (target == null) {
                rejectedCount++
                return@forEachPart
            }

            googleService.updateFileContent(target.fileId) {
                name = fileName
                inputStream = part.provider().toInputStream()
                appProperties[KEY_STATUS] = FileStatus.PROCESSING.name
                appProperties[KEY_UPLOAD_TIME] = Clock.System.now().toEpochMilliseconds().toString()
                appProperties[KEY_CAN_FIX_WITH_AI] = false.toString()
                appProperties[KEY_CONFLICT_GROUPS] = "[]"
            }.getOrThrow()
            applicationScope.launch {
                googleService.test(target.fileId, sheetValidators)
            }
            uploadedCount++
        } finally {
            part.release()
        }
    }

    return when {
        uploadedCount == 0 && rejectedCount > 0 -> UploadResult(error = "Не удалось загрузить файлы: проверьте формат и свободные слоты")
        rejectedCount > 0 -> UploadResult(error = "Часть файлов не загружена: не хватило свободных слотов или формат не поддержан")
        else -> UploadResult()
    }
}

private fun String.hasSpreadsheetExtension(): Boolean =
    endsWith(".xlsx", ignoreCase = true) || endsWith(".xls", ignoreCase = true)

private fun String.ensureXlsxExtension(): String =
    if (hasSpreadsheetExtension()) this else "$this.xlsx"

private fun String.withInvalidPrefix(status: FileStatus): String =
    if (status == FileStatus.INVALID) "ЕстьОшибки_$this" else this

private fun scheduleArchiveFileName(): String {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    return "Расписание от ${today.day} ${today.month.number.scheduleMonthAbbr()} ${today.year} г..zip"
}

private fun Int.scheduleMonthAbbr(): String =
    when (this) {
        1 -> "янв."
        2 -> "фев."
        3 -> "мар."
        4 -> "апр."
        5 -> "мая"
        6 -> "июн."
        7 -> "июл."
        8 -> "авг."
        9 -> "сент."
        10 -> "окт."
        11 -> "нояб."
        12 -> "дек."
        else -> ""
    }

private fun contentDisposition(fileName: String): String {
    val fallback = fileName.replace(Regex("""[^\w.\- ]"""), "_")
    val encoded = URLEncoder.encode(fileName, Charsets.UTF_8).replace("+", "%20")
    return """attachment; filename="$fallback"; filename*=UTF-8''$encoded"""
}

private val ApplicationCall.scheduleFilter: String
    get() = request.queryParameters["f"] ?: request.queryParameters["filter"] ?: "all"

private val ApplicationCall.isHtmxRequest: Boolean
    get() = request.headers["HX-Request"] == "true"

private data class UploadResult(
    val error: String? = null
)

private const val KEY_STATUS = "status"
private const val KEY_UPLOAD_TIME = "uploadTime"
private const val KEY_CAN_FIX_WITH_AI = "canFixWithAi"
private const val KEY_CONFLICT_GROUPS = "conflictGroups"

private val ZipContentType = ContentType.parse("application/zip")
private val XlsxContentType = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
