package ru.injent.service.notification

import ru.injent.domain.PushNotification

fun interface PushNotificationSender {
    suspend fun send(notification: PushNotification): PushDelivery
}

enum class PushDelivery {
    ACCEPTED,
    REJECTED,
    PAYLOAD_TOO_LARGE,
    UNAVAILABLE,
    FAILED,
}
