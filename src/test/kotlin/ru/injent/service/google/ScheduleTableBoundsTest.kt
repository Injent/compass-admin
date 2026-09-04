package ru.injent.service.google

import com.google.api.services.sheets.v4.model.Border
import com.google.api.services.sheets.v4.model.Borders
import com.google.api.services.sheets.v4.model.CellData
import com.google.api.services.sheets.v4.model.CellFormat
import com.google.api.services.sheets.v4.model.ExtendedValue
import com.google.api.services.sheets.v4.model.GridData
import com.google.api.services.sheets.v4.model.GridRange
import com.google.api.services.sheets.v4.model.RowData
import com.google.api.services.sheets.v4.model.Sheet
import ru.injent.service.validator.LegendValidator
import ru.injent.service.validator.lessonCells
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScheduleTableBoundsTest {
    @Test
    fun `schedule bounds follow bordered cells instead of fixed rows`() {
        val scope = SheetValidatorScope(
            sheet(
                row(cell("Служебная строка")),
                row(cell("Расписание 2024", bordered = true), cell(), cell(), cell()),
                row(cell(bordered = true), cell("1 корпус", true), cell("2 корпус", true), cell("ПРИ-101", true)),
                row(cell(bordered = true), cell(bordered = true), cell(bordered = true), cell(bordered = true)),
                row(cell("понедельник", true), cell(bordered = true), cell(bordered = true), cell("Математика", true)),
                row(cell(bordered = true)),
                row(cell(), cell(), cell(), cell("Примечание после таблицы")),
            )
        )

        assertEquals(1, scope.firstScheduleRowIdx)
        assertEquals(2, scope.headerRowIdx)
        assertEquals(4, scope.firstDayRowIdx)
        assertEquals(3, scope.subheaderRowIdx)
        assertEquals(5, scope.lastScheduleRowIdx)
        assertEquals(listOf("ПРИ-101"), scope.scheduleGroupNames())
        assertEquals(listOf("Математика"), scope.lessonCells().map(Cell::value))
    }

    @Test
    fun `horizontal weekdays and text below schedule are ignored by time validation`() {
        val scope = SheetValidatorScope(
            sheet(
                rows = listOf(
                    row(cell("Служебная строка")),
                    row(cell("Расписание", true), cell(), cell(), cell()),
                    row(cell(bordered = true), cell("1 корпус", true), cell("2 корпус", true), cell("ИСТМ-101", true)),
                    row(cell(bordered = true), cell(bordered = true), cell(bordered = true), cell(bordered = true)),
                    row(cell("понедельник", true), cell(bordered = true), cell(bordered = true), cell("Самостоятельная работа", true)),
                    row(cell("вторник", true), cell(bordered = true), cell(bordered = true), cell("Самостоятельная работа", true)),
                    row(cell("среда", true), cell(bordered = true), cell(bordered = true), cell("Самостоятельная работа", true)),
                    row(cell("четверг", true), cell(bordered = true), cell(bordered = true), cell("Самостоятельная работа", true)),
                    row(cell("пятница", true), cell("11.50-13.25", true), cell("12.20-13.55", true), cell("Пара", true)),
                    row(cell(bordered = true), cell("14.00-15.35", true), cell("14.30-16.05", true), cell("Пара", true)),
                    row(cell(), cell("Надпись после таблицы"), cell(), cell()),
                    row(cell("Следующая надпись"), cell(), cell(), cell()),
                ),
                merges = listOf(
                    merge(2, 3, 1, 1),
                    merge(2, 3, 2, 2),
                    merge(2, 3, 3, 3),
                    merge(4, 4, 0, 2),
                    merge(5, 5, 0, 2),
                    merge(6, 6, 0, 2),
                    merge(7, 7, 0, 2),
                    merge(8, 9, 0, 0),
                ),
            )
        )

        with(LegendValidator()) { scope.validate() }
        val comments = scope.getAccumulatedErrors().map(CellError::comment)

        assertFalse("В границах дня недели должны быть ячейки времени" in comments)
        assertFalse("Между ячейками времени не должно быть пустых ячеек" in comments)
        assertFalse("После последнего дня недели не должно быть данных вне его объединенной ячейки" in comments)
    }

    @Test
    fun `wide primary group is valid when first day follows header`() {
        val scope = SheetValidatorScope(
            sheet(
                rows = listOf(
                    row(cell("Расписание", true), cell(), cell(), cell(), cell()),
                    row(cell(bordered = true), cell("1 корпус", true), cell("2 корпус", true), cell("ИСТМ-101", true), cell(bordered = true)),
                    row(cell("понедельник", true), cell(bordered = true), cell(bordered = true), cell(bordered = true), cell(bordered = true)),
                ),
                merges = listOf(
                    merge(1, 1, 3, 4),
                    merge(2, 2, 0, 2),
                ),
            )
        )

        with(LegendValidator()) { scope.validate() }

        assertEquals(2, scope.firstDayRowIdx)
        assertEquals(null, scope.subheaderRowIdx)
        assertEquals(listOf("ИСТМ-101"), scope.scheduleGroupNames())
        assertFalse(
            scope.getAccumulatedErrors().any {
                it.comment == "Заголовок без подзаголовков не должен объединяться по столбцам"
            }
        )
    }

    private fun sheet(vararg rows: RowData): Sheet =
        Sheet().setData(listOf(GridData().setRowData(rows.toList())))

    private fun sheet(rows: List<RowData>, merges: List<GridRange>): Sheet =
        Sheet()
            .setData(listOf(GridData().setRowData(rows)))
            .setMerges(merges)

    private fun merge(startRow: Int, endRow: Int, startCol: Int, endCol: Int): GridRange = GridRange()
        .setStartRowIndex(startRow)
        .setEndRowIndex(endRow + 1)
        .setStartColumnIndex(startCol)
        .setEndColumnIndex(endCol + 1)

    private fun row(vararg cells: CellData): RowData = RowData().setValues(cells.toList())

    private fun cell(value: String? = null, bordered: Boolean = false): CellData = CellData().apply {
        if (value != null) {
            userEnteredValue = ExtendedValue().apply { stringValue = value }
        }
        if (bordered) {
            userEnteredFormat = CellFormat().apply {
                borders = Borders().apply {
                    bottom = Border().apply { style = "SOLID" }
                }
            }
        }
    }
}
