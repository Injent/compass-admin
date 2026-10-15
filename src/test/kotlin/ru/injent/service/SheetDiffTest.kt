package ru.injent.service

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.formula.ConditionalFormattingEvaluator
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import kotlin.test.*

class SheetDiffTest {
    @Test
    fun `xlsx and xls compare first sheets with styles and both change ranges`() {
        for (incoming in listOf(XSSFWorkbook(), HSSFWorkbook())) {
            incoming.use { new ->
                XSSFWorkbook().use { old ->
                    old.createSheet("Original").apply {
                        createRow(0).apply {
                            createCell(0).setCellValue("same")
                            createCell(1).setCellValue("before")
                        }
                        createRow(4).createCell(3).setCellValue("removed")
                    }
                    old.createSheet("Unused")
                    val first = new.createSheet("Uploaded")
                    val style = new.createCellStyle().apply {
                        wrapText = true
                        fillPattern = FillPatternType.SOLID_FOREGROUND
                        fillForegroundColor = IndexedColors.YELLOW.index
                        dataFormat = new.createDataFormat().getFormat("0.00")
                        setFont(new.createFont().apply { bold = true })
                    }
                    first.createRow(0).apply {
                        heightInPoints = 32f
                        createCell(0).setCellValue("same")
                        createCell(1).apply { setCellValue("after"); cellStyle = style }
                    }
                    first.createRow(6).createCell(5).setCellValue(12.5)
                    first.setColumnWidth(1, 5000)
                    first.addMergedRegion(CellRangeAddress(2, 2, 0, 2))
                    new.createSheet("Ignored").createRow(0).createCell(0).setCellValue("ignored")
                    val result = ByteArrayOutputStream()
                    writeSheetDiff(bytes(old).inputStream(), bytes(new).inputStream(), result)
                    XSSFWorkbook(result.toByteArray().inputStream()).use { workbook ->
                        assertEquals(listOf("Старое", "Новое"), workbook.map { it.sheetName })
                        val before = workbook.getSheetAt(0)
                        val after = workbook.getSheetAt(1)
                        assertEquals("before", before.getRow(0).getCell(1).stringCellValue)
                        assertEquals("after", after.getRow(0).getCell(1).stringCellValue)
                        assertEquals(12.5, after.getRow(6).getCell(5).numericCellValue)
                        assertEquals(32f, after.getRow(0).heightInPoints)
                        assertEquals(5000, after.getColumnWidth(1))
                        assertEquals("A3:C3", after.getMergedRegion(0).formatAsString())
                        assertEquals("0.00", after.getRow(0).getCell(1).cellStyle.dataFormatString)
                        assertEquals("FFFFFF00", (after.getRow(0).getCell(1).cellStyle as org.apache.poi.xssf.usermodel.XSSFCellStyle).fillForegroundXSSFColor.argbHex)
                        assertTrue(after.getRow(0).getCell(1).cellStyle.wrapText)
                        assertTrue(workbook.getFontAt(after.getRow(0).getCell(1).cellStyle.fontIndex).bold)
                        for ((sheet, other, color) in listOf(Triple(before, "Новое", "FFFF80AB"), Triple(after, "Старое", "FFCDDC39"))) {
                            val formatting = sheet.sheetConditionalFormatting.getConditionalFormattingAt(0)
                            assertEquals("A1:F7", formatting.formattingRanges.single().formatAsString())
                            assertEquals("A1<>'$other'!A1", formatting.getRule(0).formula1)
                            assertEquals(color, (formatting.getRule(0).patternFormatting.fillForegroundColorColor as org.apache.poi.xssf.usermodel.XSSFColor).argbHex)
                        }
                        val evaluator = ConditionalFormattingEvaluator(workbook, workbook.creationHelper.createFormulaEvaluator())
                        assertTrue(evaluator.getConditionalFormattingForCell(CellReference("Новое", 0, 0, false, false)).isEmpty())
                        assertEquals(1, evaluator.getConditionalFormattingForCell(CellReference("Новое", 0, 1, false, false)).size)
                        assertEquals(1, evaluator.getConditionalFormattingForCell(CellReference("Старое", 4, 3, false, false)).size)
                        assertEquals(1, evaluator.getConditionalFormattingForCell(CellReference("Новое", 6, 5, false, false)).size)
                    }
                }
            }
        }
    }

    @Test
    fun `cached formula results survive removal of supporting sheets`() {
        XSSFWorkbook().use { old ->
            old.createSheet("First").createRow(0).createCell(0).cellFormula = "Second!A1+1"
            old.createSheet("Second").createRow(0).createCell(0).setCellValue(5.0)
            old.creationHelper.createFormulaEvaluator().evaluateAll()
            XSSFWorkbook().use { new ->
                new.createSheet("First").createRow(0).createCell(0).cellFormula = "2+3"
                new.creationHelper.createFormulaEvaluator().evaluateAll()
                val result = ByteArrayOutputStream()
                writeSheetDiff(bytes(old).inputStream(), bytes(new).inputStream(), result)
                XSSFWorkbook(result.toByteArray().inputStream()).use { workbook ->
                    assertEquals(6.0, workbook.getSheetAt(0).getRow(0).getCell(0).numericCellValue)
                    assertEquals(5.0, workbook.getSheetAt(1).getRow(0).getCell(0).numericCellValue)
                }
            }
        }
    }

    @Test
    fun `empty workbooks are rejected`() {
        XSSFWorkbook().use { old ->
            XSSFWorkbook().use { new ->
                old.createSheet()
                assertFailsWith<IllegalArgumentException> {
                    writeSheetDiff(bytes(old).inputStream(), bytes(new).inputStream(), ByteArrayOutputStream())
                }
            }
        }
    }

    private fun bytes(workbook: Workbook): ByteArray = ByteArrayOutputStream().also { workbook.write(it) }.toByteArray()
}
