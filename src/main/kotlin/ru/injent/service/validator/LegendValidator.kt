package ru.injent.service.validator

import ru.injent.service.google.Cell
import ru.injent.service.google.SheetValidator
import ru.injent.service.google.SheetValidatorScope
import ru.injent.service.google.isWeekdayName

class LegendValidator : SheetValidator {
    override fun SheetValidatorScope.validate() {
        val detectedHeaderRowIdx = headerRowIdx ?: return
        val detectedLastScheduleRowIdx = lastScheduleRowIdx ?: return
        val fallbackFirstDayRow = validateCorpusHeaders(detectedHeaderRowIdx)

        validateTableHeaders(detectedHeaderRowIdx, subheaderRowIdx)
        validateDayBlocks(firstDayRowIdx ?: fallbackFirstDayRow, detectedLastScheduleRowIdx)
    }
}

private fun SheetValidatorScope.validateCorpusHeaders(headerRowIdx: Int): Int {
    val firstCorpusCell = cellAt(headerRowIdx, FIRST_TIME_COL_IDX)
    val secondCorpusCell = cellAt(headerRowIdx, SECOND_TIME_COL_IDX)

    firstCorpusCell?.test {
        if (value.normalizedText() != FIRST_CORPUS) {
            error("В ячейке B${headerRowIdx + 1} должен быть текст '$FIRST_CORPUS'")
        }
        if (isRedText) {
            error("Текст '$FIRST_CORPUS' не должен быть красным")
        }
        if (endColIdx != FIRST_TIME_COL_IDX) {
            error("Заголовок '$FIRST_CORPUS' не должен объединяться по столбцам")
        }
    }

    secondCorpusCell?.test {
        if (value.normalizedText() != SECOND_CORPUS) {
            error("В ячейке C${headerRowIdx + 1} должен быть текст '$SECOND_CORPUS'")
        }
        if (!isRedText) {
            error("Текст '$SECOND_CORPUS' должен быть красным")
        }
        if (endColIdx != SECOND_TIME_COL_IDX) {
            error("Заголовок '$SECOND_CORPUS' не должен объединяться по столбцам")
        }
    }

    if (firstCorpusCell != null && secondCorpusCell != null && firstCorpusCell.endRowIdx != secondCorpusCell.endRowIdx) {
        firstCorpusCell.test {
            error("Заголовки корпусов должны заканчиваться на одной строке")
        }
    }

    return maxOf(
        firstCorpusCell?.endRowIdx ?: headerRowIdx,
        secondCorpusCell?.endRowIdx ?: headerRowIdx,
    ) + 1
}

private fun SheetValidatorScope.validateTableHeaders(headerRowIdx: Int, subheaderRowIdx: Int?) {
    val lastHeaderCol = maxOf(
        lastOccupiedColInRow(headerRowIdx),
        subheaderRowIdx?.let(::lastOccupiedColInRow) ?: -1,
    )
    if (lastHeaderCol < FIRST_TIME_COL_IDX) return

    var colIdx = FIRST_TIME_COL_IDX
    while (colIdx <= lastHeaderCol) {
        val headerCell = coveringCell(headerRowIdx, colIdx)
        if (headerCell == null || headerCell.rowIdx != headerRowIdx || headerCell.value.isNullOrBlank()) {
            cellAt(headerRowIdx, colIdx)?.test {
                error("Между заголовками не должно быть пустых ячеек")
            }
            colIdx++
            continue
        }

        if (headerCell.colIdx != colIdx) {
            colIdx++
            continue
        }

        headerCell.test {
            if (value.hasGroupLikeWord()) {
                error("В названии заголовка не должно быть лишних слов кроме названия группы")
            }
        }

        val headerWidth = headerCell.endColIdx - headerCell.colIdx + 1
        val subheaders = subheaderCellsUnder(headerCell, subheaderRowIdx)

        subheaders.forEach { subheaderCell ->
            subheaderCell.test {
                if (value.hasGroupLikeWord()) {
                    error("В названии подзаголовка не должно быть лишних слов кроме названия группы")
                }
            }
        }

        if (subheaderRowIdx != null) {
            if (subheaders.isEmpty()) {
                headerCell.test {
                    if (headerWidth > 1) {
                        error("Заголовок без подзаголовков не должен объединяться по столбцам")
                    }
                }
            } else {
                headerCell.test {
                    if (headerWidth != subheaders.size) {
                        error("Количество столбцов объединенного заголовка должно совпадать с количеством подзаголовков")
                    }
                }

                for (subheaderColIdx in headerCell.colIdx..headerCell.endColIdx) {
                    val subheaderCell = coveringCell(subheaderRowIdx, subheaderColIdx)
                    if (subheaderCell == null || subheaderCell.rowIdx != subheaderRowIdx || subheaderCell.value.isNullOrBlank()) {
                        cellAt(subheaderRowIdx, subheaderColIdx)?.test {
                            error("Под объединенным заголовком не должно быть пустых подзаголовков")
                        } ?: headerCell.test {
                            error("Под объединенным заголовком не должно быть пустых подзаголовков")
                        }
                    }
                }
            }
        }

        colIdx = headerCell.endColIdx + 1
    }
}

private fun SheetValidatorScope.validateDayBlocks(firstScheduleRow: Int, lastScheduleRow: Int) {
    if (lastScheduleRow < firstScheduleRow) return

    val dayCells = dayCellsBetween(firstScheduleRow, lastScheduleRow)
    if (dayCells.isEmpty()) {
        cellAt(firstScheduleRow, DAY_COL_IDX)?.test {
            error("В столбце A должна начинаться объединенная ячейка с днем недели")
        }
        return
    }

    var expectedDayStartRow = firstScheduleRow
    dayCells.forEach { dayCell ->
        if (dayCell.rowIdx != expectedDayStartRow) {
            dayCell.test {
                error("Ячейки дней недели должны идти бесшовно одна за другой и начинаться сразу после заголовков")
            }
        }

        validateInterruptedDayRange(expectedDayStartRow, dayCell.rowIdx - 1, dayCell)

        val nextDayRow = nextNonEmptyDayCellRow(dayCell.rowIdx, lastScheduleRow)
        val scanEndRow = minOf((nextDayRow ?: (lastScheduleRow + 1)) - 1, lastScheduleRow)
            .coerceAtLeast(dayCell.rowIdx)
        val timeCells = timeCellsBetween(dayCell.rowIdx, scanEndRow)
        val expectedDayEndRow = timeCells.maxOfOrNull(Cell::endRowIdx)

        dayCell.test {
            if (!isMerged) {
                error("День недели должен быть объединенной ячейкой")
            }
            if (!value.isWeekdayName()) {
                error("В названии дня недели есть опечатка")
            }
            if (expectedDayEndRow == null) {
                if (endColIdx != SECOND_TIME_COL_IDX) {
                    error("В границах дня недели должны быть ячейки времени")
                }
            } else if (endRowIdx != expectedDayEndRow) {
                error("Объединение дня недели должно заканчиваться на строке последней ячейки времени")
            }
        }

        validateTimeCellsForDay(dayCell, scanEndRow, expectedDayEndRow)

        expectedDayStartRow = dayCell.endRowIdx + 1
    }

    if ((expectedDayStartRow..lastScheduleRow).any { rowIdx -> hasLegendValueOnRow(rowIdx) }) {
        dayCells.last().test {
            error("После последнего дня недели не должно быть данных вне его объединенной ячейки")
        }
    }
}

private fun SheetValidatorScope.validateInterruptedDayRange(
    startRowIdx: Int,
    endRowIdx: Int,
    errorCell: Cell,
) {
    if (startRowIdx > endRowIdx) return

    for (rowIdx in startRowIdx..endRowIdx) {
        if (hasLegendDataOnRow(rowIdx)) {
            errorCell.test {
                error("Данные не должны прерывать последовательность ячеек дней недели")
            }
            return
        }
    }
}

private fun SheetValidatorScope.validateTimeCellsForDay(
    dayCell: Cell,
    scanEndRow: Int,
    expectedDayEndRow: Int?,
) {
    timeCellsBetween(dayCell.rowIdx, scanEndRow).forEach { timeCell ->
        timeCell.test {
            if (!value.isTimeRange()) {
                error("Время должно быть в формате ЧЧ.ММ-ЧЧ.ММ")
            }
            if (colIdx == SECOND_TIME_COL_IDX && !isRedText) {
                error("В столбце C текст времени должен быть красным")
            }
            if (rowIdx < dayCell.rowIdx || endRowIdx > dayCell.endRowIdx) {
                error("Ячейка времени должна находиться в границах объединенной ячейки дня недели")
            }
        }
    }

    if (expectedDayEndRow == null) return

    for (rowIdx in dayCell.rowIdx..expectedDayEndRow) {
        for (colIdx in TIME_COL_IDXS) {
            val coveringTimeCell = coveringCell(rowIdx, colIdx)
            if (
                coveringTimeCell == null ||
                coveringTimeCell.colIdx != colIdx ||
                coveringTimeCell.value.isNullOrBlank() ||
                coveringTimeCell.rowIdx < dayCell.rowIdx
            ) {
                cellAt(rowIdx, colIdx)?.test {
                    error("Между ячейками времени не должно быть пустых ячеек")
                } ?: dayCell.test {
                    error("Между ячейками времени не должно быть пустых ячеек")
                }
            }
        }
    }
}

private fun SheetValidatorScope.cellAt(rowIdx: Int, colIdx: Int): Cell? =
    rows.getOrNull(rowIdx)?.getOrNull(colIdx)

private fun SheetValidatorScope.coveringCell(rowIdx: Int, colIdx: Int): Cell? =
    rows.asSequence()
        .flatMap(List<Cell>::asSequence)
        .firstOrNull { cell ->
            rowIdx in cell.rowIdx..cell.endRowIdx && colIdx in cell.colIdx..cell.endColIdx
        }

private fun SheetValidatorScope.lastOccupiedColInRow(rowIdx: Int): Int =
    rows.asSequence()
        .flatMap(List<Cell>::asSequence)
        .filter { cell -> rowIdx in cell.rowIdx..cell.endRowIdx && !cell.value.isNullOrBlank() }
        .maxOfOrNull(Cell::endColIdx)
        ?: -1

private fun SheetValidatorScope.subheaderCellsUnder(headerCell: Cell, subheaderRowIdx: Int?): List<Cell> =
    subheaderRowIdx?.let(rows::getOrNull)
        ?.filter { cell ->
            cell.rowIdx == subheaderRowIdx &&
                cell.colIdx in headerCell.colIdx..headerCell.endColIdx &&
                !cell.value.isNullOrBlank()
        }
        .orEmpty()

private fun SheetValidatorScope.nextNonEmptyDayCellRow(currentDayRow: Int, lastScheduleRow: Int): Int? =
    rows.asSequence()
        .flatMap(List<Cell>::asSequence)
        .filter { cell ->
            cell.colIdx == DAY_COL_IDX &&
                cell.rowIdx in (currentDayRow + 1)..lastScheduleRow &&
                !cell.value.isNullOrBlank()
        }
        .map(Cell::rowIdx)
        .minOrNull()

private fun SheetValidatorScope.dayCellsBetween(startRowIdx: Int, endRowIdx: Int): List<Cell> =
    rows.asSequence()
        .flatMap(List<Cell>::asSequence)
        .filter { cell ->
            cell.colIdx == DAY_COL_IDX &&
                cell.rowIdx in startRowIdx..endRowIdx &&
                !cell.value.isNullOrBlank()
        }
        .sortedBy(Cell::rowIdx)
        .toList()

private fun SheetValidatorScope.timeCellsBetween(startRowIdx: Int, endRowIdx: Int): List<Cell> =
    rows.asSequence()
        .flatMap(List<Cell>::asSequence)
        .filter { cell ->
            cell.colIdx in TIME_COL_IDXS &&
                cell.rowIdx in startRowIdx..endRowIdx &&
                !cell.value.isNullOrBlank()
        }
        .toList()

private fun SheetValidatorScope.hasLegendDataOnRow(rowIdx: Int): Boolean =
    (DAY_COL_IDX..SECOND_TIME_COL_IDX).any { colIdx ->
        val cell = coveringCell(rowIdx, colIdx)
        cell != null && (!cell.value.isNullOrBlank() || cell.borders != null)
    }

private fun SheetValidatorScope.hasLegendValueOnRow(rowIdx: Int): Boolean =
    (DAY_COL_IDX..SECOND_TIME_COL_IDX).any { colIdx ->
        !coveringCell(rowIdx, colIdx)?.value.isNullOrBlank()
    }

private fun String?.normalizedText(): String =
    orEmpty()
        .trim()
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")

private fun String?.isTimeRange(): Boolean =
    normalizedText().matches(TIME_RANGE_REGEX)

private fun String?.hasGroupLikeWord(): Boolean =
    normalizedText()
        .split(NON_LETTER_REGEX)
        .filter(String::isNotBlank)
        .any { token -> token.contains(GROUP_ROOT) || token.levenshteinDistance(GROUP_WORD) <= GROUP_WORD_MAX_DISTANCE }

private fun String.levenshteinDistance(other: String): Int {
    if (this == other) return 0
    if (isEmpty()) return other.length
    if (other.isEmpty()) return length

    var previous = IntArray(other.length + 1) { it }
    var current = IntArray(other.length + 1)

    for (rowIdx in indices) {
        current[0] = rowIdx + 1
        for (colIdx in other.indices) {
            val substitutionCost = if (this[rowIdx] == other[colIdx]) 0 else 1
            current[colIdx + 1] = minOf(
                current[colIdx] + 1,
                previous[colIdx + 1] + 1,
                previous[colIdx] + substitutionCost,
            )
        }
        val buffer = previous
        previous = current
        current = buffer
    }

    return previous[other.length]
}

private const val DAY_COL_IDX = 0
private const val FIRST_TIME_COL_IDX = 1
private const val SECOND_TIME_COL_IDX = 2
private const val FIRST_CORPUS = "1 корпус"
private const val SECOND_CORPUS = "2 корпус"
private const val GROUP_WORD = "группа"
private const val GROUP_ROOT = "груп"
private const val GROUP_WORD_MAX_DISTANCE = 2

private val TIME_COL_IDXS = FIRST_TIME_COL_IDX..SECOND_TIME_COL_IDX
private val TIME_RANGE_REGEX = Regex("\\d{1,2}\\.\\d{2}-\\d{1,2}\\.\\d{2}")
private val NON_LETTER_REGEX = Regex("[^\\p{L}]+")
