package ru.injent.page

import io.ktor.http.*
import io.ktor.server.freemarker.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Routing.indexPage() {
    get("/") {
        call.respond(FreeMarkerContent("index.html", emptyMap<String, Any>()))
    }
}

fun Routing.staticAssets() {
    get("/static/css/index.css") {
        call.respondText(
            text = File("static/css/index.css").readText(),
            contentType = ContentType.Text.CSS
        )
    }
}
