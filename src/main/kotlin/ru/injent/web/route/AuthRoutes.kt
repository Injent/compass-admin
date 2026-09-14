package ru.injent.web.route

import io.ktor.http.ContentType
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveParameters
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import ru.injent.service.auth.AUTH_COOKIE_NAME
import ru.injent.service.auth.AuthService

/**
 * Маршруты аутентификации пользователей.
 */
fun Routing.authPage(authService: AuthService) {
    get("/auth") {
        val next = safeAuthNext(call.request.queryParameters["next"])
        call.respond(FreeMarkerContent("auth/auth.html", mapOf("next" to next)))
    }

    post("/auth/login") {
        val form = if (call.request.contentType().withoutParameters() == ContentType.Application.FormUrlEncoded) {
            call.receiveParameters()
        } else null
        val next = safeAuthNext(form?.get("next"))
        val user = if (form != null) {
            authService.authenticate(form["login"].orEmpty(), form["password"].orEmpty())
        } else authService.authenticate(call)
        if (user == null) {
            if (form != null) {
                call.respond(HttpStatusCode.Unauthorized, FreeMarkerContent("auth/auth.html", mapOf("next" to next, "error" to true)))
                return@post
            }
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
        if (form != null) {
            call.response.header("Location", next)
            call.respond(HttpStatusCode.SeeOther)
        } else {
            call.respond(HttpStatusCode.NoContent)
        }
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

internal fun safeAuthNext(value: String?): String =
    value?.takeIf { it.startsWith("/") && !it.startsWith("//") && it.none { char -> char == '\\' || char.isISOControl() } } ?: "/"
