package ru.injent.page

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import ru.injent.service.auth.AuthService
import ru.injent.service.config.Access
import java.io.File

fun Routing.indexPage() {
    get("/") {
        call.respond(FreeMarkerContent("index.html", indexModel(call)))
    }
}

fun indexModel(call: ApplicationCall): Map<String, Any> =
    call.application.get<AuthService>().let { authService ->
        val canAccessConfig = authService.authenticate(call)?.role == Access.Role.SUPERUSER
        mapOf(
            "initialContentUrl" to call.initialContentUrl(canAccessConfig),
            "canAccessConfig" to canAccessConfig,
        )
    }

private fun ApplicationCall.initialContentUrl(canAccessConfig: Boolean): String {
    if (request.path().startsWith("/teachers")) {
        return "/teachers"
    }
    if (canAccessConfig && request.path().startsWith("/config")) {
        return "/config"
    }

    val filter = (request.queryParameters["f"] ?: request.queryParameters["filter"] ?: "all")
        .normalizeScheduleFilter()
    return if (filter == "all") "/schedule" else "/schedule?f=$filter"
}

private fun String.normalizeScheduleFilter(): String =
    when (lowercase()) {
        "valid" -> "valid"
        "invalid" -> "invalid"
        "deleted" -> "deleted"
        else -> "all"
    }

fun Routing.staticAssets() {
    get("/static/css/index.css") {
        call.respondText(
            text = File("static/css/index.css").readText(),
            contentType = ContentType.Text.CSS
        )
    }
}
