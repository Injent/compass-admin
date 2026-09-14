package ru.injent.service.google.model

/**
 * Замена текста в конкретной ячейке.
 */
data class CellReplacement(
    val rowIdx: Int,
    val colIdx: Int,
    val value: String,
)

/**
 * Предложение ИИ по исправлению названия предмета/преподавателя в ячейке.
 */
data class CellCorrectionSuggestion(
    val key: Int,
    val rowIdx: Int,
    val colIdx: Int,
    val oldValue: String,
    val newValue: String,
    val replacements: List<CellReplacement>,
)
