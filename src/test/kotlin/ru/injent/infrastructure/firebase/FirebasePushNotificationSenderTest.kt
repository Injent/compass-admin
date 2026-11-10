package ru.injent.infrastructure.firebase

import com.google.api.client.json.gson.GsonFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import ru.injent.domain.PushNotification
import ru.injent.domain.PushRecipient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class FirebasePushNotificationSenderTest {
    private val postedAt = Instant.parse("2026-11-10T19:00:00Z")

    @Test
    fun `FCM carries notification fields in data for each recipient`() {
        val recipients = mapOf(
            PushRecipient.Topic.GENERAL to "BROADCAST",
            PushRecipient.Topic.SCHEDULE to "SCHEDULE_CHANGE",
            PushRecipient.Topic.IMPORTANT to "SEVERE",
            PushRecipient.FcmToken("test:token") to "BROADCAST",
        )
        val title = "Расписание"
        val body = "Перенос\nна \"понедельник\""

        for ((recipient, type) in recipients) {
            val message = assertNotNull(PushNotification(recipient, title, body).toFcmMessage(postedAt))
            val wire = Json.parseToJsonElement(GsonFactory.getDefaultInstance().toString(message)).jsonObject

            assertFalse("notification" in wire)
            assertEquals(
                buildJsonObject {
                    put("notificationType", type)
                    put("title", title)
                    put("body", body)
                    put("postedAt", "2026-11-10T19:00:00Z")
                },
                wire["data"],
            )
        }
    }

    @Test
    fun `FCM size limit includes the UTF-8 data and its metadata`() {
        val notification = PushNotification(PushRecipient.Topic.IMPORTANT, "я", "я".repeat(1000))

        assertNull(notification.toFcmMessage(postedAt))
        assertNotNull(notification.copy(recipient = PushRecipient.FcmToken("test:token")).toFcmMessage(postedAt))
    }
}
