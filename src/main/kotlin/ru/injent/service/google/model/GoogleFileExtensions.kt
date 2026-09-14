package ru.injent.service.google.model

import com.google.api.services.drive.model.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.injent.domain.FileStatus
import ru.injent.domain.SheetsFile
import kotlin.time.Clock
import kotlin.time.Instant

typealias GoogleFile = File

private const val KEY_STATUS = "status"
private const val KEY_UPLOAD_TIME = "uploadTime"
private const val KEY_CAN_FIX_WITH_AI = "canFixWithAi"
private const val KEY_CONFLICT_GROUPS = "conflictGroups"

val GoogleFile.status: FileStatus
    get() = runCatching { appProperties?.get(KEY_STATUS)?.let(FileStatus::valueOf) }.getOrNull() ?: FileStatus.EMPTY

val GoogleFile.canFixWithAi: Boolean
    get() = false

val GoogleFile.conflictGroups: List<String>
    get() = decodeConflictGroups(appProperties?.get(KEY_CONFLICT_GROUPS))

val GoogleFile.modifiedAtTime: Instant
    get() = modifiedTime?.value?.let(Instant::fromEpochMilliseconds) ?: Clock.System.now()

val GoogleFile.uploadTime: Instant
    get() = appProperties?.get(KEY_UPLOAD_TIME)
        ?.let(String::toLongOrNull)
        ?.let(Instant::fromEpochMilliseconds)
        ?: Clock.System.now()

fun GoogleFile.toSheetsFile(): SheetsFile = SheetsFile(
    fileId = id,
    name = name,
    modifiedTime = modifiedAtTime,
    uploadTime = uploadTime,
    status = status,
    canFixWithAi = canFixWithAi,
    conflictGroups = conflictGroups,
)

fun encodeConflictGroups(groups: List<String>): String =
    Json.encodeToString(groups)

fun decodeConflictGroups(value: String?): List<String> =
    value?.let { encoded ->
        runCatching { Json.decodeFromString<List<String>>(encoded) }
            .getOrDefault(emptyList())
    }.orEmpty()
