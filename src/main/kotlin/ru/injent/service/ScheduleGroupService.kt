package ru.injent.service

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import ru.injent.database.SCHEDULE_GROUP_SYNC_STATUS_ACTIVE
import ru.injent.database.SCHEDULE_GROUP_SYNC_STATUS_DELETE_PENDING
import ru.injent.database.ScheduleGroupsTable
import ru.injent.domain.ScheduleGroup
import ru.injent.service.google.model.Cell
import ru.injent.util.combineGroupName
import ru.injent.util.normalizedGroupName

/**
 * Сервис учета и синхронизации учебных групп расписания в БД.
 */
class ScheduleGroupService(
    private val database: Database,
) {
    /**
     * Синхронизирует список групп для файла расписания [fileId].
     */
    fun syncGroups(fileId: String, groupNames: Collection<String>) = transaction(database) {
        val groups = groupNames
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { name ->
                ScheduleGroup(
                    name = name,
                    normalizedName = name.normalizedGroupName(),
                )
            }
            .distinctBy(ScheduleGroup::normalizedName)
        val normalizedNames = groups.mapTo(mutableSetOf(), ScheduleGroup::normalizedName)
        val fileRows = ScheduleGroupsTable
            .selectAll()
            .filter { row -> row[ScheduleGroupsTable.fileId] == fileId }

        fileRows
            .filter { row -> row[ScheduleGroupsTable.normalizedName] !in normalizedNames }
            .forEach { row ->
                ScheduleGroupsTable.update({ ScheduleGroupsTable.id eq row[ScheduleGroupsTable.id] }) {
                    it[ScheduleGroupsTable.syncStatus] = SCHEDULE_GROUP_SYNC_STATUS_DELETE_PENDING
                }
            }

        groups.forEach { group ->
            val existingRow = fileRows.firstOrNull { row ->
                row[ScheduleGroupsTable.normalizedName] == group.normalizedName
            }

            if (existingRow == null) {
                ScheduleGroupsTable.insert {
                    it[ScheduleGroupsTable.fileId] = fileId
                    it[ScheduleGroupsTable.name] = group.name
                    it[ScheduleGroupsTable.normalizedName] = group.normalizedName
                    it[ScheduleGroupsTable.syncStatus] = SCHEDULE_GROUP_SYNC_STATUS_ACTIVE
                }
            } else {
                ScheduleGroupsTable.update({ ScheduleGroupsTable.id eq existingRow[ScheduleGroupsTable.id] }) {
                    it[ScheduleGroupsTable.name] = group.name
                    it[ScheduleGroupsTable.normalizedName] = group.normalizedName
                    it[ScheduleGroupsTable.syncStatus] = SCHEDULE_GROUP_SYNC_STATUS_ACTIVE
                }
            }
        }
    }

    fun markFilesDeleted(fileIds: Collection<String>) = transaction(database) {
        val ids = fileIds.toSet()
        if (ids.isEmpty()) return@transaction

        ScheduleGroupsTable
            .selectAll()
            .filter { row -> row[ScheduleGroupsTable.fileId] in ids }
            .forEach { row ->
                ScheduleGroupsTable.update({ ScheduleGroupsTable.id eq row[ScheduleGroupsTable.id] }) {
                    it[ScheduleGroupsTable.syncStatus] = SCHEDULE_GROUP_SYNC_STATUS_DELETE_PENDING
                }
            }
    }

    fun markMissingFilesDeleted(existingFileIds: Collection<String>) {
        val existingIds = existingFileIds.toSet()
        val missingIds = transaction(database) {
            ScheduleGroupsTable
                .selectAll()
                .map { row -> row[ScheduleGroupsTable.fileId] }
                .filterNot(existingIds::contains)
                .distinct()
        }
        markFilesDeleted(missingIds)
    }

    internal fun activeGroupsByFile(): Map<String, List<ScheduleGroup>> = transaction(database) {
        ScheduleGroupsTable.selectAll()
            .filter { it[ScheduleGroupsTable.syncStatus] == SCHEDULE_GROUP_SYNC_STATUS_ACTIVE }
            .groupBy({ it[ScheduleGroupsTable.fileId] }, {
                ScheduleGroup(it[ScheduleGroupsTable.name], it[ScheduleGroupsTable.normalizedName])
            })
    }

    fun groupsToRemove(): List<String> = transaction(database) {
        val rows = ScheduleGroupsTable.selectAll().toList()
        val activeGroupNames = rows
            .filter { row -> row[ScheduleGroupsTable.syncStatus] == SCHEDULE_GROUP_SYNC_STATUS_ACTIVE }
            .mapTo(mutableSetOf()) { row -> row[ScheduleGroupsTable.normalizedName] }

        rows
            .filter { row ->
                row[ScheduleGroupsTable.syncStatus] == SCHEDULE_GROUP_SYNC_STATUS_DELETE_PENDING &&
                    row[ScheduleGroupsTable.normalizedName] !in activeGroupNames
            }
            .map { row -> row[ScheduleGroupsTable.normalizedName] }
            .distinct()
            .sorted()
    }

    fun deleteSyncedGroups(normalizedGroupNames: Collection<String>) = transaction(database) {
        val names = normalizedGroupNames.toSet()
        if (names.isEmpty()) return@transaction

        ScheduleGroupsTable
            .selectAll()
            .filter { row ->
                row[ScheduleGroupsTable.syncStatus] == SCHEDULE_GROUP_SYNC_STATUS_DELETE_PENDING &&
                    row[ScheduleGroupsTable.normalizedName] in names
            }
            .forEach { row ->
                ScheduleGroupsTable.deleteWhere { ScheduleGroupsTable.id eq row[ScheduleGroupsTable.id] }
            }
    }
}

/**
 * Извлекает названия учебных групп из заголовков таблицы.
 */
internal fun scheduleGroupNamesFromHeaders(
    rows: List<List<Cell>>,
    headerRowIdx: Int,
    subheaderRowIdx: Int?,
): List<String> {
    val headerCells = rows.getOrNull(headerRowIdx)
        .orEmpty()
        .filter { cell ->
            cell.rowIdx == headerRowIdx &&
                cell.colIdx >= FIRST_GROUP_COL_IDX &&
                !cell.value.isNullOrBlank()
        }

    return headerCells
        .flatMap { headerCell ->
            val subheaderCells = subheaderRowIdx?.let(rows::getOrNull)
                .orEmpty()
                .filter { subheaderCell ->
                    subheaderCell.rowIdx == subheaderRowIdx &&
                        subheaderCell.colIdx in headerCell.colIdx..headerCell.endColIdx &&
                        !subheaderCell.value.isNullOrBlank()
                }

            if (subheaderCells.isEmpty()) {
                listOf(headerCell.value.orEmpty())
            } else {
                subheaderCells.map { subheaderCell ->
                    combineGroupName(headerCell.value.orEmpty(), subheaderCell.value.orEmpty())
                }
            }
        }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
}

private const val FIRST_GROUP_COL_IDX = 3
