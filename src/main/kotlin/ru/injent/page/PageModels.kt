package ru.injent.page

import freemarker.template.Configuration
import io.ktor.server.routing.*
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.get
import ru.injent.dto.FileStatus
import ru.injent.dto.SheetsFile
import ru.injent.service.google.FileValidationProgress
import java.io.StringWriter
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
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
    val hasChanges: Boolean = false,
)

@Serializable
data class ScheduleView(
    val files: List<FileView>,
    val hasUnreadyFiles: Boolean,
    val canOpenScheduleApproval: Boolean,
    val error: String? = null,
    val filter: String,
    val filesLoaded: Boolean = true,
    val googleWaitMessage: String? = null,
    val validationProgress: FileValidationProgress = FileValidationProgress(),
)

fun scheduleView(
    files: List<SheetsFile>,
    error: String? = null,
    filter: String = FILTER_ALL,
    filesLoaded: Boolean = true,
    googleWaitMessage: String? = null,
    validationProgress: FileValidationProgress = FileValidationProgress(),
): ScheduleView {
    val activeFiles = files.filter { it.status != FileStatus.EMPTY }
    val hasActiveFileErrors = activeFiles.any { file ->
        file.status == FileStatus.INVALID || file.status == FileStatus.PROCESSING
    }
    val hasDuplicateGroups = activeFiles.any { file -> file.conflictGroups.isNotEmpty() }

    return ScheduleView(
        files = files.filterByScheduleFilter(filter).map(SheetsFile::toView),
        hasUnreadyFiles = hasActiveFileErrors || hasDuplicateGroups,
        canOpenScheduleApproval = filesLoaded && !hasActiveFileErrors,
        error = error,
        filter = filter.normalizeScheduleFilter(),
        filesLoaded = filesLoaded,
        googleWaitMessage = googleWaitMessage,
        validationProgress = validationProgress,
    )
}

fun fileModel(file: SheetsFile): Map<String, Any> =
    mapOf("file" to file.toView())

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
        hasChanges = status != FileStatus.EMPTY && hasChanges,
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
