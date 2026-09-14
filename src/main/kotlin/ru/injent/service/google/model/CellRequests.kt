package ru.injent.service.google.model

import com.google.api.services.sheets.v4.model.CellData
import com.google.api.services.sheets.v4.model.CellFormat
import com.google.api.services.sheets.v4.model.Color
import com.google.api.services.sheets.v4.model.ExtendedValue
import com.google.api.services.sheets.v4.model.GridRange
import com.google.api.services.sheets.v4.model.RepeatCellRequest
import com.google.api.services.sheets.v4.model.Request

/**
 * Фабричные функции для создания запросов пакетного обновления ячеек Google Sheets.
 */

@Suppress("FunctionName")
fun CellValueRequest(
    sheetId: Int,
    colIdx: Int,
    rowIdx: Int,
    value: String,
): Request = Request().setRepeatCell(
    RepeatCellRequest()
        .setCell(
            CellData().setUserEnteredValue(
                ExtendedValue().setStringValue(value)
            )
        )
        .setRange(
            GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(rowIdx)
                .setEndRowIndex(rowIdx + 1)
                .setStartColumnIndex(colIdx)
                .setEndColumnIndex(colIdx + 1)
        )
        .setFields("userEnteredValue")
)

@Suppress("FunctionName")
fun ValidCellRequest(sheetId: Int, colIdx: Int, rowIdx: Int): Request = Request().setRepeatCell(
    RepeatCellRequest()
        .setCell(
            CellData()
                .setNote(null)
                .setUserEnteredFormat(
                    CellFormat().setBackgroundColor(null)
                )
        )
        .setRange(
            GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(rowIdx)
                .setEndRowIndex(rowIdx + 1)
                .setStartColumnIndex(colIdx)
                .setEndColumnIndex(colIdx + 1)
        )
        .setFields("note, userEnteredFormat.backgroundColor")
)

@Suppress("FunctionName")
fun InvalidCellRequest(
    sheetId: Int,
    colIdx: Int,
    rowIdx: Int,
    comment: String
): Request = Request().setRepeatCell(
    RepeatCellRequest()
        .setCell(
            CellData()
                .setNote(comment)
                .setUserEnteredFormat(
                    CellFormat().setBackgroundColor(ErrorBackgroundColor)
                )
        )
        .setRange(
            GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(rowIdx)
                .setEndRowIndex(rowIdx + 1)
                .setStartColumnIndex(colIdx)
                .setEndColumnIndex(colIdx + 1)
        )
        .setFields("note, userEnteredFormat.backgroundColor")
)

private val ErrorBackgroundColor: Color = Color()
    .setRed(0.957f)
    .setGreen(0.78f)
    .setBlue(0.765f)
