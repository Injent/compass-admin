package ru.injent.service.google

import com.google.api.services.sheets.v4.model.CellData
import com.google.api.services.sheets.v4.model.GridRange
import com.google.api.services.sheets.v4.model.Sheet

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
        }

    val isRedText: Boolean
        get() = (cellRef.userEnteredFormat?.textFormat?.foregroundColor?.red ?: 0f) >= 0.9f

    val isBoldText: Boolean
        get() = cellRef.userEnteredFormat?.textFormat?.bold ?: false

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