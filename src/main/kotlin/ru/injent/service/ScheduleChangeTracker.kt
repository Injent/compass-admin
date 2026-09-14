package ru.injent.service

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import ru.injent.database.ScheduleFileApprovalsTable

/**
 * Сервис отслеживания изменений в файлах расписания по хэшу содержимого.
 */
class ScheduleChangeTracker(private val database: Database) {

    /**
     * Проверяет, изменился ли файл по сравнению с согласованным хэшем.
     */
    fun isChanged(fileId: String, fingerprint: String): Boolean = transaction(database) {
        ScheduleFileApprovalsTable.selectAll()
            .where { ScheduleFileApprovalsTable.fileId eq fileId }
            .singleOrNull()
            ?.get(ScheduleFileApprovalsTable.fingerprint) != fingerprint
    }

    /**
     * Сохраняет новые хэши согласованных файлов и удаляет отсутствующие.
     */
    fun approve(fingerprints: Map<String, String>, activeFileIds: Set<String>) = transaction(database) {
        ScheduleFileApprovalsTable.selectAll().toList()
            .filter { it[ScheduleFileApprovalsTable.fileId] !in activeFileIds }
            .forEach { row ->
                ScheduleFileApprovalsTable.deleteWhere { fileId eq row[ScheduleFileApprovalsTable.fileId] }
            }
        fingerprints.forEach { (id, hash) ->
            ScheduleFileApprovalsTable.upsert {
                it[fileId] = id
                it[fingerprint] = hash
            }
        }
    }
}
