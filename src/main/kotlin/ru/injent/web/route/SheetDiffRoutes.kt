package ru.injent.web.route

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.WorkbookFactory
import ru.injent.domain.FileStatus
import ru.injent.service.google.GoogleWorkspaceService
import ru.injent.service.writeSheetDiff
import ru.injent.util.XlsxContentType
import ru.injent.util.contentDisposition
import ru.injent.util.hasSpreadsheetExtension
import java.io.ByteArrayOutputStream

fun Routing.sheetDiffPage(googleService: GoogleWorkspaceService) {
    get("/sheetdiff") {
        call.respond(FreeMarkerContent("index.html", indexModel(call)))
    }
    post("/api/sheetdiff/{fileId}") {
        val file = googleService.files.value.firstOrNull {
            it.fileId == call.parameters["fileId"] && it.status != FileStatus.EMPTY
        }
        if (file == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Активный файл не найден"))
            return@post
        }
        val uploads = receiveScheduleFiles(call.receiveMultipart())
        try {
            val upload = uploads.singleOrNull()
            if (upload == null || !upload.name.hasSpreadsheetExtension() || upload.content.length() > 50L * 1024 * 1024) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Выберите один файл XLSX или XLS размером до 50 МБ"))
                return@post
            }
            val valid = withContext(Dispatchers.IO) {
                runCatching { WorkbookFactory.create(upload.content).use { require(it.numberOfSheets > 0) } }.isSuccess
            }
            if (!valid) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Не удалось прочитать Excel: файл повреждён, пуст или защищён паролем"))
                return@post
            }
            val result = withContext(Dispatchers.IO) {
                val old = ByteArrayOutputStream()
                googleService.exportAsXlsxTo(file.fileId, old).getOrThrow()
                ByteArrayOutputStream().also { output ->
                    old.toByteArray().inputStream().use { before ->
                        upload.content.inputStream().use { after -> writeSheetDiff(before, after, output) }
                    }
                }.toByteArray()
            }
            call.response.header(HttpHeaders.ContentDisposition, contentDisposition("Сравнение ${file.name}.xlsx"))
            call.respondBytes(result, XlsxContentType)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            call.application.environment.log.error("Sheet comparison failed", error)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Не удалось сравнить файлы. Попробуйте снова."))
        } finally {
            uploads.forEach { it.content.delete() }
        }
    }
}
