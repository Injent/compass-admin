package ru.injent.service.config

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.time.Month
import java.time.DayOfWeek
import java.time.MonthDay
import java.time.YearMonth
import java.time.ZoneId

@Serializable
data class FinSupportSettings(
    @EncodeDefault val schemaVersion: Int = 3,
    @EncodeDefault val timeZone: String = "Europe/Moscow",
    @EncodeDefault val schedules: FinSupportSchedules = FinSupportSchedules(),
)

@Serializable
data class FinSupportSchedules(@EncodeDefault val materialAid: MaterialAidSchedule = MaterialAidSchedule())

@Serializable
data class MaterialAidSchedule(
    @EncodeDefault val default: ScheduleDefaults = ScheduleDefaults(),
    @EncodeDefault val overrides: Map<String, ScheduleOverride> = emptyMap(),
)

@Serializable
data class ScheduleDefaults(
    @EncodeDefault val comment: String = "",
    @EncodeDefault val detailsUrl: String = "",
    @EncodeDefault val enabled: Boolean = true,
    @EncodeDefault val months: Map<String, ReceptionMonth> = Month.entries.associate { it.name to ReceptionMonth() },
    @EncodeDefault val hours: ScheduleHours = ScheduleHours(),
    @EncodeDefault val excludedDates: List<String> = emptyList(),
)

@Serializable
data class ReceptionMonth(@EncodeDefault val enabled: Boolean = true, @EncodeDefault val dayRange: DayRange = DayRange(1, 10))

@Serializable
data class DayRange(val from: Int, val to: Int)

val weekDays = DayOfWeek.entries.map { it.name }

@Serializable
data class ScheduleHours(@EncodeDefault val days: Map<String, WorkDay> = weekDays.associateWith { WorkDay() })

@Serializable
data class WorkDay(@EncodeDefault val enabled: Boolean = true, @EncodeDefault val from: String = "10:00", @EncodeDefault val to: String = "17:00")

@Serializable
data class ScheduleOverride(
    @EncodeDefault val comment: String = "",
    @EncodeDefault val enabled: Boolean = true,
    @EncodeDefault val dayRange: DayRange = DayRange(1, 10),
    @EncodeDefault val hours: ScheduleHours = ScheduleHours(),
    @EncodeDefault val excludedDates: List<String> = emptyList(),
    val createdAt: String? = null,
)

// Ranges include both boundary days. An enabled override replaces that month's base rule;
// annual exclusions always apply, and hours are local to the configured time zone.
internal fun MaterialAidSchedule.hoursOn(date: LocalDate): WorkDay? {
    val override = overrides[YearMonth.from(date).toString()]
    val baseMonth = default.months.getValue(date.month.name)
    if (!(override?.enabled ?: (default.enabled && baseMonth.enabled))) return null
    val range = override?.dayRange ?: baseMonth.dayRange
    if (date.dayOfMonth !in range.from..range.to) return null
    if (date.toString().substring(5) in default.excludedDates || date.toString() in override?.excludedDates.orEmpty()) return null
    return (override?.hours ?: default.hours).days.getValue(date.dayOfWeek.name).takeIf { it.enabled }
}

class FinSupportSettingsService(
    private val store: DataStore<Preferences>,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Moscow")),
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    suspend fun get(): FinSupportSettings {
        val preferences = store.updateData { current ->
            val raw = current[finSupportSettingsKey] ?: return@updateData current
            if (json.parseToJsonElement(raw).jsonObject["schemaVersion"]?.jsonPrimitive?.int == 3) current
            else current.toMutablePreferences().apply { this[finSupportSettingsKey] = json.encodeToString(read(current)) }
        }
        return read(preferences)
    }

    suspend fun getActive(): FinSupportSettings {
        val settings = get()
        val schedule = settings.schedules.materialAid
        val today = LocalDate.now(clock)
        val overrides = schedule.overrides.filter { (month, override) ->
            YearMonth.parse(month) >= YearMonth.from(today) &&
                (override.createdAt == null || !LocalDate.parse(override.createdAt).isBefore(today.minusMonths(3)))
        }
        return settings.copy(schedules = FinSupportSchedules(schedule.copy(overrides = overrides)))
    }

    suspend fun update(settings: FinSupportSettings): FinSupportSettings {
        val preferences = store.edit { preferences ->
            require(settings.schemaVersion == 3 && settings.timeZone == "Europe/Moscow") { "Неподдерживаемая схема или часовой пояс" }
            val input = settings.schedules.materialAid
            val schedule = input.copy(default = input.default.copy(detailsUrl = normalizeDetailsUrl(input.default.detailsUrl)))
            validateComment(schedule.default.comment)
            val existing = read(preferences).schedules.materialAid.overrides
            val today = LocalDate.now(clock)
            require(schedule.default.months.keys == Month.entries.map { it.name }.toSet()) { "Укажите все 12 месяцев" }
            schedule.default.months.forEach { (month, rule) -> validateRange(rule.dayRange, YearMonth.of(2000, Month.valueOf(month)).lengthOfMonth()) }
            validateHours(schedule.default.hours)
            require(schedule.default.excludedDates.distinct().size == schedule.default.excludedDates.size) { "Даты не должны повторяться" }
            schedule.default.excludedDates.forEach { require(Regex("\\d{2}-\\d{2}").matches(it) && runCatching { MonthDay.parse("--$it") }.isSuccess) { "Некорректная ежегодная дата" } }
            val overrides = schedule.overrides.mapValues { (month, override) ->
                require(Regex("\\d{4}-(0[1-9]|1[0-2])").matches(month)) { "Некорректный месяц" }
                validateComment(override.comment)
                val period = YearMonth.parse(month)
                validateRange(override.dayRange, period.lengthOfMonth())
                validateHours(override.hours)
                require(override.excludedDates.distinct().size == override.excludedDates.size) { "Даты не должны повторяться" }
                override.excludedDates.forEach { date ->
                    require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) && runCatching { YearMonth.from(LocalDate.parse(date)) == period }.getOrDefault(false)) { "Дата должна принадлежать выбранному месяцу" }
                }
                override.copy(createdAt = existing[month]?.createdAt ?: today.toString())
            }.filterValues { !LocalDate.parse(it.createdAt).isBefore(today.minusMonths(3)) }
            preferences[finSupportSettingsKey] = json.encodeToString(settings.copy(schedules = FinSupportSchedules(schedule.copy(overrides = overrides))))
        }
        return read(preferences)
    }

    private fun read(preferences: Preferences): FinSupportSettings {
        val raw = preferences[finSupportSettingsKey] ?: return FinSupportSettings()
        val root = json.parseToJsonElement(raw).jsonObject
        return when (root["schemaVersion"]?.jsonPrimitive?.int ?: 1) {
            3 -> json.decodeFromString(raw)
            2 -> {
                val old = json.decodeFromString<FinSupportSettings>(raw)
                val schedule = old.schedules.materialAid
                fun hours(value: ScheduleHours) = value.copy(days = value.days.mapKeys { (day, _) -> day.uppercase() })
                old.copy(schemaVersion = 3, schedules = FinSupportSchedules(schedule.copy(
                    default = schedule.default.copy(
                        months = schedule.default.months.mapKeys { (month, _) -> Month.of(month.toInt()).name },
                        hours = hours(schedule.default.hours),
                    ),
                    overrides = schedule.overrides.mapValues { (_, override) -> override.copy(hours = hours(override.hours)) },
                )))
            }
            1 -> migrate(root)
            else -> error("Неподдерживаемая версия схемы настроек")
        }
    }

    private fun migrate(root: JsonObject): FinSupportSettings {
        val schedule = root.getValue("schedules").jsonObject.getValue("materialAid").jsonObject
        val defaults = schedule["default"]?.jsonObject ?: JsonObject(emptyMap())
        val range = defaults["dayRange"]?.let { json.decodeFromJsonElement<DayRange>(it) } ?: DayRange(1, 10)
        val enabledMonths = defaults["months"]?.jsonArray?.map { it.jsonPrimitive.int } ?: (1..12).toList()
        fun hours(element: JsonElement?): ScheduleHours {
            if (element == null || element is JsonNull) return ScheduleHours()
            val old = element.jsonObject
            if (old["days"] != null) {
                val value = json.decodeFromJsonElement<ScheduleHours>(element)
                return value.copy(days = value.days.mapKeys { (day, _) -> day.uppercase() })
            }
            return ScheduleHours(weekDays.associateWith { WorkDay(from = old.getValue("from").jsonPrimitive.content, to = old.getValue("to").jsonPrimitive.content) })
        }
        val base = ScheduleDefaults(
            enabled = defaults["enabled"]?.jsonPrimitive?.boolean ?: true,
            months = (1..12).associate { month ->
                val max = YearMonth.of(2000, month).lengthOfMonth()
                Month.of(month).name to ReceptionMonth(month in enabledMonths, DayRange(range.from.coerceAtMost(max), range.to.coerceAtMost(max)))
            },
            hours = hours(defaults["hours"]),
        )
        val overrides = schedule["overrides"]?.jsonObject.orEmpty().mapValues { (month, element) ->
            val old = element.jsonObject
            ScheduleOverride(
                enabled = old["enabled"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.boolean ?: base.enabled,
                dayRange = old["dayRange"]?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement(it) } ?: range.let { val max = YearMonth.parse(month).lengthOfMonth(); DayRange(it.from.coerceAtMost(max), it.to.coerceAtMost(max)) },
                hours = old["hours"]?.takeUnless { it is JsonNull }?.let { hours(it) } ?: base.hours,
                createdAt = old["createdAt"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: YearMonth.parse(month).atDay(1).toString(),
            )
        }
        return FinSupportSettings(schedules = FinSupportSchedules(MaterialAidSchedule(base, overrides)))
    }

    private fun validateComment(comment: String) {
        require(comment.length <= 120 && comment.lineSequence().take(3).count() <= 2) {
            "Комментарий должен содержать не более 120 символов и двух строк"
        }
    }

    private fun normalizeDetailsUrl(value: String): String {
        val link = value.trim()
        if (link.isEmpty()) return ""
        require(link.none { it.isWhitespace() }) { "Ссылка не должна содержать пробелы" }
        val normalized = when {
            link.startsWith("//") -> "https:$link"
            "://" in link -> link
            else -> "https://$link"
        }
        val uri = runCatching { URI(normalized) }.getOrNull()
        require(uri != null && uri.scheme.lowercase() in setOf("http", "https") && uri.userInfo == null &&
            uri.host != null && ('.' in uri.host || uri.host == "localhost") && uri.port in -1..65535) {
            "Укажите корректную HTTP или HTTPS ссылку"
        }
        return normalized
    }

    private fun validateRange(range: DayRange, max: Int) {
        require(range.from in 1..max && range.to in range.from..max) { "Некорректный диапазон дней (1–$max)" }
    }

    private fun validateHours(hours: ScheduleHours) {
        require(hours.days.keys == weekDays.toSet()) { "Укажите график для всех дней недели" }
        val pattern = Regex("([01]\\d|2[0-3]):[0-5]\\d")
        hours.days.values.forEach { require(!it.enabled || (pattern.matches(it.from) && pattern.matches(it.to) && it.from < it.to)) { "Некорректный интервал времени" } }
    }
}
