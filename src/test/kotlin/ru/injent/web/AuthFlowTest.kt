package ru.injent.web

import freemarker.cache.FileTemplateLoader
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import ru.injent.service.auth.AuthService
import ru.injent.service.config.*
import ru.injent.web.middleware.installAuthGuard
import ru.injent.web.route.authPage
import ru.injent.web.route.safeAuthNext
import java.io.File
import kotlin.test.*

class AuthFlowTest {
    @Test
    fun `schedule login works without javascript`() = testApplication {
        application {
            install(FreeMarker) { templateLoader = FileTemplateLoader(File("templates")) }
            val auth = AuthService(AppConfig(
                GigaChatConfig("", "", 0, ""), CompassApiConfig("", ""),
                listOf(Access(login = "тест", password = "пароль")),
            ))
            installAuthGuard(auth, "test-key")
            routing {
                authPage(auth)
                get("/schedule") { call.respondText("schedule") }
            }
        }
        val browser = createClient { followRedirects = false }
        val protected = browser.get("/schedule") { accept(ContentType.Text.Html) }
        assertEquals(HttpStatusCode.Found, protected.status)
        assertEquals("/auth?next=%2Fschedule", protected.headers[HttpHeaders.Location])
        val page = browser.get(protected.headers[HttpHeaders.Location]!!)
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("name=\"next\" value=\"/schedule\""))
        val invalid = browser.submitForm("/auth/login", parameters {
            append("login", "тест"); append("password", "wrong"); append("next", "/schedule")
        })
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        assertNull(invalid.headers[HttpHeaders.WWWAuthenticate])
        assertTrue(invalid.bodyAsText().contains("Неверный логин или пароль"))
        val login = browser.submitForm("/auth/login", parameters {
            append("login", "тест"); append("password", "пароль"); append("next", "/schedule")
        })
        assertEquals(HttpStatusCode.SeeOther, login.status)
        assertEquals("/schedule", login.headers[HttpHeaders.Location])
        val schedule = browser.get("/schedule") {
            header(HttpHeaders.Cookie, login.headers[HttpHeaders.SetCookie]!!.substringBefore(';'))
        }
        assertEquals(HttpStatusCode.OK, schedule.status)
        assertEquals("schedule", schedule.bodyAsText())
    }

    @Test
    fun `redirect stays on this site`() {
        for (value in listOf(null, "https://example.com", "//example.com", "/\\example.com", "/\r\nLocation: bad")) {
            assertEquals("/", safeAuthNext(value))
        }
        assertEquals("/schedule?filter=a&sort=b", safeAuthNext("/schedule?filter=a&sort=b"))
    }
}
