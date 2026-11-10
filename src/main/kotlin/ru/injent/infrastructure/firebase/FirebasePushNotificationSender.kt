package ru.injent.infrastructure.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import ru.injent.domain.PushNotification
import ru.injent.domain.PushRecipient
import ru.injent.service.notification.PushDelivery
import ru.injent.service.notification.PushNotificationSender
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

class FirebasePushNotificationSender(
    private val credentialFile: File,
    private val logger: Logger,
) : PushNotificationSender, AutoCloseable {
    private val app = lazy {
        val credentials = credentialFile.inputStream().use { GoogleCredentials.fromStream(it) }
        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .build()
        FirebaseApp.initializeApp(options, "compass-notifications-${UUID.randomUUID()}")
    }

    override suspend fun send(notification: PushNotification): PushDelivery = withContext(Dispatchers.IO) {
        val message = notification.toFcmMessage(Clock.System.now())
            ?: return@withContext PushDelivery.PAYLOAD_TOO_LARGE
        val messaging = try {
            FirebaseMessaging.getInstance(app.value)
        } catch (_: IOException) {
            logger.warn("Firebase service account credentials are unavailable")
            return@withContext PushDelivery.UNAVAILABLE
        }

        try {
            messaging.send(message)
            PushDelivery.ACCEPTED
        } catch (cause: FirebaseMessagingException) {
            val code = cause.messagingErrorCode
            logger.warn("FCM send failed ({} / {})", cause.errorCode, code)
            when (code) {
                MessagingErrorCode.INVALID_ARGUMENT,
                MessagingErrorCode.UNREGISTERED,
                MessagingErrorCode.SENDER_ID_MISMATCH -> PushDelivery.REJECTED

                else -> PushDelivery.FAILED
            }
        }
    }

    override fun close() {
        if (app.isInitialized()) app.value.delete()
    }
}

internal fun PushNotification.toFcmMessage(postedAt: Instant): Message? {
    val notificationType = when (recipient) {
        PushRecipient.Topic.GENERAL, is PushRecipient.FcmToken -> "BROADCAST"
        PushRecipient.Topic.SCHEDULE -> "SCHEDULE_CHANGE"
        PushRecipient.Topic.IMPORTANT -> "SEVERE"
    }
    val data = mapOf(
        "notificationType" to notificationType,
        "title" to title,
        "body" to body,
        "postedAt" to postedAt.toString(),
    )
    val maxBytes = if (recipient is PushRecipient.Topic) 2048 else 4096
    val payloadBytes = data.entries.sumOf { (key, value) -> key.length + value.encodeToByteArray().size }
    if (payloadBytes > maxBytes) return null

    val message = Message.builder()
        .putAllData(data)
        .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
        .setApnsConfig(
            ApnsConfig.builder()
                .putHeader("apns-push-type", "background")
                .putHeader("apns-priority", "5")
                .setAps(Aps.builder().setContentAvailable(true).build())
                .build(),
        )
    when (val target = recipient) {
        is PushRecipient.Topic -> message.setTopic(target.id)
        is PushRecipient.FcmToken -> {
            // Registration tokens and Firebase installation IDs are distinct identifiers.
            @Suppress("DEPRECATION")
            message.setToken(target.value)
        }
    }
    return message.build()
}
