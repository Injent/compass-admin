package ru.injent.service

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import ru.injent.database.ServerSettingsTable
import ru.injent.service.config.*
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class FinSupportSettingsServiceTest {
    @Test
    fun `API serialization includes all default fields without omitted override parameters`() {
        val encoded = Json.parseToJsonElement(Json.encodeToString(settings(mapOf("2026-10" to ScheduleOverride(enabled = false)))))
            .jsonObject.getValue("schedules").jsonObject.getValue("materialAid").jsonObject
        assertEquals(setOf("enabled", "dayRange", "hours", "months"), encoded.getValue("default").jsonObject.keys)
        assertEquals(setOf("enabled"), encoded.getValue("overrides").jsonObject.getValue("2026-10").jsonObject.keys)
        assertTrue("overrides" in Json.parseToJsonElement(Json.encodeToString(FinSupportSettings())).jsonObject
            .getValue("schedules").jsonObject.getValue("materialAid").jsonObject)
    }

    @Test
    fun `weekly hours and reception months persist and old hours migrate`() = withDatabase { database ->
        transaction(database) {
            ServerSettingsTable.upsert {
                it[key] = "profkom"
                it[value] = """{"schedules":{"materialAid":{"default":{"hours":{"from":"09:00","to":"18:00"}},"overrides":{"2026-10":{"hours":{"from":"11:00","to":"16:00"}}}}}}"""
            }
        }
        val service = FinSupportSettingsService(store.preferences, clock("2026-10-20"))
        val loaded = service.get()
        assertEquals(7, loaded.schedules.materialAid.default.hours.days.size)
        assertTrue(loaded.schedules.materialAid.default.hours.days.values.all { it.from == "09:00" && it.to == "18:00" })
        assertEquals("11:00", loaded.schedules.materialAid.overrides.getValue("2026-10").hours!!.days.getValue("monday").from)
        assertEquals((1..12).toList(), loaded.schedules.materialAid.default.months)
        val days = loaded.schedules.materialAid.default.hours.days + ("sunday" to WorkDay(false, "00:00", "00:00"))
        val defaults = loaded.schedules.materialAid.default.copy(hours = ScheduleHours(days = days), months = listOf(1, 5, 12))
        val result = service.update(settings(emptyMap(), defaults))
        assertEquals(result, FinSupportSettingsService(store.preferences, clock("2026-10-20")).get())
        assertEquals(listOf(1, 5, 12), result.schedules.materialAid.default.months)
        assertFalse(result.schedules.materialAid.default.hours.days.getValue("sunday").enabled)
        for (invalid in listOf(
            defaults.copy(months = listOf(0, 13)), defaults.copy(months = listOf(1, 1)),
            defaults.copy(hours = ScheduleHours(days = days - "monday")),
            defaults.copy(hours = ScheduleHours(days = days + ("monday" to WorkDay(true, "18:00", "10:00")))),
        )) assertFailsWith<IllegalArgumentException> { service.update(settings(emptyMap(), invalid)) }
        assertEquals(result, service.get())
    }

    @Test
    fun `preferences survive store restart and do not import stale sqlite settings again`() = withDatabase { database ->
        val file = Files.createTempDirectory("finsupport-restart-").resolve("data/server.preferences_pb").toFile()
        var localStore = ServerSettingsStore(database, file)
        try {
            val service = FinSupportSettingsService(localStore.preferences, clock("2026-10-20"))
            val saved = service.update(settings(emptyMap(), ScheduleDefaults(months = listOf(2, 10))))
            assertTrue(file.isFile)
            transaction(database) {
                ServerSettingsTable.upsert {
                    it[key] = "profkom"
                    it[value] = Json.encodeToString(FinSupportSettings())
                }
            }
            localStore.close()
            localStore = ServerSettingsStore(database, file)
            assertEquals(saved, FinSupportSettingsService(localStore.preferences, clock("2026-10-20")).get())
        } finally {
            localStore.close()
            file.parentFile.parentFile.deleteRecursively()
        }
    }

    private val zone = ZoneId.of("Europe/Moscow")
    private fun clock(date: String) = Clock.fixed(Instant.parse("${date}T09:00:00Z"), zone)
    private fun settings(overrides: Map<String, ScheduleOverride>, defaults: ScheduleDefaults = ScheduleDefaults()) =
        FinSupportSettings(FinSupportSchedules(MaterialAidSchedule(defaults, overrides)))

    private lateinit var store: ServerSettingsStore

    private fun withDatabase(test: suspend (Database) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("finsupport-")
        val path = directory.resolve("legacy.db")
        try {
            val database = Database.connect("jdbc:sqlite:$path", driver = "org.sqlite.JDBC")
            transaction(database) { SchemaUtils.create(ServerSettingsTable) }
            store = ServerSettingsStore(database, directory.resolve("data/server.preferences_pb").toFile())
            test(database)
        } finally {
            store.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `partial override persists locally and creation date survives replacement`() = withDatabase { database ->
        val service = FinSupportSettingsService(store.preferences, clock("2026-10-20"))
        assertEquals(FinSupportSettings(), service.get())
        val saved = service.update(settings(mapOf("2026-10" to ScheduleOverride(enabled = false))))
        assertEquals("2026-10-20", saved.schedules.materialAid.overrides.getValue("2026-10").createdAt)
        val restarted = FinSupportSettingsService(store.preferences, clock("2026-10-25"))
        assertEquals(saved, restarted.get())
        val updated = restarted.update(settings(mapOf("2026-10" to ScheduleOverride(dayRange = DayRange(1, 20), createdAt = "2099-01-01"))))
        val override = updated.schedules.materialAid.overrides.getValue("2026-10")
        assertEquals("2026-10-20", override.createdAt)
        assertNull(override.enabled)
        assertNull(override.hours)
        val json = Json { encodeDefaults = true; explicitNulls = false }
        val encoded = json.parseToJsonElement(json.encodeToString(override)).jsonObject
        assertFalse("enabled" in encoded)
        assertFalse("hours" in encoded)
        assertTrue(restarted.update(settings(emptyMap())).schedules.materialAid.overrides.isEmpty())
    }

    @Test
    fun `expired overrides remain on reads and are pruned only after more than three months`() = withDatabase { database ->
        FinSupportSettingsService(store.preferences, clock("2026-07-20"))
            .update(settings(mapOf("2026-07" to ScheduleOverride(enabled = false))))
        val boundary = FinSupportSettingsService(store.preferences, clock("2026-10-20"))
        val atBoundary = boundary.update(boundary.get())
        assertTrue("2026-07" in atBoundary.schedules.materialAid.overrides)
        val later = FinSupportSettingsService(store.preferences, clock("2026-10-21"))
        assertTrue("2026-07" in later.get().schedules.materialAid.overrides)
        assertTrue(later.update(later.get()).schedules.materialAid.overrides.isEmpty())
    }

    @Test
    fun `legacy overrides get a creation date and expire on update`() = withDatabase { database ->
        transaction(database) {
            ServerSettingsTable.upsert {
                it[key] = "profkom"
                it[value] = Json.encodeToString(settings(mapOf("2026-01" to ScheduleOverride(enabled = false))))
            }
        }
        val service = FinSupportSettingsService(store.preferences, clock("2026-10-20"))
        assertTrue(service.update(service.get()).schedules.materialAid.overrides.isEmpty())
    }

    @Test
    fun `invalid ranges hours and edits outside current month do not overwrite settings`() = withDatabase { database ->
        val service = FinSupportSettingsService(store.preferences, clock("2026-02-20"))
        val invalid = listOf(
            settings(mapOf("2026-02" to ScheduleOverride(dayRange = DayRange(1, 29)))),
            settings(mapOf("2026-02" to ScheduleOverride(dayRange = DayRange(10, 1)))),
            settings(mapOf("2026-02" to ScheduleOverride(hours = ScheduleHours("25:00", "26:00")))),
            settings(mapOf("2026-02" to ScheduleOverride(hours = ScheduleHours("17:00", "10:00")))),
            settings(mapOf("2026-02" to ScheduleOverride())),
            settings(mapOf("2026-03" to ScheduleOverride(enabled = false))),
            settings(mapOf("invalid" to ScheduleOverride(enabled = false))),
            settings(emptyMap(), ScheduleDefaults(dayRange = DayRange(0, 32))),
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { service.update(it) } }
        assertEquals(FinSupportSettings(), service.get())
    }
}
