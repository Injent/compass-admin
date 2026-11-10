package ru.injent.web.route

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import ru.injent.domain.PushNotification
import ru.injent.domain.PushRecipient
import ru.injent.service.auth.AuthService
import ru.injent.service.config.Access
import ru.injent.service.notification.PushDelivery
import ru.injent.service.notification.PushNotificationSender
import ru.injent.web.dto.ApiError

fun Routing.notificationRoutes(authService: AuthService, sender: PushNotificationSender) {
    get("/notifications") {
        if (!call.requireSuperUser(authService)) return@get
        call.respond(FreeMarkerContent("index.html", indexModel(call)))
    }

    post("/api/notifications") {
        if (!call.requireSuperUser(authService)) return@post
        if (!call.isSameOriginRequest()) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
            call.respond(HttpStatusCode.UnsupportedMediaType, ApiError("Ожидается запрос в формате JSON"))
            return@post
        }
        val notification = try {
            call.receive<PushNotificationRequest>().toNotification()
        } catch (_: BadRequestException) {
            call.respond(HttpStatusCode.BadRequest, ApiError("Некорректный запрос на отправку"))
            return@post
        } catch (cause: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiError(cause.message ?: "Некорректное уведомление"))
            return@post
        }
        when (sender.send(notification)) {
            PushDelivery.ACCEPTED -> call.respond(HttpStatusCode.NoContent)
            PushDelivery.REJECTED -> call.respond(HttpStatusCode.BadRequest, ApiError("FCM отклонил получателя или содержимое"))
            PushDelivery.PAYLOAD_TOO_LARGE -> call.respond(HttpStatusCode.BadRequest, ApiError("Сократите заголовок или содержимое уведомления"))
            PushDelivery.UNAVAILABLE -> call.respond(HttpStatusCode.ServiceUnavailable, ApiError("Firebase не настроен на сервере"))
            PushDelivery.FAILED -> call.respond(HttpStatusCode.BadGateway, ApiError("Не удалось подтвердить отправку через FCM"))
        }
    }
}

@Serializable
private data class PushNotificationRequest(
    val title: String,
    val body: String,
    val topic: String? = null,
    val fcmToken: String? = null,
) {
    fun toNotification(): PushNotification {
        val token = fcmToken?.trim()?.takeIf(String::isNotEmpty)
        require((topic != null) xor (token != null)) { "Выберите топик или укажите FCM-токен" }
        val recipient = if (topic != null) {
            requireNotNull(PushRecipient.Topic.entries.find { it.id == topic }) { "Неизвестный топик" }
        } else {
            PushRecipient.FcmToken(requireNotNull(token))
        }
        return PushNotification(recipient, title.trim(), body.trim())
    }
}

private suspend fun ApplicationCall.requireSuperUser(authService: AuthService): Boolean {
    if (authService.authenticate(this)?.role == Access.Role.SUPERUSER) return true
    respond(HttpStatusCode.Forbidden)
    return false
}

private fun ApplicationCall.isSameOriginRequest(): Boolean {
    request.headers["Sec-Fetch-Site"]?.let { return it == "same-origin" }
    val source = request.headers[HttpHeaders.Origin] ?: return true
    val target = request.origin
    return source == URLBuilder(
        protocol = URLProtocol.createOrDefault(target.scheme),
        host = target.serverHost,
        port = target.serverPort,
    ).buildString().removeSuffix("/")
}
