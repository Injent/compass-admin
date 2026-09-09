package ru.injent.service

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.injent.database.ScheduleGroups
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleGroupServiceTest {
    @Test
    fun `groups from files missing in Drive are scheduled for removal`() {
        val databaseFile = Files.createTempFile("schedule-groups-", ".db")
        try {
            val database = Database.connect("jdbc:sqlite:$databaseFile", driver = "org.sqlite.JDBC")
            transaction(database) { SchemaUtils.create(ScheduleGroups) }
            val service = ScheduleGroupService(database)

            service.syncGroups("missing-file", listOf("ПРИ-101"))
            service.syncGroups("existing-file", listOf("ПРИ-102"))
            service.markMissingFilesDeleted(listOf("existing-file"))

            assertEquals(listOf("при-101"), service.groupsToRemove())
            assertEquals(mapOf("existing-file" to listOf(ScheduleGroup("ПРИ-102", "при-102"))), service.activeGroupsByFile())
            service.syncGroups("existing-file", listOf("ПРИ-103"))
            assertEquals(listOf(ScheduleGroup("ПРИ-103", "при-103")), service.activeGroupsByFile()["existing-file"])
        } finally {
            Files.deleteIfExists(databaseFile)
        }
    }
}
