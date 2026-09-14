package ru.injent.web.route

import io.ktor.server.application.call
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.io.File

/**
 * Главные маршруты приложения и раздача статических файлов.
 */
fun Routing.indexPage() {
    get("/") {
        call.respond(FreeMarkerContent("index.html", indexModel(call)))
    }
}

fun Routing.staticAssets() {
    staticFiles("/static", File("static"))
}
