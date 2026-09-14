package ru.injent.util

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

private val defaultTimeZone: TimeZone by lazy {
    runCatching { TimeZone.of(System.getenv("COMPASS_TIMEZONE") ?: "Europe/Moscow") }
        .getOrDefault(TimeZone.currentSystemDefault())
}

/**
 * Форматирует [Instant] в понятную пользователю дату и время расписания с учетом часового пояса.
 */
fun Instant.formatScheduleDate(timeZone: TimeZone = defaultTimeZone): String {
    val now = Clock.System.now().toLocalDateTime(timeZone)
    val localDateTime = toLocalDateTime(timeZone)
    val date = localDateTime.date
    val time = "${localDateTime.hour.twoDigits()}:${localDateTime.minute.twoDigits()}"

    return when {
        date == now.date -> time
        date == now.date.minus(1, DateTimeUnit.DAY) -> "вчера, $time"
        date.year == now.year -> "${date.day} ${date.month.number.monthAbbr()} $time"
        else -> "${date.day} ${date.month.number.monthAbbr()} ${date.year} г."
    }
}

/**
 * Возвращает наименование архива с расписанием за текущий день.
 */
fun scheduleArchiveFileName(): String {
    val today = Clock.System.now().toLocalDateTime(defaultTimeZone).date
    return "Расписание от ${today.day} ${today.month.number.monthAbbr()} ${today.year} г..zip"
}

/**
 * Дополняет число ведущим нулем до 2 цифр.
 */
fun Int.twoDigits(): String =
    toString().padStart(2, '0')

/**
 * Возвращает сокращенное русское название месяца по его номеру (1..12).
 */
fun Int.monthAbbr(): String =
    when (this) {
        1 -> "янв."
        2 -> "фев."
        3 -> "мар."
        4 -> "апр."
        5 -> "мая"
        6 -> "июн."
        7 -> "июл."
        8 -> "авг."
        9 -> "сент."
        10 -> "окт."
        11 -> "нояб."
        12 -> "дек."
        else -> ""
    }
