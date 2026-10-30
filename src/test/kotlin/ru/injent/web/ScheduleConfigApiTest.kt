package ru.injent.web

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.injent.database.ServerSettingsTable
import ru.injent.service.auth.AuthService
import ru.injent.service.config.*
import ru.injent.web.middleware.installAuthGuard
import ru.injent.web.route.authPage
import ru.injent.web.route.finSupportPage
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class ScheduleConfigApiTest {
    @Test
    fun `external config requires API key even for logged in administrators and filters stale overrides`() = testApplication {
        val directory = Files.createTempDirectory("schedule-config-api-").toFile()
        val database = Database.connect("jdbc:sqlite:${directory.resolve("legacy.db")}", driver = "org.sqlite.JDBC")
        transaction(database) { SchemaUtils.create(ServerSettingsTable) }
        val store = ServerSettingsStore(database, directory.resolve("data/prefs.preferences_pb"))
        val clock = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneId.of("Europe/Moscow"))
        val service = FinSupportSettingsService(store.preferences, clock)
        try {
            val saved = service.update(FinSupportSettings(schedules = FinSupportSchedules(MaterialAidSchedule(
                overrides = mapOf("2026-09" to ScheduleOverride(), "2026-10" to ScheduleOverride(enabled = false), "2026-11" to ScheduleOverride()),
            ))))
            application {
                install(ContentNegotiation) { json() }
                val auth = AuthService(AppConfig(GigaChatConfig("", "", 0, ""), CompassApiConfig("", "service-key"), listOf(Access(Access.Role.SUPERUSER, "admin", "password"))))
                installAuthGuard(auth, "callback-key", "service-key")
                routing { authPage(auth); finSupportPage(service) }
            }
            val browser = createClient { followRedirects = false }
            val url = "/api/schedules/config"
            assertEquals(HttpStatusCode.Unauthorized, browser.get(url).status)
            assertEquals(HttpStatusCode.Unauthorized, browser.get(url) { header("X-Api-Key", "wrong") }.status)
            assertEquals(HttpStatusCode.Unauthorized, browser.get(url) { header("X-Api-Key", "callback-key") }.status)
            val login = browser.submitForm("/auth/login", parameters { append("login", "admin"); append("password", "password") })
            val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
            assertEquals(HttpStatusCode.Unauthorized, browser.get(url) { header(HttpHeaders.Cookie, cookie); accept(ContentType.Text.Html) }.status)
            val response = browser.get(url) { header("x-api-key", "service-key") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.contentType()!!.match(ContentType.Application.Json))
            val exported = Json.decodeFromString<FinSupportSettings>(response.bodyAsText())
            assertEquals(setOf("2026-10", "2026-11"), exported.schedules.materialAid.overrides.keys)
            assertEquals(saved.schedules.materialAid.default, exported.schedules.materialAid.default)
            assertEquals(saved.schemaVersion, exported.schemaVersion)
            assertEquals(saved.timeZone, exported.timeZone)
            assertEquals(saved, service.get())
            assertEquals(HttpStatusCode.Unauthorized, browser.put(url).status)
        } finally { store.close(); directory.deleteRecursively() }
    }
}
