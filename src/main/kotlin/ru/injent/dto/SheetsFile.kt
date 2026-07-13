package ru.injent.dto

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class SheetsFile(
    val fileId: String,
    val name: String,
    val modifiedTime: Instant,
    val uploadTime: Instant,
    val status: FileStatus,
)
