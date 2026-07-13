package ru.injent.page

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import ru.injent.dto.FileStatus
import ru.injent.service.google.NewGoogleService
import kotlin.time.Clock

fun Routing.schedulePage(googleService: NewGoogleService) {
    get("/schedule") {
        call.respond(
            FreeMarkerContent(
                "schedule/schedule.ftl",
                scheduleModel(googleService.files.value)
            )
        )
    }

    post("/schedule/upload") {
        val uploadResult = uploadFilesToFreeSlots(googleService, call.receiveMultipart())
        call.respond(
            status = if (uploadResult.error == null) HttpStatusCode.OK else HttpStatusCode.BadRequest,
            message = FreeMarkerContent(
                "schedule/schedule_list.ftl",
                scheduleModel(googleService.files.value, uploadResult.error)
            )
        )
    }

    sse("/schedule/list/sse") {
        googleService.files
            .map(::scheduleModel)
            .onStart { emit(scheduleModel(googleService.files.value)) }
            .drop(1)
            .collectLatest { model ->
                send(
                    data = renderTemplate("schedule/schedule_list.ftl", model),
                    event = "ScheduleListUpdate"
                )
            }
    }
}

private suspend fun uploadFilesToFreeSlots(
    googleService: NewGoogleService,
    multipart: MultiPartData
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
                inputStream = part.streamProvider()
                appProperties[KEY_STATUS] = FileStatus.PROCESSING.name
                appProperties[KEY_UPLOAD_TIME] = Clock.System.now().toEpochMilliseconds().toString()
            }.getOrThrow()
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

private data class UploadResult(
    val error: String? = null
)

private const val KEY_STATUS = "status"
private const val KEY_UPLOAD_TIME = "uploadTime"
