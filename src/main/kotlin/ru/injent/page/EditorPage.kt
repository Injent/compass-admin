package ru.injent.page

import io.ktor.http.*
import io.ktor.server.freemarker.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import ru.injent.service.google.NewGoogleService

fun Routing.editorPage(googleService: NewGoogleService) {
    get("/schedule/editor/{fileId}") {
        val fileId = call.parameters["fileId"].orEmpty()
        val file = googleService.files.value.find { it.fileId == fileId }
            ?: return@get call.respond(HttpStatusCode.NotFound, "File not found")

        call.respond(FreeMarkerContent("schedule/editor.ftl", fileModel(file)))
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
                    data = renderTemplate("schedule/status_snackbar.ftl", fileModel(file)),
                    event = "StatusUpdate"
                )
            }
    }
}
