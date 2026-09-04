package ru.injent.service

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.injent.database.SCHEDULE_GROUP_SYNC_STATUS_ACTIVE
import ru.injent.database.SCHEDULE_GROUP_SYNC_STATUS_DELETE_PENDING
import ru.injent.database.ScheduleGroups
import ru.injent.service.google.Cell

internal data class ScheduleGroup(
    val name: String,
    val normalizedName: String,
)

class ScheduleGroupService(
    private val database: Database,
) {
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
        val fileRows = ScheduleGroups
            .selectAll()
            .filter { row -> row[ScheduleGroups.fileId] == fileId }

        fileRows
            .filter { row -> row[ScheduleGroups.normalizedName] !in normalizedNames }
            .forEach { row ->
                ScheduleGroups.update({ ScheduleGroups.id eq row[ScheduleGroups.id] }) {
                    it[ScheduleGroups.syncStatus] = SCHEDULE_GROUP_SYNC_STATUS_DELETE_PENDING
                }
            }

        groups.forEach { group ->
            val existingRow = fileRows.firstOrNull { row ->
                row[ScheduleGroups.normalizedName] == group.normalizedName
            }

            if (existingRow == null) {
                ScheduleGroups.insert {
                    it[ScheduleGroups.fileId] = fileId
                    it[ScheduleGroups.name] = group.name
                    it[ScheduleGroups.normalizedName] = group.normalizedName
                    it[ScheduleGroups.syncStatus] = SCHEDULE_GROUP_SYNC_STATUS_ACTIVE
                }
            } else {
                ScheduleGroups.update({ ScheduleGroups.id eq existingRow[ScheduleGroups.id] }) {
                    it[ScheduleGroups.name] = group.name
                    it[ScheduleGroups.normalizedName] = group.normalizedName
                    it[ScheduleGroups.syncStatus] = SCHEDULE_GROUP_SYNC_STATUS_ACTIVE
                }
            }
        }
    }

    fun markFilesDeleted(fileIds: Collection<String>) = transaction(database) {
        val ids = fileIds.toSet()
        if (ids.isEmpty()) return@transaction

        ScheduleGroups
            .selectAll()
            .filter { row -> row[ScheduleGroups.fileId] in ids }
            .forEach { row ->
                ScheduleGroups.update({ ScheduleGroups.id eq row[ScheduleGroups.id] }) {
                    it[ScheduleGroups.syncStatus] = SCHEDULE_GROUP_SYNC_STATUS_DELETE_PENDING
                }
            }
    }

    fun markMissingFilesDeleted(existingFileIds: Collection<String>) {
        val existingIds = existingFileIds.toSet()
        val missingIds = transaction(database) {
            ScheduleGroups
                .selectAll()
                .map { row -> row[ScheduleGroups.fileId] }
                .filterNot(existingIds::contains)
                .distinct()
        }
        markFilesDeleted(missingIds)
    }

    fun groupsToRemove(): List<String> = transaction(database) {
        val rows = ScheduleGroups.selectAll().toList()
        val activeGroupNames = rows
            .filter { row -> row[ScheduleGroups.syncStatus] == SCHEDULE_GROUP_SYNC_STATUS_ACTIVE }
            .mapTo(mutableSetOf()) { row -> row[ScheduleGroups.normalizedName] }

        rows
            .filter { row ->
                row[ScheduleGroups.syncStatus] == SCHEDULE_GROUP_SYNC_STATUS_DELETE_PENDING &&
                    row[ScheduleGroups.normalizedName] !in activeGroupNames
            }
            .map { row -> row[ScheduleGroups.normalizedName] }
            .distinct()
            .sorted()
    }

    fun deleteSyncedGroups(normalizedGroupNames: Collection<String>) = transaction(database) {
        val names = normalizedGroupNames.toSet()
        if (names.isEmpty()) return@transaction

        ScheduleGroups
            .selectAll()
            .filter { row ->
                row[ScheduleGroups.syncStatus] == SCHEDULE_GROUP_SYNC_STATUS_DELETE_PENDING &&
                    row[ScheduleGroups.normalizedName] in names
            }
            .forEach { row ->
                ScheduleGroups.deleteWhere { ScheduleGroups.id eq row[ScheduleGroups.id] }
            }
    }
}

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

fun String.normalizedGroupName(): String =
    trim()
        .lowercase()
        .replace('ё', 'е')
        .replace(WHITESPACE_REGEX, " ")
        .replace(PARENTHESES_SPACES_REGEX, "\$1")

private fun combineGroupName(headerName: String, subheaderName: String): String {
    val normalizedHeader = headerName.normalizedGroupName()
    val normalizedSubheader = subheaderName.normalizedGroupName()

    return when {
        normalizedSubheader.startsWith(normalizedHeader) -> subheaderName
        subheaderName.startsWith("(") || subheaderName.startsWith("[") -> headerName + subheaderName
        else -> "$headerName($subheaderName)"
    }
}

private const val FIRST_GROUP_COL_IDX = 3

private val WHITESPACE_REGEX = Regex("\\s+")
private val PARENTHESES_SPACES_REGEX = Regex("\\s*([()])\\s*")
