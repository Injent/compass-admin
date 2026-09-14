package ru.injent.web.dto

import kotlinx.serialization.Serializable

/**
 * Общая модель передачи ошибки в API ответах.
 */
@Serializable
data class ApiError(val error: String)
