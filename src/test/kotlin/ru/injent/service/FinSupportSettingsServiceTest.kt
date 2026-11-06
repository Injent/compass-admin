package ru.injent.service

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.edit
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import ru.injent.database.ServerSettingsTable
import ru.injent.service.config.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.*

class FinSupportSettingsServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneId.of("Europe/Moscow"))
    private fun settings(base: ScheduleDefaults = ScheduleDefaults(), overrides: Map<String, ScheduleOverride> = emptyMap()) =
        FinSupportSettings(schedules = FinSupportSchedules(MaterialAidSchedule(base, overrides)))

    private fun withStore(test: suspend (ServerSettingsStore, Database, java.io.File) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("finsupport-v2-").toFile()
        val database = Database.connect("jdbc:sqlite:${directory.resolve("legacy.db")}", driver = "org.sqlite.JDBC")
        transaction(database) { SchemaUtils.create(ServerSettingsTable) }
        val file = directory.resolve("data/prefs.preferences_pb")
        val store = ServerSettingsStore(database, file)
        try { test(store, database, file) }
        finally { store.close(); directory.deleteRecursively() }
    }

    @Test
    fun `migrates legacy hours ranges and partial overrides without losing closed months`() = withStore { store, database, _ ->
        transaction(database) {
            ServerSettingsTable.upsert {
                it[key] = "profkom"
                it[value] = """{"schedules":{"materialAid":{"default":{"enabled":true,"dayRange":{"from":1,"to":31},"months":[2,10],"hours":{"from":"09:00","to":"18:00"}},"overrides":{"2026-10":{"enabled":false,"createdAt":"2026-10-20"}}}}}"""
            }
        }
        val service = FinSupportSettingsService(store.preferences, clock)
        val loaded = service.get()
        assertEquals(3, loaded.schemaVersion)
        assertEquals("Europe/Moscow", loaded.timeZone)
        val base = loaded.schedules.materialAid.default
        assertEquals(29, base.months.getValue("FEBRUARY").dayRange.to)
        assertFalse(base.months.getValue("JANUARY").enabled)
        assertEquals("09:00", base.hours.days.getValue("MONDAY").from)
        assertFalse(loaded.schedules.materialAid.overrides.getValue("2026-10").enabled)
        assertEquals(loaded, service.update(loaded))
    }

    @Test
    fun `v2 migration persists enum keys and preserves dates overrides and month rules`() = withStore { store, _, _ ->
        val original = settings(
            ScheduleDefaults(excludedDates = listOf("01-01", "02-29")),
            mapOf("2026-11" to ScheduleOverride(enabled = false, excludedDates = listOf("2026-11-06"), createdAt = "2026-10-01")),
        )
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val root = json.encodeToJsonElement(original).jsonObject
        val schedule = root.getValue("schedules").jsonObject.getValue("materialAid").jsonObject
        fun oldHours(value: JsonElement): JsonObject = JsonObject(mapOf("days" to JsonObject(
            value.jsonObject.getValue("days").jsonObject.mapKeys { (day, _) -> day.lowercase() },
        )))
        val base = schedule.getValue("default").jsonObject
        val oldBase = JsonObject(base + ("months" to JsonObject(base.getValue("months").jsonObject.mapKeys { (month, _) ->
            "%02d".format(kotlinx.datetime.Month.valueOf(month).ordinal + 1)
        })) + ("hours" to oldHours(base.getValue("hours"))))
        val oldOverrides = JsonObject(schedule.getValue("overrides").jsonObject.mapValues { (_, element) ->
            JsonObject(element.jsonObject + ("hours" to oldHours(element.jsonObject.getValue("hours"))))
        })
        val legacy = JsonObject(root + ("schemaVersion" to JsonPrimitive(2)) + ("schedules" to JsonObject(mapOf(
            "materialAid" to JsonObject(schedule + ("default" to oldBase) + ("overrides" to oldOverrides)),
        )))).toString()
        store.preferences.edit { it[finSupportSettingsKey] = legacy }
        val service = FinSupportSettingsService(store.preferences, clock)
        assertEquals(original, service.get())
        val stored = json.decodeFromString<FinSupportSettings>(store.preferences.data.first()[finSupportSettingsKey]!!)
        assertEquals(original, stored)
        assertEquals(kotlinx.datetime.Month.entries.map { it.name }.toSet(), stored.schedules.materialAid.default.months.keys)
        assertEquals(kotlinx.datetime.DayOfWeek.entries.map { it.name }.toSet(), stored.schedules.materialAid.default.hours.days.keys)
        assertEquals(original, service.getActive())
        assertEquals(original, service.update(original))
    }

    @Test
    fun `v3 JSON is explicit and preferences survive reopening`() = withStore { store, database, file ->
        val base = ScheduleDefaults(excludedDates = listOf("01-01", "02-29"))
        val nextMonth = ScheduleOverride(dayRange = DayRange(5, 20), excludedDates = listOf("2027-01-12"))
        val service = FinSupportSettingsService(store.preferences, clock)
        val saved = service.update(settings(base, mapOf("2027-01" to nextMonth)))
        assertEquals("2026-10-30", saved.schedules.materialAid.overrides.getValue("2027-01").createdAt)
        val encoded = Json.parseToJsonElement(Json.encodeToString(saved)).jsonObject
        assertEquals(setOf("schemaVersion", "timeZone", "schedules"), encoded.keys)
        assertTrue(file.isFile)
        store.close()
        ServerSettingsStore(database, file).use { reopened ->
            assertEquals(saved, FinSupportSettingsService(reopened.preferences, clock).get())
        }
    }

    @Test
    fun `validates month ranges recurring dates override month and time atomically`() = withStore { store, _, _ ->
        val service = FinSupportSettingsService(store.preferences, clock)
        val valid = service.update(settings())
        val base = ScheduleDefaults()
        val invalid = listOf(
            settings(base.copy(months = base.months - "JANUARY")),
            settings(base.copy(months = base.months + ("FEBRUARY" to ReceptionMonth(dayRange = DayRange(1, 30))))),
            settings(base.copy(excludedDates = listOf("02-30"))),
            settings(base.copy(excludedDates = listOf("01-01", "01-01"))),
            settings(overrides = mapOf("2027-02" to ScheduleOverride(dayRange = DayRange(1, 29)))),
            settings(overrides = mapOf("2027-02" to ScheduleOverride(excludedDates = listOf("2027-03-01")))),
            settings(overrides = mapOf("2027-02" to ScheduleOverride(excludedDates = listOf("2027-02-30")))),
            settings(overrides = mapOf("bad" to ScheduleOverride())),
            settings(base.copy(hours = ScheduleHours(base.hours.days + ("MONDAY" to WorkDay(from = "17:00", to = "10:00"))))),
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { service.update(it) }; assertEquals(valid, service.get()) }
    }

    @Test
    fun `comments and details links persist and missing fields remain compatible`() = withStore { store, database, file ->
        val service = FinSupportSettingsService(store.preferences, clock)
        val oldJson = Json { encodeDefaults = true }.encodeToJsonElement(settings()).jsonObject
        val schedule = oldJson.getValue("schedules").jsonObject.getValue("materialAid").jsonObject
        val oldDefault = JsonObject(schedule.getValue("default").jsonObject - "comment" - "detailsUrl")
        val old = JsonObject(oldJson + ("schedules" to JsonObject(mapOf("materialAid" to JsonObject(schedule + ("default" to oldDefault))))))
        store.preferences.edit { it[finSupportSettingsKey] = old.toString() }
        assertEquals("", service.get().schedules.materialAid.default.comment)
        assertEquals("", service.get().schedules.materialAid.default.detailsUrl)
        for ((link, normalized) in mapOf(
            "example.org/support?q=1" to "https://example.org/support?q=1",
            " https://example.org/help " to "https://example.org/help",
            "http://example.org" to "http://example.org",
            "" to "",
        )) {
            val saved = service.update(settings(
                ScheduleDefaults(comment = "Приём по записи", detailsUrl = link),
                mapOf("2026-11" to ScheduleOverride(enabled = false, comment = "Ремонт")),
            ))
            assertEquals(normalized, saved.schedules.materialAid.default.detailsUrl)
            assertEquals("Приём по записи", saved.schedules.materialAid.default.comment)
            assertEquals("Ремонт", saved.schedules.materialAid.overrides.getValue("2026-11").comment)
            assertEquals(saved, service.getActive())
        }
        val before = service.get()
        for (link in listOf("not a link", "javascript:alert(1)", "data:text/html,test", "ftp://example.org", "https://", "https://user:password@example.org", "https://example.org:99999")) {
            assertFailsWith<IllegalArgumentException>(link) { service.update(settings(ScheduleDefaults(detailsUrl = link))) }
            assertEquals(before, service.get())
        }
        store.close()
        ServerSettingsStore(database, file).use { reopened -> assertEquals(before, FinSupportSettingsService(reopened.preferences, clock).get()) }
    }

    @Test
    fun `comments are limited to 120 characters and two lines in defaults and overrides`() = withStore { store, _, _ ->
        val service = FinSupportSettingsService(store.preferences, clock)
        val valid = service.update(settings(
            ScheduleDefaults(comment = "a".repeat(59) + "\n" + "b".repeat(60)),
            mapOf("2026-11" to ScheduleOverride(comment = "Первая строка\nВторая строка")),
        ))
        for (comment in listOf("a".repeat(121), "a\nb\nc", "a\r\nb\r\nc")) {
            assertFailsWith<IllegalArgumentException> { service.update(settings(ScheduleDefaults(comment = comment))) }
            assertFailsWith<IllegalArgumentException> { service.update(settings(overrides = mapOf("2026-11" to ScheduleOverride(comment = comment)))) }
            assertEquals(valid, service.get())
        }
    }

    @Test
    fun `override cleanup uses creation date only on updates and preserves replacement timestamp`() = withStore { store, _, _ ->
        fun at(day: String) = FinSupportSettingsService(store.preferences, Clock.fixed(Instant.parse("${day}T09:00:00Z"), ZoneId.of("Europe/Moscow")))
        val original = at("2026-07-30").update(settings(overrides = mapOf("2026-12" to ScheduleOverride())))
        val boundary = at("2026-10-30").update(original)
        assertTrue("2026-12" in boundary.schedules.materialAid.overrides)
        val replaced = at("2026-10-30").update(settings(overrides = mapOf("2026-12" to ScheduleOverride(enabled = false))))
        assertEquals("2026-07-30", replaced.schedules.materialAid.overrides.getValue("2026-12").createdAt)
        assertTrue("2026-12" in at("2026-10-31").get().schedules.materialAid.overrides)
        assertTrue(at("2026-10-31").getActive().schedules.materialAid.overrides.isEmpty())
        assertTrue("2026-12" in at("2026-10-30").getActive().schedules.materialAid.overrides)
        assertTrue(at("2026-10-31").update(replaced).schedules.materialAid.overrides.isEmpty())
    }

    @Test
    fun `date lookup combines month range weekday and annual exclusions with one-off overrides`() {
        val base = ScheduleDefaults(excludedDates = listOf("10-05"))
        val november = ReceptionMonth(dayRange = DayRange(15, 20))
        val schedule = MaterialAidSchedule(base.copy(months = base.months + ("NOVEMBER" to november)))
        assertNotNull(schedule.hoursOn(LocalDate.parse("2026-10-01")))
        assertNull(schedule.hoursOn(LocalDate.parse("2026-10-05")))
        assertNull(schedule.hoursOn(LocalDate.parse("2026-11-10")))
        assertNotNull(schedule.hoursOn(LocalDate.parse("2026-11-16")))
        val hours = ScheduleHours(base.hours.days + ("SUNDAY" to WorkDay(enabled = false)))
        val changed = schedule.copy(overrides = mapOf("2026-10" to ScheduleOverride(dayRange = DayRange(1, 20), hours = hours, excludedDates = listOf("2026-10-12"))))
        assertNull(changed.hoursOn(LocalDate.parse("2026-10-04")))
        assertNull(changed.hoursOn(LocalDate.parse("2026-10-05")))
        assertNull(changed.hoursOn(LocalDate.parse("2026-10-12")))
        assertNotNull(changed.hoursOn(LocalDate.parse("2026-10-19")))
        assertNull(changed.hoursOn(LocalDate.parse("2027-10-19")))
        assertNull(changed.copy(overrides = mapOf("2026-10" to ScheduleOverride(enabled = false))).hoursOn(LocalDate.parse("2026-10-01")))
    }
}
