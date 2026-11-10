package ru.injent.domain

data class PushNotification(
    val recipient: PushRecipient,
    val title: String,
    val body: String,
) {
    init {
        require(title.isNotBlank() && body.isNotBlank()) { "Заполните заголовок и содержимое" }
    }
}

sealed interface PushRecipient {
    enum class Topic(val id: String) : PushRecipient {
        GENERAL("general"),
        SCHEDULE("schedule"),
        IMPORTANT("important"),
    }

    data class FcmToken(val value: String) : PushRecipient {
        init {
            require(value.isNotBlank() && value.length <= 4096 && value.none(Char::isWhitespace)) {
                "Укажите корректный FCM-токен"
            }
        }
    }
}
