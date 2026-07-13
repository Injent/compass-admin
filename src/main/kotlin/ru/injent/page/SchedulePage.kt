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
import ru.injent.service.google.CellReplacement
import ru.injent.service.google.NewGoogleService
import ru.injent.service.google.SheetValidator
import ru.injent.service.wordcorrection.WordCorrectionService
import kotlin.time.Clock

fun Routing.schedulePage(
    googleService: NewGoogleService,
    wordCorrectionService: WordCorrectionService,
    sheetValidators: Collection<SheetValidator>,
) {
    get("/schedule") {
        call.respond(
            FreeMarkerContent(
                "schedule/schedule.html",
                scheduleModel(googleService.files.value)
            )
        )
    }

    post("/schedule/upload") {
        val uploadResult = uploadFilesToFreeSlots(googleService, call.receiveMultipart())
        call.respond(
            status = if (uploadResult.error == null) HttpStatusCode.OK else HttpStatusCode.BadRequest,
            message = FreeMarkerContent(
                "schedule/schedule_list.html",
                scheduleModel(googleService.files.value, uploadResult.error)
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
                "schedule/schedule_list.html",
                scheduleModel(googleService.files.value, error)
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
                "schedule/schedule_list.html",
                scheduleModel(googleService.files.value, error)
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
                    data = renderTemplate("schedule/schedule_list.html", model),
                    event = "ScheduleListUpdate"
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
                appProperties[KEY_CAN_FIX_WITH_AI] = false.toString()
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
private const val KEY_CAN_FIX_WITH_AI = "canFixWithAi"
