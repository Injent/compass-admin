package ru.injent.web.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import ru.injent.service.config.RemoteConfig
import ru.injent.service.config.RemoteConfigService
import ru.injent.web.dto.ApiError

/**
 * Маршруты управления удаленной конфигурацией приложения.
 */
fun Routing.configPage(remoteConfigService: RemoteConfigService) {
    get("/api/config") {
        runCatching { remoteConfigService.get() }
            .onSuccess { call.respond(it) }
            .onFailure { call.respond(HttpStatusCode.BadGateway, ApiError(it.message ?: "Не удалось загрузить настройки")) }
    }

    put("/api/config") {
        val remoteConfig = call.receive<RemoteConfig>()
        runCatching { remoteConfigService.update(remoteConfig) }
            .onSuccess { call.respond(remoteConfig) }
            .onFailure { call.respond(HttpStatusCode.BadGateway, ApiError(it.message ?: "Не удалось сохранить настройки")) }
    }

    get("/config") {
        call.respond(FreeMarkerContent("index.html", indexModel(call)))
    }
}
