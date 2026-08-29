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
    get("/config") {
        if (!call.isHtmxRequest) {
            call.respond(FreeMarkerContent("index.html", indexModel(call)))
            return@get
        }

        val result = runCatching { remoteConfigService.get() }
        call.respond(
            FreeMarkerContent(
                "config/config.html",
                configModel(
                    remoteConfig = result.getOrNull(),
                    error = result.exceptionOrNull()?.message,
                )
            )
        )
    }

    post("/config") {
        val remoteConfig = call.receiveParameters().toRemoteConfig()
        val result = runCatching { remoteConfigService.update(remoteConfig) }

        call.respond(
            status = if (result.isSuccess) HttpStatusCode.OK else HttpStatusCode.BadGateway,
            message = FreeMarkerContent(
                "config/config.html",
                configModel(
                    remoteConfig = remoteConfig,
                    error = result.exceptionOrNull()?.message,
                    saved = result.isSuccess,
                )
            )
        )
    }
}

private fun configModel(
    remoteConfig: RemoteConfig?,
    error: String? = null,
    saved: Boolean = false,
): Map<String, Any?> =
    mapOf(
        "remoteConfig" to remoteConfig,
        "error" to error,
        "saved" to saved,
    )

private fun Parameters.toRemoteConfig(): RemoteConfig =
    RemoteConfig(
        termStartDate = get("termStartDate").orEmpty().trim(),
        lastResetTimestamp = get("lastResetTimestamp").orEmpty().trim(),
        versionCode = get("versionCode").orEmpty().trim().toIntOrNull() ?: 0,
        downloadUrl = get("downloadUrl").orEmpty().trim(),
        vkLinkSupport = nullableSupportLink("vkLinkSupport"),
        maxLinkSupport = nullableSupportLink("maxLinkSupport"),
        telegramLinkSupport = nullableSupportLink("telegramLinkSupport"),
        teacherSearchWarningDateRanges = getAll("teacherSearchWarningDateRanges")
            .orEmpty()
            .mapNotNull(String::toWarningDateRange),
    )

private fun Parameters.nullableSupportLink(name: String): String? =
    if (get("${name}Enabled") == "true") get(name).orEmpty().trim().ifBlank { null } else null

private fun String.toWarningDateRange(): List<String>? {
    val parts = trim().split("..")
    if (parts.size != 2) return null

    val start = parts[0].trim()
    val end = parts[1].trim()
    return if (start.isNotBlank() && end.isNotBlank()) listOf(start, end) else null
}

private val ApplicationCall.isHtmxRequest: Boolean
    get() = request.headers["HX-Request"] == "true"
