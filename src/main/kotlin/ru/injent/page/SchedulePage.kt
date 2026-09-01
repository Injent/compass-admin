package ru.injent.page

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sse.sse
import io.ktor.util.logging.Logger
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import ru.injent.dto.FileStatus
import ru.injent.dto.SheetsFile
import ru.injent.service.config.AppConfig
import ru.injent.service.config.CompassApiConfig
import ru.injent.service.google.CellReplacement
import ru.injent.service.google.NewGoogleService
import ru.injent.service.google.SheetValidator
import ru.injent.service.wordcorrection.WordCorrectionService
import java.net.URLEncoder
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun Routing.schedulePage(
    googleService: NewGoogleService,
    wordCorrectionService: WordCorrectionService,
    sheetValidators: Collection<SheetValidator>,
    appConfig: AppConfig,
    httpClient: HttpClient,
    applicationScope: CoroutineScope,
    logger: Logger,
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
                scheduleModel(
                    files = googleService.files.value,
                    filter = call.scheduleFilter,
                    filesLoaded = googleService.filesLoaded.value,
                )
            )
        )
    }

    get("/schedule/list") {
        call.respond(
            FreeMarkerContent(
                "schedule/schedule_list_container.html",
                scheduleModel(
                    files = googleService.files.value,
                    filter = call.scheduleFilter,
                    filesLoaded = googleService.filesLoaded.value,
                )
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
                scheduleModel(
                    files = googleService.files.value,
                    error = uploadResult.error,
                    filter = call.scheduleFilter,
                    filesLoaded = googleService.filesLoaded.value,
                )
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
                scheduleModel(
                    files = googleService.files.value,
                    error = error,
                    filter = call.scheduleFilter,
                    filesLoaded = googleService.filesLoaded.value,
                )
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
                googleService.restore(fileId).exceptionOrNull()?.message
            } catch (error: Exception) {
                error.message
            }
        }

        call.respond(
            FreeMarkerContent(
                "schedule/schedule_list_container.html",
                scheduleModel(
                    files = googleService.files.value,
                    error = error,
                    filter = call.scheduleFilter,
                    filesLoaded = googleService.filesLoaded.value,
                )
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
        googleService.scheduleUpdates
            .map { files ->
                scheduleModel(
                    files = files,
                    filter = call.scheduleFilter,
                    filesLoaded = googleService.filesLoaded.value,
                )
            }
            .onStart {
                emit(
                    scheduleModel(
                        files = googleService.files.value,
                        filter = call.scheduleFilter,
                        filesLoaded = googleService.filesLoaded.value,
                    )
                )
            }
            .drop(1)
            .collectLatest { model ->
                send(
                    data = renderTemplate("schedule/schedule_list.html", model),
                    event = "ScheduleListUpdate"
                )
            }
    }

    get("/schedule/approve/groups") {
        val groups = googleService.groupsToRemove()
        call.respondText(
            text = groups.joinToString(", "),
            contentType = ContentType.Text.Plain
        )
    }

    post("/schedule/approve") {
        if (approvalState.value.status == ScheduleApprovalStatus.RUNNING ||
            approvalState.value.status == ScheduleApprovalStatus.SUCCESS
        ) {
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
                approvalState = approvalState,
                logger = logger,
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
        call.respond(HttpStatusCode.NotFound)
    }

    post("/schedule/corrections/{fileId}/suggest") {
        call.respond(HttpStatusCode.NotFound)
    }

    post("/schedule/corrections/{fileId}/apply") {
        call.respond(HttpStatusCode.NotFound)
    }
}

private suspend fun sendApprovedScheduleFiles(
    googleService: NewGoogleService,
    appConfig: AppConfig,
    httpClient: HttpClient,
    approvalState: MutableStateFlow<ScheduleApprovalState>,
    logger: Logger,
) {
    try {
        logger.info("Schedule submission started")
        require(googleService.filesLoaded.value) { "Расписание ещё загружается" }
        googleService.refreshScheduleGroups().getOrThrow()
        val files = googleService.files.value
            .filter { file -> file.status != FileStatus.EMPTY }

        val conflictGroups = files
            .flatMap(SheetsFile::conflictGroups)
            .distinct()
            .sorted()
        require(conflictGroups.isEmpty()) {
            "Конфликтующие группы: ${conflictGroups.joinToString(", ")}"
        }
        require(files.all { file -> file.status == FileStatus.VALID }) {
            "Все файлы должны быть проверены без ошибок"
        }
        val groupsToRemove = googleService.groupsToRemove()

        approvalState.value = ScheduleApprovalState.running(10)

        var scheduleResponse: CompassApiResponse? = null
        if (files.isNotEmpty()) {
            scheduleResponse = sendScheduleWithRetry(
                googleService = googleService,
                appConfig = appConfig,
                httpClient = httpClient,
                files = files,
                logger = logger,
            )
        }

        var removedGroupsResponse: CompassApiResponse? = null
        if (groupsToRemove.isNotEmpty()) {
            approvalState.value = ScheduleApprovalState.running(80)
            removedGroupsResponse = sendRemovedGroupsWithRetry(
                appConfig = appConfig,
                httpClient = httpClient,
                normalizedGroupNames = groupsToRemove,
            )
            googleService.deleteSyncedGroups(groupsToRemove)
        }

        val removedGroupsText = groupsToRemove
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(", ")
            ?: "нет"
        logger.info(
            "Schedule submission completed: filesSent=${files.size}, " +
                "removedGroups=$removedGroupsText, " +
                "uploadNewSchedules=${scheduleResponse?.toLogText() ?: "not requested (empty schedule)"}, " +
                "removeGroups=${removedGroupsResponse?.toLogText() ?: "not requested"}"
        )
        approvalState.value = ScheduleApprovalState(
            status = ScheduleApprovalStatus.SUCCESS,
            progress = 100,
            message = "Расписание отправлено"
        )
        delay(5.seconds)
        if (approvalState.value.status == ScheduleApprovalStatus.SUCCESS) {
            approvalState.value = ScheduleApprovalState.idle()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logger.error("Schedule submission failed", error)
        approvalState.value = ScheduleApprovalState(
            status = ScheduleApprovalStatus.ERROR,
            progress = approvalState.value.progress,
            message = error.message ?: "Отправка расписания прервана"
        )
    }
}

private suspend fun sendScheduleWithRetry(
    googleService: NewGoogleService,
    appConfig: AppConfig,
    httpClient: HttpClient,
    files: List<SheetsFile>,
    logger: Logger,
): CompassApiResponse {
    var lastStatus: HttpStatusCode? = null
    var lastError: Throwable? = null
    var lastResponseBody: String? = null

    repeat(SCHEDULE_SEND_MAX_ATTEMPTS) { attempt ->
        try {
            logger.info("Schedule submission attempt ${attempt + 1} of $SCHEDULE_SEND_MAX_ATTEMPTS")
            val response = httpClient.post(appConfig.compassApiConfig.approveScheduleUrl()) {
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
            val responseBody = response.bodyAsText()
            if (response.status.value in 200..299) {
                return CompassApiResponse(response.status, responseBody)
            }

            lastStatus = response.status
            lastResponseBody = responseBody
            logger.warn(
                "Schedule submission attempt ${attempt + 1} failed: " +
                    "status=${response.status.value}, response=${responseBody.toLogText()}"
            )
        } catch (error: ResponseException) {
            lastStatus = error.response.status
            lastError = error
            lastResponseBody = error.response.bodyAsText()
            logger.warn(
                "Schedule submission attempt ${attempt + 1} failed: " +
                    "status=${error.response.status.value}, response=${lastResponseBody.toLogText()}"
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastError = error
            logger.warn("Schedule submission attempt ${attempt + 1} failed: ${error.message}")
        }

        if (attempt < SCHEDULE_SEND_MAX_ATTEMPTS - 1) {
            delay(SCHEDULE_SEND_RETRY_DELAY)
        }
    }

    val reason = lastStatus
        ?.let { status -> "статус ${status.value}" }
        ?: lastError?.message
        ?: "неизвестная ошибка"
    val responseDetails = lastResponseBody
        ?.takeIf(String::isNotBlank)
        ?.let { body -> ", response=${body.toLogText()}" }
        .orEmpty()
    error("Не удалось отправить расписание после $SCHEDULE_SEND_MAX_ATTEMPTS попыток: $reason$responseDetails")
}

private suspend fun sendRemovedGroupsWithRetry(
    appConfig: AppConfig,
    httpClient: HttpClient,
    normalizedGroupNames: List<String>,
): CompassApiResponse {
    var lastStatus: HttpStatusCode? = null
    var lastError: Throwable? = null
    var lastResponseBody: String? = null

    repeat(REMOVE_GROUPS_MAX_ATTEMPTS) { attempt ->
        try {
            val response = httpClient.post(appConfig.compassApiConfig.removeGroupsUrl()) {
                header(HttpHeaders.Authorization, "Bearer ${appConfig.compassApiConfig.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(normalizedGroupNames)
            }
            val responseBody = response.bodyAsText()

            if (response.status == HttpStatusCode.OK) {
                return CompassApiResponse(response.status, responseBody)
            }
            lastStatus = response.status
            lastResponseBody = responseBody
        } catch (error: ResponseException) {
            lastStatus = error.response.status
            lastError = error
            lastResponseBody = error.response.bodyAsText()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastError = error
        }

        if (attempt < REMOVE_GROUPS_MAX_ATTEMPTS - 1) {
            delay(REMOVE_GROUPS_RETRY_DELAY)
        }
    }

    val reason = lastStatus
        ?.let { status -> "статус ${status.value}" }
        ?: lastError?.message
        ?: "неизвестная ошибка"
    val responseDetails = lastResponseBody
        ?.takeIf(String::isNotBlank)
        ?.let { body -> ", response=${body.toLogText()}" }
        .orEmpty()
    error("Не удалось удалить группы в Compass: $reason$responseDetails")
}

private data class CompassApiResponse(
    val status: HttpStatusCode,
    val body: String,
)

private fun CompassApiResponse.toLogText(): String =
    "status=${status.value}, response=${body.toLogText()}"

private fun String?.toLogText(): String =
    this
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.take(MAX_SERVER_RESPONSE_LENGTH)
        ?.ifEmpty { "<empty>" }
        ?: "<empty>"

private fun CompassApiConfig.approveScheduleUrl(): String =
    host.trimEnd('/') + "/uploadNewSchedules"

private fun CompassApiConfig.removeGroupsUrl(): String =
    host.trimEnd('/') + "/removeGroups"

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

private const val REMOVE_GROUPS_RETRY_COUNT = 3
private const val REMOVE_GROUPS_MAX_ATTEMPTS = REMOVE_GROUPS_RETRY_COUNT + 1
private val REMOVE_GROUPS_RETRY_DELAY = 10.minutes
private const val SCHEDULE_SEND_MAX_ATTEMPTS = 3
private val SCHEDULE_SEND_RETRY_DELAY = 5.seconds
private const val MAX_SERVER_RESPONSE_LENGTH = 500

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
                name = fileName.removeSpreadsheetExtension()
                inputStream = part.provider().toInputStream()
                appProperties[KEY_STATUS] = FileStatus.PROCESSING.name
                appProperties[KEY_UPLOAD_TIME] = Clock.System.now().toEpochMilliseconds().toString()
                appProperties[KEY_CAN_FIX_WITH_AI] = false.toString()
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

private fun String.removeSpreadsheetExtension(): String =
    if (endsWith(".xlsx", ignoreCase = true)) {
        dropLast(5)
    } else if (endsWith(".xls", ignoreCase = true)) {
        dropLast(4)
    } else {
        this
    }

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

private val ZipContentType = ContentType.parse("application/zip")
private val XlsxContentType = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
