package ru.injent.service.google

import com.google.api.services.sheets.v4.model.Spreadsheet
import java.security.MessageDigest

// Compare schedule data, excluding validation notes, highlighting and Drive metadata.
internal fun Spreadsheet.scheduleFingerprint(fileName: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: Any?) {
        if (value == null) {
            digest.update("-1:".toByteArray())
            return
        }
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        digest.update("${bytes.size}:".toByteArray())
        digest.update(bytes)
    }
    add(fileName)
    sheets.orEmpty().forEach { sheet ->
        add("sheet")
        add(sheet.properties?.title)
        sheet.merges.orEmpty().forEach { merge ->
            add("merge")
            add(listOf(merge.startRowIndex, merge.endRowIndex, merge.startColumnIndex, merge.endColumnIndex))
        }
        sheet.data.orEmpty().forEach { grid ->
            grid.rowData.orEmpty().forEachIndexed { rowIndex, row ->
                row.getValues().orEmpty().forEachIndexed { columnIndex, cell ->
                    val entered = cell.userEnteredValue
                    val effective = cell.effectiveValue
                    if (entered != null || effective != null) {
                        add("cell")
                        add((grid.startRow ?: 0) + rowIndex)
                        add((grid.startColumn ?: 0) + columnIndex)
                        for (value in listOf(entered, effective)) {
                            add(value?.stringValue)
                            add(value?.numberValue)
                            add(value?.boolValue)
                            add(value?.formulaValue)
                            add(value?.errorValue?.type)
                        }
                    }
                }
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
