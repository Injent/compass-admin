package ru.injent.service.google

import com.google.api.services.sheets.v4.model.Border
import com.google.api.services.sheets.v4.model.Borders
import com.google.api.services.sheets.v4.model.CellData
import com.google.api.services.sheets.v4.model.CellFormat
import com.google.api.services.sheets.v4.model.ExtendedValue
import com.google.api.services.sheets.v4.model.GridData
import com.google.api.services.sheets.v4.model.RowData
import com.google.api.services.sheets.v4.model.Sheet
import ru.injent.service.validator.lessonCells
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleTableBoundsTest {
    @Test
    fun `schedule bounds follow bordered cells instead of fixed rows`() {
        val scope = SheetValidatorScope(
            sheet(
                row(cell("Служебная строка")),
                row(cell("Расписание 2024", bordered = true), cell(), cell(), cell()),
                row(cell(bordered = true), cell("1 корпус", true), cell("2 корпус", true), cell("ПРИ-101", true)),
                row(cell(bordered = true), cell(bordered = true), cell(bordered = true), cell(bordered = true)),
                row(cell(bordered = true), cell(bordered = true), cell(bordered = true), cell("Математика", true)),
                row(cell(bordered = true)),
                row(cell(), cell(), cell(), cell("Примечание после таблицы")),
            )
        )

        assertEquals(1, scope.firstScheduleRowIdx)
        assertEquals(2, scope.headerRowIdx)
        assertEquals(3, scope.subheaderRowIdx)
        assertEquals(5, scope.lastScheduleRowIdx)
        assertEquals(listOf("ПРИ-101"), scope.scheduleGroupNames())
        assertEquals(listOf("Математика"), scope.lessonCells().map(Cell::value))
    }

    private fun sheet(vararg rows: RowData): Sheet =
        Sheet().setData(listOf(GridData().setRowData(rows.toList())))

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
