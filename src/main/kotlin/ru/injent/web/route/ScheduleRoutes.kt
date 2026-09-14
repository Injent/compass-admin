package ru.injent.web.route

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sse.sse
import io.ktor.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.injent.domain.FileStatus
import ru.injent.domain.SheetsFile
import ru.injent.service.config.AppConfig
import ru.injent.service.google.GoogleWorkspaceService
import ru.injent.service.google.SheetValidator
import ru.injent.service.wordcorrection.WordCorrectionService
import ru.injent.util.XlsxContentType
import ru.injent.util.ZipContentType
import ru.injent.util.contentDisposition
import ru.injent.util.ensureXlsxExtension
import ru.injent.util.scheduleArchiveFileName
import ru.injent.util.withInvalidPrefix
import ru.injent.web.dto.DeleteScheduleRequest
import ru.injent.web.dto.ScheduleApprovalPreview
import ru.injent.web.dto.ScheduleApprovalState
import ru.injent.web.dto.ScheduleApprovalStatus
import ru.injent.web.dto.scheduleView
import kotlin.time.Duration.Companion.seconds

/**
 * Маршруты API расписания (загрузка, удаление, сброс, публикация и скачивание).
 */
fun Routing.schedulePage(
    googleService: GoogleWorkspaceService,
    wordCorrectionService: WordCorrectionService,
    sheetValidators: Collection<SheetValidator>,
    appConfig: AppConfig,
    httpClient: HttpClient,
    applicationScope: CoroutineScope,
    logger: Logger,
) {
    val approvalState = MutableStateFlow(ScheduleApprovalState.idle())

    sse("/api/google-sheets/quota/events") {
        googleService.sheetsQuotaUpdates.collect { quota ->
            send(data = Json.encodeToString(quota), event = "quota")
        }
    }

    get("/api/schedule") {
        call.respond(
            scheduleView(
                files = googleService.files.value,
                filter = call.scheduleFilter,
                filesLoaded = googleService.filesLoaded.value,
                googleWaitMessage = googleService.googleWaitMessage.value,
                validationProgress = googleService.processingProgress,
            )
        )
    }

    post("/api/schedule/upload") {
        val result = googleService.withScheduleOperation {
            uploadFilesToFreeSlots(
                googleService = googleService,
                multipart = call.receiveMultipart(),
                sheetValidators = sheetValidators,
                applicationScope = applicationScope,
            )
        }
        call.respond(
            status = if (result.error == null) HttpStatusCode.OK else HttpStatusCode.BadRequest,
            message = scheduleView(
                files = googleService.files.value,
                error = result.error,
                filter = call.scheduleFilter,
                filesLoaded = googleService.filesLoaded.value,
                googleWaitMessage = googleService.googleWaitMessage.value,
                validationProgress = googleService.processingProgress,
            )
        )
    }

    post("/api/schedule/delete") {
        val requestedIds = call.receive<DeleteScheduleRequest>().ids.distinct()
        val existingIds = googleService.files.value.map(SheetsFile::fileId).toSet()
        val fileIds = requestedIds.filter(existingIds::contains)
        val error = if (fileIds.isEmpty()) {
            "Выберите файлы для удаления"
        } else {
            googleService.freeFiles(fileIds).exceptionOrNull()?.message
        }
        call.respond(
            status = if (error == null) HttpStatusCode.OK else HttpStatusCode.BadRequest,
            message = scheduleView(
                files = googleService.files.value,
                error = error,
                filter = call.scheduleFilter,
                filesLoaded = googleService.filesLoaded.value,
                googleWaitMessage = googleService.googleWaitMessage.value,
                validationProgress = googleService.processingProgress,
            )
        )
    }

    post("/api/schedule/restore/{fileId}") {
        val fileId = call.parameters["fileId"].orEmpty()
        val existingFile = googleService.files.value.firstOrNull { it.fileId == fileId }
        val error = when {
            existingFile == null -> "Файл не найден"
            existingFile.status != FileStatus.EMPTY -> "Файл уже восстановлен"
            else -> runCatching { googleService.restore(fileId).getOrThrow() }.exceptionOrNull()?.message
        }
        call.respond(
            status = if (error == null) HttpStatusCode.OK else HttpStatusCode.BadRequest,
            message = scheduleView(
                files = googleService.files.value,
                error = error,
                filter = call.scheduleFilter,
                filesLoaded = googleService.filesLoaded.value,
                googleWaitMessage = googleService.googleWaitMessage.value,
                validationProgress = googleService.processingProgress,
            )
        )
    }

    sse("/api/schedule/events") {
        googleService.scheduleUpdates
            .onStart { emit(googleService.files.value) }
            .collectLatest { files ->
                send(
                    data = Json.encodeToString(
                        scheduleView(files, filter = call.scheduleFilter, filesLoaded = googleService.filesLoaded.value, googleWaitMessage = googleService.googleWaitMessage.value, validationProgress = googleService.processingProgress)
                    ),
                    event = "schedule"
                )
            }
    }

    get("/api/schedule/approve/preview") {
        val files = googleService.files.value.filter { it.status != FileStatus.EMPTY }
        call.respond(
            ScheduleApprovalPreview(
                groupsToRemove = googleService.groupsToRemove(),
                duplicateGroups = files.flatMap(SheetsFile::conflictGroups).distinct().sorted(),
            )
        )
    }

    post("/api/schedule/approve") {
        if (approvalState.value.status != ScheduleApprovalStatus.IDLE &&
            approvalState.value.status != ScheduleApprovalStatus.ERROR
        ) {
            call.respond(HttpStatusCode.Accepted)
            return@post
        }

        approvalState.value = ScheduleApprovalState(ScheduleApprovalStatus.RUNNING, 0, "Файлы отправляются")
        applicationScope.launch {
            googleService.withScheduleOperation {
                sendApprovedScheduleFiles(googleService, appConfig, httpClient, approvalState, logger)
            }
            if (approvalState.value.status == ScheduleApprovalStatus.SUCCESS) {
                delay(5.seconds)
                if (approvalState.value.status == ScheduleApprovalStatus.SUCCESS) {
                    approvalState.value = ScheduleApprovalState.idle()
                }
            }
        }
        call.respond(HttpStatusCode.Accepted)
    }

    sse("/api/schedule/approve/events") {
        approvalState.collect { state ->
            send(data = Json.encodeToString(state), event = "approval")
        }
    }

    get("/schedule") {
        call.respond(FreeMarkerContent("index.html", indexModel(call)))
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
}

private val ApplicationCall.scheduleFilter: String
    get() = request.queryParameters["f"] ?: request.queryParameters["filter"] ?: "all"
