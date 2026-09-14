package ru.injent.web.middleware

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import ru.injent.service.auth.AuthService
import ru.injent.service.config.Access
import java.net.URLEncoder

const val GOOGLE_CALLBACK_PATH = "/schedule/google/callback"
const val GOOGLE_CALLBACK_API_KEY_HEADER = "X-API-Key"

/**
 * Устанавливает перехватчик проверки авторизации для защиты эндпоинтов.
 */
fun Application.installAuthGuard(authService: AuthService, googleCallbackApiKey: String) {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()

        if (path.isGoogleCallbackPath()) {
            if (call.request.headers[GOOGLE_CALLBACK_API_KEY_HEADER] != googleCallbackApiKey) {
                call.respond(HttpStatusCode.Unauthorized, "$GOOGLE_CALLBACK_API_KEY_HEADER is required")
                finish()
            }
            return@intercept
        }

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

        if ((path.startsWith("/config") || path == "/api/google-sheets/quota/events") && user.role != Access.Role.SUPERUSER) {
            call.respond(HttpStatusCode.Forbidden)
            finish()
        }
    }
}

private fun String.isPublicAuthPath(): Boolean =
    this == "/auth" || this == "/auth/login" || startsWith("/static/")

private fun String.isGoogleCallbackPath(): Boolean =
    this == GOOGLE_CALLBACK_PATH || startsWith("$GOOGLE_CALLBACK_PATH/")

private fun ApplicationCall.shouldRedirectToAuth(): Boolean =
    request.httpMethod.value == "GET" &&
        request.headers["HX-Request"] != "true" &&
        request.headers[HttpHeaders.Accept].orEmpty().contains("text/html", ignoreCase = true)

private fun String.encodeNextUrl(): String =
    URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")
