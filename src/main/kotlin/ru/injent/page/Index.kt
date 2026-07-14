package ru.injent.page

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Routing.indexPage() {
    get("/") {
        call.respond(FreeMarkerContent("index.html", indexModel(call)))
    }
}

fun indexModel(call: ApplicationCall): Map<String, Any> =
    mapOf(
        "initialScheduleUrl" to call.initialScheduleUrl()
    )

private fun ApplicationCall.initialScheduleUrl(): String {
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
