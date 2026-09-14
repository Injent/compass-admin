package ru.injent.service.google.model

import kotlinx.serialization.Serializable

/**
 * Индикатор прогресса проверки или загрузки файлов расписания.
 */
@Serializable
data class FileValidationProgress(
    val total: Int = 0,
    val completed: Int = 0
)
