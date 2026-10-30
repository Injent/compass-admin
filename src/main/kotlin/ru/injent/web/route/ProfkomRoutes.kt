package ru.injent.web.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import ru.injent.service.config.ProfkomSettings
import ru.injent.service.config.ProfkomSettingsService
import ru.injent.web.dto.ApiError

fun Routing.profkomPage(settings: ProfkomSettingsService) {
    get("/finsupport") {
        call.respond(FreeMarkerContent("index.html", indexModel(call)))
    }
    get("/api/profkom") { call.respond(settings.get()) }
    put("/api/profkom") {
        val input = call.receive<ProfkomSettings>()
        try {
            call.respond(settings.update(input))
        } catch (cause: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiError(cause.message ?: "Некорректные настройки"))
        }
    }
}
