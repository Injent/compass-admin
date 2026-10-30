package ru.injent.service.google

import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.drive.Drive
import com.google.api.services.sheets.v4.Sheets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import ru.injent.database.ScheduleFileApprovalsTable
import ru.injent.database.ScheduleGroupsTable
import ru.injent.database.TeachersTable
import ru.injent.service.ScheduleChangeTracker
import ru.injent.service.ScheduleGroupService
import ru.injent.service.teacher.TeacherService
import ru.injent.service.validator.LegendValidator
import ru.injent.service.validator.LessonValidator
import ru.injent.service.validator.TeacherValidator
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleStartupTest {
    @Test
    fun `local startup lists files without reading or validating sheets`() = runBlocking {
        val path = Files.createTempFile("google-startup-", ".db")
        try {
            val database = Database.connect("jdbc:sqlite:$path", driver = "org.sqlite.JDBC")
            transaction(database) { SchemaUtils.create(ScheduleGroupsTable, ScheduleFileApprovalsTable, TeachersTable) }
            var driveRequests = 0
            var sheetsRequests = 0
            val driveTransport = object : MockHttpTransport() {
                override fun buildRequest(method: String, url: String): MockLowLevelHttpRequest {
                    driveRequests++
                    val body = if (driveRequests == 1) """{"files":[{"id":"folder"}]}"""
                    else """{"files":[{"id":"file","name":"Schedule","appProperties":{"status":"PROCESSING"}}]}"""
                    return MockLowLevelHttpRequest(url).setResponse(MockLowLevelHttpResponse().setContentType("application/json").setContent(body))
                }
            }
            val sheetsTransport = object : MockHttpTransport() {
                override fun buildRequest(method: String, url: String): MockLowLevelHttpRequest {
                    sheetsRequests++
                    error("Local startup must not call Sheets")
                }
            }
            val json = GsonFactory.getDefaultInstance()
            val service = GoogleWorkspaceService(
                Drive.Builder(driveTransport, json, null).setApplicationName("test").build(),
                Sheets.Builder(sheetsTransport, json, null).setApplicationName("test").build(),
                LoggerFactory.getLogger("GoogleStartupTest"), Dispatchers.Unconfined,
                LegendValidator(), LessonValidator(), TeacherValidator(TeacherService(database)),
                ScheduleGroupService(database), ScheduleChangeTracker(database),
            )
            assertTrue(service.loadFiles(validateOnStartup = false).isSuccess)
            assertTrue(service.filesLoaded.value)
            assertEquals(listOf("file"), service.files.value.map { it.fileId })
            assertEquals(2, driveRequests)
            assertEquals(0, sheetsRequests)
        } finally { Files.deleteIfExists(path) }
    }
}
