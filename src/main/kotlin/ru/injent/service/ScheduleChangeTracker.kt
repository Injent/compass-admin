package ru.injent.service

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object ScheduleFileApprovals : Table("schedule_file_approvals") {
    val fileId = varchar("file_id", 160)
    val fingerprint = varchar("fingerprint", 64)
    override val primaryKey = PrimaryKey(fileId)
}

class ScheduleChangeTracker(private val database: Database) {
    fun isChanged(fileId: String, fingerprint: String): Boolean = transaction(database) {
        ScheduleFileApprovals.selectAll().where { ScheduleFileApprovals.fileId eq fileId }
            .singleOrNull()?.get(ScheduleFileApprovals.fingerprint) != fingerprint
    }

    fun approve(fingerprints: Map<String, String>, activeFileIds: Set<String>) = transaction(database) {
        ScheduleFileApprovals.selectAll().toList()
            .filter { it[ScheduleFileApprovals.fileId] !in activeFileIds }
            .forEach { row ->
                ScheduleFileApprovals.deleteWhere { fileId eq row[ScheduleFileApprovals.fileId] }
            }
        fingerprints.forEach { (id, hash) ->
            ScheduleFileApprovals.upsert {
                it[fileId] = id
                it[fingerprint] = hash
            }
        }
    }
}
