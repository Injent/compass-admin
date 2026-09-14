package ru.injent.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Таблица согласованных хешей файлов расписания.
 */
object ScheduleFileApprovalsTable : Table("schedule_file_approvals") {
    val fileId = varchar("file_id", 160)
    val fingerprint = varchar("fingerprint", 64)
    override val primaryKey = PrimaryKey(fileId)
}
