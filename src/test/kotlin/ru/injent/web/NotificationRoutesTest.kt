package ru.injent.web

import freemarker.cache.FileTemplateLoader
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import ru.injent.domain.PushNotification
import ru.injent.domain.PushRecipient
import ru.injent.service.auth.AuthService
import ru.injent.service.config.Access
import ru.injent.service.config.AppConfig
import ru.injent.service.config.CompassApiConfig
import ru.injent.service.config.GigaChatConfig
import ru.injent.service.notification.PushDelivery
import ru.injent.service.notification.PushNotificationSender
import ru.injent.web.middleware.installAuthGuard
import ru.injent.web.route.notificationRoutes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationRoutesTest {
    @Test
    fun `only superusers can open the page and send pushes`() = testApplication {
        val sent = installNotifications()
        val payload = """{"topic":"schedule","title":" Расписание ","body":" Занятия перенесены "}"""

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/notifications").status)
        for (role in listOf(Access.Role.USER, Access.Role.PROFKOM)) {
            for (path in listOf("/notifications", "/%6Eotifications")) {
                assertEquals(HttpStatusCode.Forbidden, client.get(path) { basicAuth(role.name, "password") }.status)
            }
            for (path in listOf("/api/notifications", "/api/%6Eotifications")) {
                assertEquals(HttpStatusCode.Forbidden, client.post(path) {
                    basicAuth(role.name, "password")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }.status)
            }
        }

        val page = client.get("/notifications") { basicAuth(Access.Role.SUPERUSER.name, "password") }
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("canAccessNotifications: true"))
        assertEquals(HttpStatusCode.NoContent, send(payload))
        assertEquals(HttpStatusCode.NoContent, send("""{"fcmToken":" token:value ","title":"Привет","body":"Тест"}"""))
        assertEquals(
            listOf(
                PushNotification(PushRecipient.Topic.SCHEDULE, "Расписание", "Занятия перенесены"),
                PushNotification(PushRecipient.FcmToken("token:value"), "Привет", "Тест"),
            ),
            sent,
        )
    }

    @Test
    fun `invalid and cross-origin requests never reach the sender`() = testApplication {
        val sent = installNotifications()
        val payload = """{"topic":"important","title":"Привет","body":"Тест"}"""

        for (invalid in listOf(
            """{"title":"Привет","body":"Тест"}""",
            """{"topic":"other","title":"Привет","body":"Тест"}""",
            """{"topic":"important","fcmToken":"token","title":"Привет","body":"Тест"}""",
            """{"fcmToken":" ","title":"Привет","body":"Тест"}""",
            """{"topic":"important","title":" ","body":"Тест"}""",
            """{"topic":"important","title":"Привет"}""",
        )) {
            assertEquals(HttpStatusCode.BadRequest, send(invalid))
        }
        assertEquals(HttpStatusCode.Forbidden, send(payload, origin = "https://other.example"))
        assertEquals(HttpStatusCode.Forbidden, send(payload, fetchSite = "same-site"))
        assertTrue(sent.isEmpty())

        assertEquals(HttpStatusCode.NoContent, send(payload, origin = "http://localhost"))
    }

    private fun ApplicationTestBuilder.installNotifications(): List<PushNotification> {
        val sent = mutableListOf<PushNotification>()
        val auth = AuthService(
            AppConfig(
                GigaChatConfig("", "", 0, ""),
                CompassApiConfig("", ""),
                Access.Role.entries.map { Access(it, it.name, "password") },
            ),
        )
        application {
            install(Koin) { modules(module { single { auth } }) }
            install(FreeMarker) { templateLoader = FileTemplateLoader(File("templates")) }
            install(ContentNegotiation) { json() }
            installAuthGuard(auth, "test-key")
            routing {
                notificationRoutes(auth, PushNotificationSender { notification ->
                    sent += notification
                    PushDelivery.ACCEPTED
                })
            }
        }
        return sent
    }

    private suspend fun ApplicationTestBuilder.send(
        payload: String,
        origin: String? = null,
        fetchSite: String? = null,
    ): HttpStatusCode = client.post("/api/notifications") {
        basicAuth(Access.Role.SUPERUSER.name, "password")
        contentType(ContentType.Application.Json)
        origin?.let { header(HttpHeaders.Origin, it) }
        fetchSite?.let { header("Sec-Fetch-Site", it) }
        setBody(payload)
    }.status
}
