package ru.injent.service

import com.google.api.services.sheets.v4.model.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.injent.service.google.scheduleFingerprint
import java.nio.file.Files
import kotlin.test.*

class ScheduleChangeTrackerTest {
    @Test
    fun `approval survives restart and tracks edits and restored files`() {
        val path = Files.createTempFile("schedule-approvals-", ".db")
        try {
            val database = Database.connect("jdbc:sqlite:$path", driver = "org.sqlite.JDBC")
            transaction(database) { SchemaUtils.create(ScheduleFileApprovals) }
            val tracker = ScheduleChangeTracker(database)
            assertTrue(tracker.isChanged("a", "v1"))
            tracker.approve(mapOf("a" to "v1", "b" to "v1"), setOf("a", "b"))
            val restarted = ScheduleChangeTracker(database)
            assertFalse(restarted.isChanged("a", "v1"))
            assertTrue(restarted.isChanged("a", "v2"))
            assertFalse(restarted.isChanged("b", "v1"))
            // Only acknowledge the version actually sent: a later edit stays dirty.
            restarted.approve(mapOf("a" to "v2"), setOf("a", "b"))
            assertTrue(restarted.isChanged("a", "v3"))
            assertFalse(restarted.isChanged("b", "v1"))
            restarted.approve(emptyMap(), setOf("b"))
            assertTrue(restarted.isChanged("a", "v2"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `fingerprint ignores validation decoration but detects schedule edits`() {
        val cell = CellData().setUserEnteredValue(ExtendedValue().setStringValue("Математика"))
        val sheet = Sheet().setProperties(SheetProperties().setTitle("Расписание"))
            .setData(listOf(GridData().setRowData(listOf(RowData().setValues(listOf(cell))))))
        val spreadsheet = Spreadsheet().setSheets(listOf(sheet))
        val original = spreadsheet.scheduleFingerprint("file.xlsx")
        cell.setNote("Проверено").setUserEnteredFormat(CellFormat().setBackgroundColor(Color().setRed(1f)))
        assertEquals(original, spreadsheet.scheduleFingerprint("file.xlsx"))
        cell.userEnteredValue.stringValue = "Физика"
        assertNotEquals(original, spreadsheet.scheduleFingerprint("file.xlsx"))
        cell.userEnteredValue.stringValue = "Математика"
        assertEquals(original, spreadsheet.scheduleFingerprint("file.xlsx"))
        assertNotEquals(original, spreadsheet.scheduleFingerprint("renamed.xlsx"))
        sheet.setMerges(listOf(GridRange().setStartRowIndex(0).setEndRowIndex(2)))
        assertNotEquals(original, spreadsheet.scheduleFingerprint("file.xlsx"))
    }
}
