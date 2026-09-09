package ru.injent.page

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.injent.service.config.RemoteConfig
import ru.injent.service.config.RemoteConfigService

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
