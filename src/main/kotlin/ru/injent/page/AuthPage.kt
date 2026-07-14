package ru.injent.page

import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import ru.injent.service.auth.AUTH_COOKIE_NAME
import ru.injent.service.auth.AuthService
import ru.injent.service.config.Access
import java.net.URLEncoder

fun Routing.authPage(authService: AuthService) {
    get("/auth") {
        val next = call.request.queryParameters["next"]
            ?.takeIf { value -> value.startsWith("/") && !value.startsWith("//") }
            ?: "/"
        call.respond(FreeMarkerContent("auth/auth.html", mapOf("next" to next)))
    }

    post("/auth/login") {
        val user = authService.authenticate(call)
        if (user == null) {
            val (headerName, headerValue) = authService.challengeHeader()
            call.response.header(headerName, headerValue)
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }

        call.response.cookies.append(
            name = AUTH_COOKIE_NAME,
            value = user.basicToken,
            path = "/",
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
            encoding = CookieEncoding.RAW,
        )
        call.respond(HttpStatusCode.NoContent)
    }

    post("/auth/logout") {
        call.response.cookies.append(
            name = AUTH_COOKIE_NAME,
            value = "",
            path = "/",
            maxAge = 0,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
        )
        call.respondRedirect("/auth")
    }
}

fun Application.installAuthGuard(authService: AuthService) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path.isPublicAuthPath()) return@intercept

        val user = authService.authenticate(call)
        if (user == null) {
            if (call.shouldRedirectToAuth()) {
                call.respondRedirect("/auth?next=${call.request.uri.encodeNextUrl()}")
            } else {
                val (headerName, headerValue) = authService.challengeHeader()
                call.response.header(headerName, headerValue)
                call.respond(HttpStatusCode.Unauthorized)
            }
            finish()
            return@intercept
        }

        if (path.startsWith("/config") && user.role != Access.Role.SUPERUSER) {
            call.respond(HttpStatusCode.Forbidden)
            finish()
        }
    }
}

private fun String.isPublicAuthPath(): Boolean =
    this == "/auth" || this == "/auth/login" || this == "/static/css/index.css"

private fun io.ktor.server.application.ApplicationCall.shouldRedirectToAuth(): Boolean =
    request.httpMethod.value == "GET" &&
        request.headers["HX-Request"] != "true" &&
        request.headers[HttpHeaders.Accept].orEmpty().contains("text/html", ignoreCase = true)

private fun String.encodeNextUrl(): String =
    URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

