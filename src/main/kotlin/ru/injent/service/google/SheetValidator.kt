package ru.injent.service.google

import com.google.api.services.sheets.v4.model.GridRange
import com.google.api.services.sheets.v4.model.Sheet
import ru.injent.service.google.model.Cell
import ru.injent.service.google.model.CellError
import ru.injent.service.google.model.GroupHeaderCell
import ru.injent.service.scheduleGroupNamesFromHeaders
import ru.injent.util.combineGroupName

/**
 * Интерфейс валидатора листа таблицы расписания.
 */
fun interface SheetValidator {
    fun SheetValidatorScope.validate()
}

/**
 * Контекст выполнения валидации листа таблицы с агрегацией ошибок.
 */
class SheetValidatorScope(
    private val sheet: Sheet
) {
    private val accumulatedErrors = mutableListOf<CellError>()

    val rows: List<List<Cell>> = sheet.data?.firstOrNull()?.rowData.orEmpty().mapIndexed { rowIdx, rowData ->
        rowData?.getValues().orEmpty().mapIndexed { colIdx, cellData ->
            val range = sheet.merges.orEmpty().find { it.startRowIndex == rowIdx && it.startColumnIndex == colIdx }

            Cell(
                cellRef = cellData,
                range = range ?: GridRange().apply {
                    startRowIndex = rowIdx
                    startColumnIndex = colIdx
                    endRowIndex = rowIdx + 1
                    endColumnIndex = colIdx + 1
                },
                isMerged = range != null
            )
        }
    }.let { rows ->
        val lastActiveCol = rows
            .maxOfOrNull { row ->
                row.indexOfLast { !it.isEmpty() }
            } ?: return@let rows

        rows
            .map { it.take(lastActiveCol + 1) }
            .dropLastWhile { row ->
                row.all(Cell::isEmpty)
            }
    }

    private val borderedCells = rows.asSequence()
        .flatMap(List<Cell>::asSequence)
        .filter { cell -> cell.borders != null }

    private val borderedRowIndexes = borderedCells
        .flatMap { cell -> (cell.rowIdx..cell.endRowIdx).asSequence() }
        .toSet()

    private val scheduleContentRowIndexes = borderedCells
        .filter { cell -> cell.borders?.isTopOnly == false }
        .flatMap { cell -> (cell.rowIdx..cell.endRowIdx).asSequence() }
        .toSet()

    internal val firstScheduleRowIdx: Int? = borderedRowIndexes.minOrNull()

    internal val lastScheduleRowIdx: Int? = firstScheduleRowIdx?.let { firstRow ->
        generateSequence(firstRow) { rowIdx -> rowIdx + 1 }
            .takeWhile { rowIdx -> rowIdx == firstRow || rowIdx in scheduleContentRowIndexes }
            .last()
    }

    internal val headerRowIdx: Int? = run {
        val firstRow = firstScheduleRowIdx ?: return@run null
        val lastRow = lastScheduleRowIdx ?: return@run null

        (firstRow..lastRow).firstOrNull { rowIdx ->
            val row = rows.getOrNull(rowIdx).orEmpty()
            TIME_COL_IDXS.all { colIdx ->
                row.any { cell -> cell.colIdx == colIdx && !cell.value.isNullOrBlank() }
            }
        }
    }

    internal val firstDayRowIdx: Int? = run {
        val firstPossibleRow = headerRowIdx?.plus(1) ?: return@run null
        val lastPossibleRow = lastScheduleRowIdx ?: return@run null

        rows.asSequence()
            .flatMap(List<Cell>::asSequence)
            .filter { cell ->
                cell.colIdx == DAY_COL_IDX &&
                    cell.rowIdx in firstPossibleRow..lastPossibleRow &&
                    cell.value.isWeekdayName()
            }
            .minOfOrNull(Cell::rowIdx)
    }

    internal val subheaderRowIdx: Int?
        get() {
            val headerRow = headerRowIdx ?: return null
            return firstDayRowIdx?.minus(1)?.takeIf { it > headerRow }
        }

    private val initialErrorCells = rows.flatten().filter(Cell::hasBackground)

    /**
     * Возвращает ошибки, которые были исправлены во время проверки.
     */
    fun getFixedErrors(): List<CellError> {
        val currentErrorCells = accumulatedErrors
            .map { error -> error.rowIdx to error.colIdx }
            .toSet()

        return initialErrorCells
            .filter { cell -> cell.rowIdx to cell.colIdx !in currentErrorCells }
            .map { cell ->
                CellError(
                    rowIdx = cell.rowIdx,
                    colIdx = cell.colIdx,
                    comment = ""
                )
            }
    }

    /**
     * Возвращает накопленные ошибки за время проверки.
     */
    fun getAccumulatedErrors(): List<CellError> = accumulatedErrors

    fun Cell.test(block: Cell.() -> Unit) {
        try {
            block(this)
        } catch (e: IllegalStateException) {
            accumulatedErrors += CellError(
                rowIdx = rowIdx,
                colIdx = colIdx,
                comment = e.message ?: "Error not specified"
            )
        }
    }
}

internal fun SheetValidatorScope.scheduleGroupNames(): List<String> {
    val detectedHeaderRowIdx = headerRowIdx ?: return emptyList()

    return scheduleGroupNamesFromHeaders(
        rows = rows,
        headerRowIdx = detectedHeaderRowIdx,
        subheaderRowIdx = subheaderRowIdx,
    )
}

internal fun String?.isWeekdayName(): Boolean =
    orEmpty().trim().lowercase().replace('ё', 'е') in WEEKDAY_NAMES

internal fun SheetValidatorScope.groupHeaderCells(sheetId: Int): List<GroupHeaderCell> =
    rows.getOrNull(headerRowIdx ?: -1).orEmpty()
        .filter { it.colIdx >= 3 && !it.value.isNullOrBlank() }
        .flatMap { header ->
            val subheaders = rows.getOrNull(subheaderRowIdx ?: -1).orEmpty()
                .filter { it.colIdx in header.colIdx..header.endColIdx && !it.value.isNullOrBlank() }
            if (subheaders.isEmpty()) {
                listOf(GroupHeaderCell(header.value.orEmpty(), sheetId, header.rowIdx, header.colIdx, header.note))
            } else {
                subheaders.map { cell ->
                    GroupHeaderCell(combineGroupName(header.value.orEmpty(), cell.value.orEmpty()), sheetId, cell.rowIdx, cell.colIdx, cell.note)
                }
            }
        }

private const val DAY_COL_IDX = 0
private val TIME_COL_IDXS = 1..2
private val WEEKDAY_NAMES = setOf(
    "понедельник",
    "вторник",
    "среда",
    "четверг",
    "пятница",
    "суббота",
    "воскресенье",
)
