package ru.injent.service.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.json.Json
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@Serializable
data class FinSupportSettings(@EncodeDefault val schedules: FinSupportSchedules = FinSupportSchedules())

@Serializable
data class FinSupportSchedules(@EncodeDefault val materialAid: MaterialAidSchedule = MaterialAidSchedule())

@Serializable
data class MaterialAidSchedule(
    @EncodeDefault val default: ScheduleDefaults = ScheduleDefaults(),
    @EncodeDefault val overrides: Map<String, ScheduleOverride> = emptyMap(),
)

@Serializable
data class ScheduleDefaults(
    @EncodeDefault val enabled: Boolean = true,
    @EncodeDefault val dayRange: DayRange = DayRange(1, 10),
    @EncodeDefault val hours: ScheduleHours = ScheduleHours(days = weekDays.associateWith { WorkDay() }),
    @EncodeDefault val months: List<Int> = (1..12).toList(),
)

@Serializable
data class DayRange(val from: Int, val to: Int)

@Serializable
data class ScheduleHours(val from: String? = null, val to: String? = null, val days: Map<String, WorkDay> = emptyMap()) {
    fun normalized(): ScheduleHours = if (from != null || to != null) {
        ScheduleHours(days = weekDays.associateWith { WorkDay(from = from.orEmpty(), to = to.orEmpty()) })
    } else this
}

val weekDays = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

@Serializable
data class WorkDay(@EncodeDefault val enabled: Boolean = true, @EncodeDefault val from: String = "10:00", @EncodeDefault val to: String = "17:00")

@Serializable
data class ScheduleOverride(
    val enabled: Boolean? = null,
    val dayRange: DayRange? = null,
    val hours: ScheduleHours? = null,
    val createdAt: String? = null,
)

class FinSupportSettingsService(
    private val store: DataStore<Preferences>,
    private val clock: Clock = Clock.system(ZoneId.of("Europe/Moscow")),
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    suspend fun get(): FinSupportSettings = read(store.data.first())

    suspend fun update(settings: FinSupportSettings): FinSupportSettings {
        val preferences = store.edit { preferences ->
            val schedule = normalize(settings).schedules.materialAid
            val existing = read(preferences).schedules.materialAid.overrides
            val today = LocalDate.now(clock)
            validateRange(schedule.default.dayRange, 31)
            validateHours(schedule.default.hours)
            require(schedule.default.months.all { it in 1..12 } && schedule.default.months.distinct().size == schedule.default.months.size) {
                "Некорректные месяцы приёма"
            }
            val overrides = schedule.overrides.mapValues { (month, override) ->
                require(Regex("\\d{4}-(0[1-9]|1[0-2])").matches(month)) { "Некорректный месяц" }
                val yearMonth = YearMonth.parse(month)
                val old = existing[month]
                require(yearMonth == YearMonth.from(today) || (old != null && override.copy(createdAt = old.createdAt) == old)) {
                    "Изменять график можно только на текущий месяц"
                }
                require(override.enabled != null || override.dayRange != null || override.hours != null) {
                    "Укажите хотя бы один параметр исключения"
                }
                override.dayRange?.let { validateRange(it, yearMonth.lengthOfMonth()) }
                override.hours?.let(::validateHours)
                // Existing entries without metadata use their month as the creation date.
                override.copy(createdAt = old?.createdAt ?: if (old != null) yearMonth.atDay(1).toString() else today.toString())
            }.filterValues { !LocalDate.parse(it.createdAt).isBefore(today.minusMonths(3)) }
            val result = settings.copy(schedules = FinSupportSchedules(schedule.copy(overrides = overrides)))
            preferences[finSupportSettingsKey] = json.encodeToString(result)
        }
        return read(preferences)
    }

    private fun read(preferences: Preferences): FinSupportSettings = preferences[finSupportSettingsKey]
        ?.let { normalize(json.decodeFromString<FinSupportSettings>(it)) }
        ?: FinSupportSettings()

    private fun validateRange(range: DayRange, maxDay: Int) {
        require(range.from in 1..maxDay && range.to in range.from..maxDay) { "Некорректный диапазон дней" }
    }

    private fun validateHours(hours: ScheduleHours) {
        val pattern = Regex("([01]\\d|2[0-3]):[0-5]\\d")
        require(hours.days.keys == weekDays.toSet()) { "Укажите график для всех дней недели" }
        hours.days.values.forEach { day ->
            require(!day.enabled || (pattern.matches(day.from) && pattern.matches(day.to) && day.from < day.to)) {
                "Укажите время в формате ЧЧ:ММ, начало должно быть раньше окончания"
            }
        }
    }

    private fun normalize(settings: FinSupportSettings): FinSupportSettings {
        val schedule = settings.schedules.materialAid
        return FinSupportSettings(FinSupportSchedules(schedule.copy(
            default = schedule.default.copy(hours = schedule.default.hours.normalized()),
            overrides = schedule.overrides.mapValues { (_, override) -> override.copy(hours = override.hours?.normalized()) },
        )))
    }
}
