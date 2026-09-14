package ru.injent.web.route

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.util.logging.Logger
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.injent.domain.FileStatus
import ru.injent.domain.SheetsFile
import ru.injent.service.config.AppConfig
import ru.injent.service.config.CompassApiConfig
import ru.injent.service.google.GoogleWorkspaceService
import ru.injent.service.google.SheetValidator
import ru.injent.service.google.model.CellReplacement
import ru.injent.util.XlsContentType
import ru.injent.util.XlsxContentType
import ru.injent.util.ensureXlsxExtension
import ru.injent.util.hasSpreadsheetExtension
import ru.injent.util.multipartFileDisposition
import ru.injent.util.removeSpreadsheetExtension
import ru.injent.util.scheduleFileNameKey
import ru.injent.util.toLogText
import ru.injent.web.dto.CompassApiResponse
import ru.injent.web.dto.ScheduleApprovalState
import ru.injent.web.dto.ScheduleApprovalStatus
import ru.injent.web.dto.UploadResult
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val KEY_STATUS = "status"
private const val KEY_UPLOAD_TIME = "uploadTime"
private const val KEY_CAN_FIX_WITH_AI = "canFixWithAi"
private const val KEY_CONFLICT_GROUPS = "conflictGroups"
private const val REMOVE_GROUPS_RETRY_COUNT = 3
private const val REMOVE_GROUPS_MAX_ATTEMPTS = REMOVE_GROUPS_RETRY_COUNT + 1
private val REMOVE_GROUPS_RETRY_DELAY = 10.minutes
private const val SCHEDULE_SEND_MAX_ATTEMPTS = 3
private val SCHEDULE_SEND_RETRY_DELAY = 5.seconds

/**
 * Обработчик отправки согласованного расписания на внешний API Compass.
 */
suspend fun sendApprovedScheduleFiles(
    googleService: GoogleWorkspaceService,
    appConfig: AppConfig,
    httpClient: HttpClient,
    approvalState: MutableStateFlow<ScheduleApprovalState>,
    logger: Logger,
) {
    try {
        logger.info("Schedule submission started")
        require(googleService.filesLoaded.value) { "Расписание ещё загружается" }
        googleService.refreshScheduleGroups().getOrThrow()
        val files = googleService.files.value
            .filter { file -> file.status != FileStatus.EMPTY }

        val conflictGroups = files
            .flatMap(SheetsFile::conflictGroups)
            .distinct()
            .sorted()
        require(conflictGroups.isEmpty()) {
            "Конфликтующие группы: ${conflictGroups.joinToString(", ")}"
        }
        require(files.all { file -> file.status == FileStatus.VALID }) {
            "Все файлы должны быть проверены без ошибок"
        }
        val groupsToRemove = googleService.groupsToRemove()
        val changedFiles = files.filter(SheetsFile::hasChanges)

        approvalState.value = ScheduleApprovalState.running(10)

        var scheduleResponse: CompassApiResponse? = null
        if (changedFiles.isNotEmpty()) {
            scheduleResponse = sendScheduleWithRetry(
                googleService = googleService,
                appConfig = appConfig,
                httpClient = httpClient,
                files = changedFiles,
                logger = logger,
            )
        }

        var removedGroupsResponse: CompassApiResponse? = null
        if (groupsToRemove.isNotEmpty()) {
            approvalState.value = ScheduleApprovalState.running(80)
            removedGroupsResponse = sendRemovedGroupsWithRetry(
                appConfig = appConfig,
                httpClient = httpClient,
                normalizedGroupNames = groupsToRemove,
            )
            googleService.deleteSyncedGroups(groupsToRemove)
        }

        googleService.markScheduleApproved(changedFiles, files.mapTo(mutableSetOf(), SheetsFile::fileId))

        val removedGroupsText = groupsToRemove
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(", ")
            ?: "нет"
        logger.info(
            "Schedule submission completed: filesSent=${changedFiles.size}, " +
                "removedGroups=$removedGroupsText, " +
                "uploadNewSchedules=${scheduleResponse?.toLogText() ?: "not requested (no changed files)"}, " +
                "removeGroups=${removedGroupsResponse?.toLogText() ?: "not requested"}"
        )
        approvalState.value = ScheduleApprovalState(
            status = ScheduleApprovalStatus.SUCCESS,
            progress = 100,
            message = if (changedFiles.isEmpty() && groupsToRemove.isEmpty()) "Нет изменений для отправки" else "Расписание отправлено: файлов ${changedFiles.size}"
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logger.error("Schedule submission failed", error)
        approvalState.value = ScheduleApprovalState(
            status = ScheduleApprovalStatus.ERROR,
            progress = approvalState.value.progress,
            message = error.message ?: "Отправка расписания прервана"
        )
    }
}

private suspend fun sendScheduleWithRetry(
    googleService: GoogleWorkspaceService,
    appConfig: AppConfig,
    httpClient: HttpClient,
    files: List<SheetsFile>,
    logger: Logger,
): CompassApiResponse {
    var lastStatus: HttpStatusCode? = null
    var lastError: Throwable? = null
    var lastResponseBody: String? = null

    repeat(SCHEDULE_SEND_MAX_ATTEMPTS) { attempt ->
        try {
            logger.info("Schedule submission attempt ${attempt + 1} of $SCHEDULE_SEND_MAX_ATTEMPTS")
            val response = httpClient.post(appConfig.compassApiConfig.approveScheduleUrl()) {
                header(HttpHeaders.Authorization, "Bearer ${appConfig.compassApiConfig.apiKey}")
                timeout {
                    requestTimeoutMillis = 60_000
                    connectTimeoutMillis = 10_000
                }
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            files.forEach { file ->
                                val fileName = file.name.ensureXlsxExtension()
                                append(
                                    "files",
                                    InputProvider {
                                        googleService.exportAsXlsx(file.fileId).asInput()
                                    },
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, multipartFileDisposition("files", fileName))
                                        append(HttpHeaders.ContentType, XlsxContentType.toString())
                                    }
                                )
                            }
                        }
                    )
                )
            }
            val responseBody = response.bodyAsText()
            if (response.status.value in 200..299) {
                return CompassApiResponse(response.status, responseBody)
            }

            lastStatus = response.status
            lastResponseBody = responseBody
            logger.warn(
                "Schedule submission attempt ${attempt + 1} failed: " +
                    "status=${response.status.value}, response=${responseBody.toLogText()}"
            )
        } catch (error: ResponseException) {
            lastStatus = error.response.status
            lastError = error
            lastResponseBody = error.response.bodyAsText()
            logger.warn(
                "Schedule submission attempt ${attempt + 1} failed: " +
                    "status=${error.response.status.value}, response=${lastResponseBody.toLogText()}"
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastError = error
            logger.warn("Schedule submission attempt ${attempt + 1} failed: ${error.message}")
        }

        if (attempt < SCHEDULE_SEND_MAX_ATTEMPTS - 1) {
            delay(SCHEDULE_SEND_RETRY_DELAY)
        }
    }

    val reason = lastStatus
        ?.let { status -> "статус ${status.value}" }
        ?: lastError?.message
        ?: "неизвестная ошибка"
    val responseDetails = lastResponseBody
        ?.takeIf(String::isNotBlank)
        ?.let { body -> ", response=${body.toLogText()}" }
        .orEmpty()
    error("Не удалось отправить расписание после $SCHEDULE_SEND_MAX_ATTEMPTS попыток: $reason$responseDetails")
}

private suspend fun sendRemovedGroupsWithRetry(
    appConfig: AppConfig,
    httpClient: HttpClient,
    normalizedGroupNames: List<String>,
): CompassApiResponse {
    var lastStatus: HttpStatusCode? = null
    var lastError: Throwable? = null
    var lastResponseBody: String? = null

    repeat(REMOVE_GROUPS_MAX_ATTEMPTS) { attempt ->
        try {
            val response = httpClient.post(appConfig.compassApiConfig.removeGroupsUrl()) {
                header(HttpHeaders.Authorization, "Bearer ${appConfig.compassApiConfig.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(normalizedGroupNames)
            }
            val responseBody = response.bodyAsText()

            if (response.status == HttpStatusCode.OK) {
                return CompassApiResponse(response.status, responseBody)
            }
            lastStatus = response.status
            lastResponseBody = responseBody
        } catch (error: ResponseException) {
            lastStatus = error.response.status
            lastError = error
            lastResponseBody = error.response.bodyAsText()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            lastError = error
        }

        if (attempt < REMOVE_GROUPS_MAX_ATTEMPTS - 1) {
            delay(REMOVE_GROUPS_RETRY_DELAY)
        }
    }

    val reason = lastStatus
        ?.let { status -> "статус ${status.value}" }
        ?: lastError?.message
        ?: "неизвестная ошибка"
    val responseDetails = lastResponseBody
        ?.takeIf(String::isNotBlank)
        ?.let { body -> ", response=${body.toLogText()}" }
        .orEmpty()
    error("Не удалось удалить группы в Compass: $reason$responseDetails")
}

/**
 * Загружает новые файлы расписания в свободные слоты Drive.
 */
suspend fun uploadFilesToFreeSlots(
    googleService: GoogleWorkspaceService,
    multipart: MultiPartData,
    sheetValidators: Collection<SheetValidator>,
    applicationScope: CoroutineScope,
): UploadResult {
    val freeFiles = googleService.files.value
        .filter { file -> file.status == FileStatus.EMPTY }
        .toMutableList()
    val existingFilesByName = googleService.files.value
        .filter { file -> file.status != FileStatus.EMPTY }
        .associateBy { file -> file.name.scheduleFileNameKey() }
        .toMutableMap()

    var uploadedCount = 0
    var rejectedCount = 0

    multipart.forEachPart { part ->
        try {
            if (part !is PartData.FileItem) return@forEachPart

            val fileName = part.originalFileName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            if (!fileName.hasSpreadsheetExtension()) {
                rejectedCount++
                return@forEachPart
            }

            val storedFileName = fileName.removeSpreadsheetExtension()
            val target = existingFilesByName[storedFileName.scheduleFileNameKey()]
                ?: freeFiles.removeFirstOrNull()
            if (target == null) {
                rejectedCount++
                return@forEachPart
            }
            freeFiles.removeAll { file -> file.fileId == target.fileId }

            googleService.updateFileContent(target.fileId) {
                name = storedFileName
                inputStream = part.provider().toInputStream()
                contentType = if (fileName.endsWith(".xls", ignoreCase = true)) {
                    XlsContentType.toString()
                } else {
                    XlsxContentType.toString()
                }
                appProperties[KEY_STATUS] = FileStatus.PROCESSING.name
                appProperties[KEY_UPLOAD_TIME] = Clock.System.now().toEpochMilliseconds().toString()
                appProperties[KEY_CAN_FIX_WITH_AI] = false.toString()
                appProperties[KEY_CONFLICT_GROUPS] = "[]"
            }.getOrThrow()
            existingFilesByName[storedFileName.scheduleFileNameKey()] = target.copy(name = storedFileName)
            applicationScope.launch {
                googleService.test(target.fileId, sheetValidators)
            }
            uploadedCount++
        } finally {
            part.release()
        }
    }

    return when {
        uploadedCount == 0 && rejectedCount > 0 -> UploadResult(error = "Не удалось загрузить файлы: проверьте формат и свободные слоты")
        rejectedCount > 0 -> UploadResult(error = "Часть файлов не загружена: не хватило свободных слотов или формат не поддержан")
        else -> UploadResult()
    }
}

fun Parameters.toCellReplacements(): List<CellReplacement> {
    val rowIndexes = getAll("rowIdx").orEmpty()
    val colIndexes = getAll("colIdx").orEmpty()
    val values = getAll("value").orEmpty()
    val count = minOf(rowIndexes.size, colIndexes.size, values.size)

    return (0 until count).mapNotNull { index ->
        val rowIdx = rowIndexes[index].toIntOrNull() ?: return@mapNotNull null
        val colIdx = colIndexes[index].toIntOrNull() ?: return@mapNotNull null
        val value = values[index].trim().takeIf(String::isNotBlank) ?: return@mapNotNull null

        CellReplacement(
            rowIdx = rowIdx,
            colIdx = colIdx,
            value = value
        )
    }
}

private fun CompassApiResponse.toLogText(): String =
    "status=${status.value}, response=${body.toLogText()}"

private fun CompassApiConfig.approveScheduleUrl(): String =
    host.trimEnd('/') + "/uploadNewSchedules"

private fun CompassApiConfig.removeGroupsUrl(): String =
    host.trimEnd('/') + "/removeGroups"
