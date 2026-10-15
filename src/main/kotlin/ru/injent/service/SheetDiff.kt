package ru.injent.service

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream
import java.io.OutputStream

/** Сравнивает значения первых листов, используя сохранённые результаты формул. */
fun writeSheetDiff(oldFile: InputStream, newFile: InputStream, output: OutputStream) {
    XSSFWorkbook(oldFile).use { workbook ->
        WorkbookFactory.create(newFile).use { incoming ->
            require(workbook.numberOfSheets > 0 && incoming.numberOfSheets > 0) { "В файле нет листов" }
            val old = workbook.getSheetAt(0)
            // Сохраняем значения до удаления остальных листов, на которые могли ссылаться формулы.
            old.forEach { row -> row.filter { it.cellType == CellType.FORMULA }.forEach { cell ->
                val value = cell.cachedFormulaResultType
                when (value) {
                    CellType.STRING -> { val v = cell.stringCellValue; cell.removeFormula(); cell.setCellValue(v) }
                    CellType.NUMERIC -> { val v = cell.numericCellValue; cell.removeFormula(); cell.setCellValue(v) }
                    CellType.BOOLEAN -> { val v = cell.booleanCellValue; cell.removeFormula(); cell.setCellValue(v) }
                    CellType.ERROR -> { val v = cell.errorCellValue; cell.removeFormula(); cell.setCellErrorValue(v) }
                    else -> cell.setBlank()
                }
            } }
            while (workbook.numberOfSheets > 1) workbook.removeSheetAt(1)
            workbook.setSheetName(0, "Старое")
            workbook.setSheetVisibility(0, SheetVisibility.VISIBLE)
            val fresh = workbook.createSheet("Новое")
            copySheet(incoming.getSheetAt(0), fresh)
            val lastRow = maxOf(old.lastRowNum, fresh.lastRowNum, 0)
            val lastColumn = maxOf(
                old.maxOfOrNull { it.lastCellNum.toInt() } ?: 0,
                fresh.maxOfOrNull { it.lastCellNum.toInt() } ?: 0, 1
            ) - 1
            val range = CellRangeAddress(0, lastRow, 0, lastColumn)
            highlight(old, "A1<>'Новое'!A1", range, byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0xAB.toByte()))
            highlight(fresh, "A1<>'Старое'!A1", range, byteArrayOf(0xCD.toByte(), 0xDC.toByte(), 0x39))
            workbook.setActiveSheet(1)
            workbook.write(output)
        }
    }
}

private fun copySheet(source: Sheet, target: Sheet) {
    val workbook = target.workbook as XSSFWorkbook
    val styles = mutableMapOf<Int, CellStyle>()
    val fonts = mutableMapOf<Int, Font>()
    fun legacyColor(index: Short): XSSFColor = (source.workbook as HSSFWorkbook)
        .customPalette.getColor(index)?.triplet
        ?.let { channels -> XSSFColor(ByteArray(3) { channels[it].toByte() }) }
        ?: XSSFColor(IndexedColors.AUTOMATIC, null)
    fun style(original: CellStyle): CellStyle = styles.getOrPut(original.index.toInt()) {
        workbook.createCellStyle().apply {
            if (original is XSSFCellStyle) cloneStyleFrom(original)
            else {
                alignment = original.alignment
                verticalAlignment = original.verticalAlignment
                wrapText = original.wrapText
                rotation = original.rotation
                indention = original.indention
                borderTop = original.borderTop
                borderBottom = original.borderBottom
                borderLeft = original.borderLeft
                borderRight = original.borderRight
                fillPattern = original.fillPattern
                setFillForegroundColor(legacyColor(original.fillForegroundColor))
                setFillBackgroundColor(legacyColor(original.fillBackgroundColor))
                setTopBorderColor(legacyColor(original.topBorderColor))
                setBottomBorderColor(legacyColor(original.bottomBorderColor))
                setLeftBorderColor(legacyColor(original.leftBorderColor))
                setRightBorderColor(legacyColor(original.rightBorderColor))
                setFont(fonts.getOrPut(original.fontIndex) {
                    val font = source.workbook.getFontAt(original.fontIndex)
                    workbook.createFont().apply {
                        fontName = font.fontName
                        fontHeight = font.fontHeight
                        bold = font.bold
                        italic = font.italic
                        strikeout = font.strikeout
                        underline = font.underline
                        setColor(legacyColor(font.color))
                        typeOffset = font.typeOffset
                        charSet = font.charSet
                    }
                })
            }
            dataFormat = workbook.createDataFormat().getFormat(original.dataFormatString)
        }
    }
    target.defaultRowHeight = source.defaultRowHeight
    target.defaultColumnWidth = source.defaultColumnWidth
    val columnCount = source.maxOfOrNull { it.lastCellNum.toInt() } ?: 0
    for (column in 0 until columnCount) {
        target.setColumnWidth(column, source.getColumnWidth(column))
        target.setColumnHidden(column, source.isColumnHidden(column))
    }
    source.forEach { row ->
        val copy = target.createRow(row.rowNum)
        copy.height = row.height
        copy.zeroHeight = row.zeroHeight
        row.rowStyle?.let { copy.rowStyle = style(it) }
        row.forEach { cell ->
            val dest = copy.createCell(cell.columnIndex)
            dest.cellStyle = style(cell.cellStyle)
            when (if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType) {
                CellType.STRING -> dest.setCellValue(cell.stringCellValue)
                CellType.NUMERIC -> dest.setCellValue(cell.numericCellValue)
                CellType.BOOLEAN -> dest.setCellValue(cell.booleanCellValue)
                CellType.ERROR -> dest.setCellErrorValue(cell.errorCellValue)
                else -> dest.setBlank()
            }
        }
    }
    source.mergedRegions.forEach { target.addMergedRegion(it.copy()) }
}

private fun highlight(sheet: Sheet, formula: String, range: CellRangeAddress, rgb: ByteArray) {
    val formatting = sheet.sheetConditionalFormatting
    while (formatting.numConditionalFormattings > 0) formatting.removeConditionalFormatting(0)
    val rule = formatting.createConditionalFormattingRule(formula)
    rule.createPatternFormatting().apply {
        fillPattern = PatternFormatting.SOLID_FOREGROUND
        setFillForegroundColor(XSSFColor(rgb))
        setFillBackgroundColor(XSSFColor(rgb))
    }
    formatting.addConditionalFormatting(arrayOf(range), rule)
}
