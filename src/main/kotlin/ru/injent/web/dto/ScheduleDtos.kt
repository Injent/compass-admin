package ru.injent.web.dto

import kotlinx.serialization.Serializable
import ru.injent.domain.FileStatus
import ru.injent.domain.SheetsFile
import ru.injent.service.google.model.FileValidationProgress
import ru.injent.util.withoutSpreadsheetExtension

const val FILTER_ALL = "all"
const val FILTER_VALID = "valid"
const val FILTER_INVALID = "invalid"
const val FILTER_DELETED = "deleted"

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

private fun SheetsFile.toView(): FileView =
    FileView(
        fileId = fileId,
        name = name.withoutSpreadsheetExtension(),
        status = displayStatus().name,
        statusText = displayStatus().toText(),
        modifiedTime = modifiedTime.toString(),
        createdTime = uploadTime.toString(),
        icon = displayStatus().toIcon(),
        canFixWithAi = canFixWithAi,
        hasChanges = status != FileStatus.EMPTY && hasChanges,
        supportingText = conflictGroups
            .takeIf { groups -> status != FileStatus.EMPTY && groups.isNotEmpty() }
            ?.let { groups -> "расписание с группами: ${groups.joinToString(", ")} уже существует" },
    )

private fun List<SheetsFile>.filterByScheduleFilter(filter: String): List<SheetsFile> =
    when (filter.normalizeScheduleFilter()) {
        FILTER_VALID -> filter { file -> file.displayStatus() == FileStatus.VALID }
        FILTER_INVALID -> filter { file -> file.displayStatus() == FileStatus.INVALID }
        FILTER_DELETED -> filter { file -> file.status == FileStatus.EMPTY }
        else -> filter { file -> file.status != FileStatus.EMPTY }
    }

private fun SheetsFile.displayStatus(): FileStatus =
    if (status != FileStatus.EMPTY && conflictGroups.isNotEmpty()) FileStatus.INVALID else status

fun String.normalizeScheduleFilter(): String =
    when (lowercase()) {
        FILTER_VALID -> FILTER_VALID
        FILTER_INVALID -> FILTER_INVALID
        FILTER_DELETED -> FILTER_DELETED
        else -> FILTER_ALL
    }

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

@Serializable
data class DeleteScheduleRequest(val ids: List<String>)

@Serializable
data class ScheduleApprovalPreview(
    val groupsToRemove: List<String>,
    val duplicateGroups: List<String>,
)

@Serializable
data class ScheduleApprovalState(
    val status: ScheduleApprovalStatus,
    val progress: Int,
    val message: String? = null,
) {
    companion object {
        fun idle(): ScheduleApprovalState =
            ScheduleApprovalState(ScheduleApprovalStatus.IDLE, 0)

        fun running(progress: Int): ScheduleApprovalState =
            ScheduleApprovalState(ScheduleApprovalStatus.RUNNING, progress, "Файлы отправляются")
    }
}

@Serializable
enum class ScheduleApprovalStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    ERROR,
}

data class UploadResult(
    val error: String? = null
)

data class CompassApiResponse(
    val status: io.ktor.http.HttpStatusCode,
    val body: String,
)
