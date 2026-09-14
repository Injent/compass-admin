package ru.injent.service.google.model

import com.google.api.services.sheets.v4.model.CellData
import com.google.api.services.sheets.v4.model.GridRange

/**
 * Информация об ошибке в ячейке листа.
 */
data class CellError(
    val rowIdx: Int,
    val colIdx: Int,
    val comment: String
)

/**
 * Обертка над ячейкой Google Таблиц с удобными свойствами диапазона и форматирования.
 */
data class Cell(
    private val cellRef: CellData?,
    private val range: GridRange,
    val isMerged: Boolean
) {
    val rowIdx: Int
        get() = range.startRowIndex

    val colIdx: Int
        get() = range.startColumnIndex

    val endRowIdx: Int
        get() = range.endRowIndex.minus(1).coerceAtLeast(0)

    val endColIdx: Int
        get() = range.endColumnIndex.minus(1).coerceAtLeast(0)

    val value: String?
        get() = cellRef?.userEnteredValue?.let {
            it.stringValue ?: it.numberValue?.toString() ?: it.boolValue?.toString()
        }?.normalizeCellValue()

    val isRedText: Boolean
        get() = (cellRef?.userEnteredFormat?.textFormat?.foregroundColor?.red ?: 0f) >= 0.9f

    val isBoldText: Boolean
        get() = cellRef?.userEnteredFormat?.textFormat?.bold ?: false

    val note: String? get() = cellRef?.note

    val hasBackground: Boolean
        get() = cellRef?.userEnteredFormat?.backgroundColor != null

    val borders: Borders?
        get() = cellRef?.userEnteredFormat?.borders?.let { borders ->
            Borders(
                top = borders.top != null,
                left = borders.left != null,
                right = borders.right != null,
                bottom = borders.bottom != null
            ).takeIf { b ->
                arrayOf(b.top, b.left, b.right, b.bottom).any { it }
            }
        }

    fun isEmpty() = value.isNullOrBlank() && borders == null

    data class Borders(
        val top: Boolean,
        val left: Boolean,
        val right: Boolean,
        val bottom: Boolean
    ) {
        val isTopOnly: Boolean
            get() = top && !left && !right && !bottom
    }

    override fun toString(): String {
        return "Cell($rowIdx:$colIdx${if (isMerged) "$endRowIdx:$endColIdx" else ""} '$value')"
    }
}

/**
 * Заголовок группы в листе таблицы.
 */
internal data class GroupHeaderCell(
    val name: String,
    val sheetId: Int,
    val row: Int,
    val column: Int,
    val note: String?,
)

private fun String.normalizeCellValue(): String =
    replace(LINE_BREAKS_REGEX, " ")
        .replace(INVISIBLE_CHARS_REGEX, "")
        .replace(SPACES_REGEX, " ")
        .trim()

private val LINE_BREAKS_REGEX = Regex("[\\r\\n\\t]+")
private val INVISIBLE_CHARS_REGEX = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u00AD\\u034F\\u061C\\u115F\\u1160\\u17B4\\u17B5\\u180E\\u200B-\\u200F\\u2028\\u2029\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")
private val SPACES_REGEX = Regex("[\\s\\u00A0]{2,}")
