package ru.injent.service.google

import com.google.api.services.sheets.v4.model.CellData
import com.google.api.services.sheets.v4.model.GridRange
import com.google.api.services.sheets.v4.model.Sheet
import ru.injent.service.scheduleGroupNamesFromHeaders

fun interface SheetValidator {
    fun SheetValidatorScope.validate()
}

class SheetValidatorScope(
    private val sheet: Sheet
) {
    private val accumulatedErrors = mutableListOf<CellError>()

    val rows: List<List<Cell>> = sheet.data[0].rowData.mapIndexed { rowIdx, rowData ->
        rowData.getValues().mapIndexed { colIdx, cellData ->
            val range = sheet.merges.find { it.startRowIndex == rowIdx && it.startColumnIndex == colIdx }

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

    private val initialErrorCells = rows.flatten().filter(Cell::hasBackground)

    /**
     * Возвращет ошибки, которые были исправлены во время проверки
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
     * Возвращает накопленные ошибки за время проверки
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

data class CellError(
    val rowIdx: Int,
    val colIdx: Int,
    val comment: String
)

data class Cell(
    private val cellRef: CellData,
    private val range: GridRange,
    val isMerged: Boolean
) {
    /**
     * The start row (inclusive) of the range
     */
    val rowIdx: Int
        get() = range.startRowIndex

    /**
     * The start column (inclusive) of the range
     */
    val colIdx: Int
        get() = range.startColumnIndex

    /**
     * The end row (inclusive) of the range
     */
    val endRowIdx: Int
        get() = range.endRowIndex.minus(1).coerceAtLeast(0)

    /**
     * The end column (inclusive) of the range
     */
    val endColIdx: Int
        get() = range.endColumnIndex.minus(1).coerceAtLeast(0)

    val value: String?
        get() = cellRef.userEnteredValue?.let {
            it.stringValue ?: it.numberValue?.toString() ?: it.boolValue?.toString()
        }?.normalizeCellValue()

    val isRedText: Boolean
        get() = (cellRef.userEnteredFormat?.textFormat?.foregroundColor?.red ?: 0f) >= 0.9f

    val isBoldText: Boolean
        get() = cellRef.userEnteredFormat?.textFormat?.bold ?: false

    val hasBackground: Boolean
        get() = cellRef.userEnteredFormat?.backgroundColor != null

    val borders: Borders?
        get() = cellRef.userEnteredFormat?.borders?.let { borders ->
            Borders(
                top = borders.top != null,
                left = borders.left != null,
                right = borders.right != null,
                bottom = borders.bottom != null
            ).takeIf { borders ->
                arrayOf(
                    borders.top,
                    borders.left,
                    borders.right,
                    borders.bottom
                ).any { it }
            }
        }

    fun isEmpty() = value.isNullOrBlank() && borders == null

    data class Borders(
        val top: Boolean,
        val left: Boolean,
        val right: Boolean,
        val bottom: Boolean
    )

    override fun toString(): String {
        return "Cell($rowIdx:$colIdx${if (isMerged) "$endRowIdx:$endColIdx" else ""} '$value')"
    }
}

internal fun SheetValidatorScope.scheduleGroupNames(): List<String> =
    scheduleGroupNamesFromHeaders(rows)

private fun String.normalizeCellValue(): String =
    replace(LINE_BREAKS_REGEX, " ")
        .replace(INVISIBLE_CHARS_REGEX, "")
        .replace(SPACES_REGEX, " ")
        .trim()

private val LINE_BREAKS_REGEX = Regex("[\\r\\n\\t]+")
private val INVISIBLE_CHARS_REGEX = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u00AD\\u034F\\u061C\\u115F\\u1160\\u17B4\\u17B5\\u180E\\u200B-\\u200F\\u2028\\u2029\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")
private val SPACES_REGEX = Regex("[\\s\\u00A0]{2,}")
