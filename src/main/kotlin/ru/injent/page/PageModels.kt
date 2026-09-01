package ru.injent.page

import freemarker.template.Configuration
import io.ktor.server.routing.*
import kotlinx.datetime.*
import org.koin.ktor.ext.get
import ru.injent.dto.FileStatus
import ru.injent.dto.SheetsFile
import ru.injent.service.google.CellCorrectionSuggestion
import java.io.StringWriter
import kotlin.time.Clock
import kotlin.time.Instant

data class FileView(
    val fileId: String,
    val name: String,
    val status: String,
    val statusText: String,
    val modifiedTime: String,
    val createdTime: String,
    val icon: String,
    val canFixWithAi: Boolean,
    val supportingText: String?,
)

fun scheduleModel(
    files: List<SheetsFile>,
    error: String? = null,
    filter: String = FILTER_ALL,
    groupsToRemove: List<String> = emptyList(),
    filesLoaded: Boolean = true,
): Map<String, Any?> {
    val activeFiles = files.filter { it.status != FileStatus.EMPTY }
    val hasActiveErrors = activeFiles.any { file ->
        file.displayStatus() == FileStatus.INVALID ||
            file.displayStatus() == FileStatus.PROCESSING ||
            file.conflictGroups.isNotEmpty()
    }
    val allActiveFilesValid = filesLoaded && !hasActiveErrors

    return mapOf(
        "files" to files.filterByScheduleFilter(filter).map(SheetsFile::toView),
        "hasUnreadyFiles" to hasActiveErrors,
        "allActiveFilesValid" to allActiveFilesValid,
        "groupsToRemove" to groupsToRemove,
        "error" to error,
        "filter" to filter.normalizeScheduleFilter(),
    )
}

fun fileModel(file: SheetsFile): Map<String, Any> =
    mapOf("file" to file.toView())

fun correctionPaneModel(fileId: String): Map<String, Any?> =
    mapOf("fileId" to fileId)

fun correctionResultsModel(
    fileId: String,
    suggestions: List<CellCorrectionSuggestion>,
    error: String? = null,
): Map<String, Any?> =
    mapOf(
        "fileId" to fileId,
        "suggestions" to suggestions,
        "error" to error
    )

fun correctionApplyResultModel(
    appliedCount: Int,
    error: String? = null,
): Map<String, Any?> =
    mapOf(
        "appliedCount" to appliedCount,
        "error" to error
    )

context(routing: Routing)
fun renderTemplate(templateName: String, model: Map<String, Any?>): String {
    val writer = StringWriter()
    routing.get<Configuration>().getTemplate(templateName).process(model, writer)
    return writer.toString()
}

private fun SheetsFile.toView(): FileView =
    FileView(
        fileId = fileId,
        name = name.withoutSpreadsheetExtension(),
        status = displayStatus().name,
        statusText = displayStatus().toText(),
        modifiedTime = modifiedTime.formatScheduleDate(),
        createdTime = uploadTime.formatScheduleDate(),
        icon = displayStatus().toIcon(),
        canFixWithAi = canFixWithAi,
        supportingText = conflictGroups
            .takeIf { groups -> status != FileStatus.EMPTY && groups.isNotEmpty() }
            ?.let { groups -> "расписание с группами: ${groups.joinToString(", ")} уже существует" },
    )

private fun String.withoutSpreadsheetExtension(): String =
    replace(Regex("\\.(xlsx|xls)$", RegexOption.IGNORE_CASE), "")

private fun List<SheetsFile>.filterByScheduleFilter(filter: String): List<SheetsFile> =
    when (filter.normalizeScheduleFilter()) {
        FILTER_VALID -> filter { file -> file.displayStatus() == FileStatus.VALID }
        FILTER_INVALID -> filter { file -> file.displayStatus() == FileStatus.INVALID }
        FILTER_DELETED -> filter { file -> file.status == FileStatus.EMPTY }
        else -> filter { file -> file.status != FileStatus.EMPTY }
    }

private fun SheetsFile.displayStatus(): FileStatus =
    if (status != FileStatus.EMPTY && conflictGroups.isNotEmpty()) FileStatus.INVALID else status

private fun String.normalizeScheduleFilter(): String =
    when (lowercase()) {
        FILTER_VALID -> FILTER_VALID
        FILTER_INVALID -> FILTER_INVALID
        FILTER_DELETED -> FILTER_DELETED
        else -> FILTER_ALL
    }

private const val FILTER_ALL = "all"
private const val FILTER_VALID = "valid"
private const val FILTER_INVALID = "invalid"
private const val FILTER_DELETED = "deleted"

private fun FileStatus.toText(): String =
    when (this) {
        FileStatus.EMPTY -> "Свободный слот"
        FileStatus.PROCESSING -> "Файл обрабатывается"
        FileStatus.VALID -> "Файл проверен"
        FileStatus.INVALID -> "Найдены ошибки"
    }

private fun FileStatus.toIcon(): String =
    when (this) {
        FileStatus.EMPTY -> "inventory_2"
        FileStatus.PROCESSING -> "sync"
        FileStatus.VALID -> "check"
        FileStatus.INVALID -> "priority_high"
    }

private fun Instant.formatScheduleDate(): String {
    val timeZone = TimeZone.currentSystemDefault()
    val now = Clock.System.now().toLocalDateTime(timeZone)
    val localDateTime = toLocalDateTime(timeZone)
    val date = localDateTime.date
    val time = "${localDateTime.hour.twoDigits()}:${localDateTime.minute.twoDigits()}"

    return when {
        date == now.date -> time
        date == now.date.minus(1, DateTimeUnit.DAY) -> "вчера, $time"
        date.year == now.year -> "${date.day} ${date.month.number.monthAbbr()} $time"
        else -> "${date.day} ${date.month.number.monthAbbr()} ${date.year} г."
    }
}

private fun Int.twoDigits(): String =
    toString().padStart(2, '0')

private fun Int.monthAbbr(): String =
    when (this) {
        1 -> "янв."
        2 -> "фев."
        3 -> "мар."
        4 -> "апр."
        5 -> "мая"
        6 -> "июн."
        7 -> "июл."
        8 -> "авг."
        9 -> "сент."
        10 -> "окт."
        11 -> "нояб."
        12 -> "дек."
        else -> ""
    }
