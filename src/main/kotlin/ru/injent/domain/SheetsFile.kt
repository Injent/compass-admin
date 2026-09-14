package ru.injent.domain

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Доменное представление файла таблиц расписания.
 */
@Serializable
data class SheetsFile(
    val fileId: String,
    val name: String,
    val modifiedTime: Instant,
    val uploadTime: Instant,
    val status: FileStatus,
    val canFixWithAi: Boolean,
    val contentFingerprint: String? = null,
    val hasChanges: Boolean = true,
    val conflictGroups: List<String> = emptyList(),
)
