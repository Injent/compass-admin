package ru.injent.service

import ru.injent.service.google.Cell

internal data class ScheduleGroup(
    val name: String,
    val normalizedName: String,
)

internal fun scheduleGroupNamesFromHeaders(rows: List<List<Cell>>): List<String> {
    val headerCells = rows.getOrNull(HEADER_ROW_IDX)
        .orEmpty()
        .filter { cell ->
            cell.rowIdx == HEADER_ROW_IDX &&
                cell.colIdx >= FIRST_GROUP_COL_IDX &&
                !cell.value.isNullOrBlank()
        }

    return headerCells
        .flatMap { headerCell ->
            val subheaderCells = rows.getOrNull(SUBHEADER_ROW_IDX)
                .orEmpty()
                .filter { subheaderCell ->
                    subheaderCell.rowIdx == SUBHEADER_ROW_IDX &&
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

private const val HEADER_ROW_IDX = 1
private const val SUBHEADER_ROW_IDX = 2
private const val FIRST_GROUP_COL_IDX = 3

private val WHITESPACE_REGEX = Regex("\\s+")
private val PARENTHESES_SPACES_REGEX = Regex("\\s*([()])\\s*")
