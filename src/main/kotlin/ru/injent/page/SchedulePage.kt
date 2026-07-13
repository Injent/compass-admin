package ru.injent.page

import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

fun Routing.schedulePage() {
    get {
        call.respond("Cool")
    }
}