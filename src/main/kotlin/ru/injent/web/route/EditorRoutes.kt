package ru.injent.web.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import ru.injent.service.google.GoogleWorkspaceService
import ru.injent.web.dto.fileModel

/**
 * Маршруты онлайн-редактора файлов расписания и SSE статусов.
 */
fun Routing.editorPage(googleService: GoogleWorkspaceService) {
    get("/schedule/editor/{fileId}") {
        val fileId = call.parameters["fileId"].orEmpty()
        val file = googleService.files.value.find { it.fileId == fileId }
            ?: return@get call.respond(HttpStatusCode.NotFound, "File not found")

        call.respond(FreeMarkerContent("schedule/editor.html", fileModel(file)))
    }

    sse("/schedule/status/sse/{fileId}") {
        val fileId = call.parameters["fileId"].orEmpty()

        googleService.files
            .mapNotNull { files -> files.find { it.fileId == fileId } }
            .onStart {
                googleService.files.value.find { it.fileId == fileId }?.let { emit(it) }
            }
            .collectLatest { file ->
                send(
                    data = renderTemplate("schedule/status_snackbar.html", fileModel(file)),
                    event = "StatusUpdate"
                )
            }
    }
}
