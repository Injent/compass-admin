package ru.injent.web.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import ru.injent.service.config.FinSupportSettings
import ru.injent.service.config.FinSupportSettingsService
import ru.injent.web.dto.ApiError
import ru.injent.web.middleware.SCHEDULE_CONFIG_PATH

fun Routing.finSupportPage(settings: FinSupportSettingsService) {
    get("/finsupport") {
        call.respond(FreeMarkerContent("index.html", indexModel(call)))
    }
    get(SCHEDULE_CONFIG_PATH) { call.respond(settings.getActive()) }
    get("/api/finsupport") { call.respond(settings.get()) }
    put("/api/finsupport") {
        val input = call.receive<FinSupportSettings>()
        try {
            call.respond(settings.update(input))
        } catch (cause: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiError(cause.message ?: "Некорректные настройки"))
        }
    }
}
