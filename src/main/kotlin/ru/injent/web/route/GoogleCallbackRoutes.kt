package ru.injent.web.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.injent.service.google.GoogleWorkspaceService
import ru.injent.service.google.SheetValidator

/**
 * Маршруты вебхука от Google для перезапуска валидации файла.
 */
fun Routing.googleSheetsCallbackPage(
    googleService: GoogleWorkspaceService,
    validators: Collection<SheetValidator>,
    applicationScope: CoroutineScope,
) {
    get("/schedule/google/callback") {
        call.restartValidation(googleService, validators, applicationScope)
    }

    post("/schedule/google/callback") {
        call.restartValidation(googleService, validators, applicationScope)
    }

    get("/schedule/google/callback/{fileId}") {
        call.restartValidation(googleService, validators, applicationScope)
    }

    post("/schedule/google/callback/{fileId}") {
        call.restartValidation(googleService, validators, applicationScope)
    }
}

private suspend fun ApplicationCall.restartValidation(
    googleService: GoogleWorkspaceService,
    validators: Collection<SheetValidator>,
    applicationScope: CoroutineScope,
) {
    val fileId = parameters["fileId"] ?: request.queryParameters["fileId"]
    if (fileId.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, "fileId is required")
        return
    }

    applicationScope.launch {
        googleService.test(fileId, validators)
    }

    respond(HttpStatusCode.Accepted, "validation started")
}
