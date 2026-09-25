package ru.injent.web.route

import io.ktor.client.request.forms.*
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.*
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import ru.injent.web.dto.ScheduleUploadState
import ru.injent.web.dto.scheduleView
import kotlin.test.*

class ScheduleUploadTest {
    @Test
    fun `13 uploaded files remain readable after request ends`() = testApplication {
        var files = emptyList<PendingScheduleFile>()
        application {
            routing {
                post("/upload") {
                    files = receiveScheduleFiles(call.receiveMultipart())
                    call.respond(HttpStatusCode.Accepted)
                }
            }
        }
        try {
            val response = client.post("/upload") {
                setBody(MultiPartFormDataContent(formData {
                    repeat(13) { index ->
                        append("files", "file $index".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"schedule-$index.xlsx\"")
                        })
                    }
                }))
            }
            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(13, files.size)
            files.forEachIndexed { index, file ->
                assertEquals("schedule-$index.xlsx", file.name)
                assertEquals("file $index", file.content.readText())
            }
        } finally {
            files.forEach { it.content.delete() }
        }
    }

    @Test
    fun `approval waits for background upload and upload errors survive reconnect`() {
        assertFalse(scheduleView(emptyList(), upload = ScheduleUploadState(running = true)).canOpenScheduleApproval)
        val completed = scheduleView(emptyList(), upload = ScheduleUploadState(error = "Upload failed"))
        assertEquals("Upload failed", completed.upload.error)
        assertFalse(completed.upload.running)
        assertTrue(completed.canOpenScheduleApproval)
    }
}
