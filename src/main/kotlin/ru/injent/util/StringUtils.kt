package ru.injent.util

import ru.injent.domain.FileStatus

private val WHITESPACE_REGEX = Regex("\\s+")
private val PARENTHESES_SPACES_REGEX = Regex("\\s*([()])\\s*")
private val SPREADSHEET_EXTENSION_REGEX = Regex("\\.(xlsx|xls)$", RegexOption.IGNORE_CASE)

/**
 * Удаляет расширение электронной таблицы (.xlsx, .xls) из названия файла.
 */
fun String.withoutSpreadsheetExtension(): String =
    replace(SPREADSHEET_EXTENSION_REGEX, "")

/**
 * Алиас для [withoutSpreadsheetExtension].
 */
fun String.removeSpreadsheetExtension(): String =
    withoutSpreadsheetExtension()

/**
 * Проверяет, имеет ли файл расширение .xlsx или .xls.
 */
fun String.hasSpreadsheetExtension(): Boolean =
    endsWith(".xlsx", ignoreCase = true) || endsWith(".xls", ignoreCase = true)

/**
 * Гарантирует наличие расширения .xlsx у имени файла.
 */
fun String.ensureXlsxExtension(): String =
    if (hasSpreadsheetExtension()) this else "$this.xlsx"

/**
 * Добавляет префикс ошибки, если статус файла INVALID.
 */
fun String.withInvalidPrefix(status: FileStatus): String =
    if (status == FileStatus.INVALID) "ЕстьОшибки_$this" else this

/**
 * Приводит имя файла расписания к нижнему регистру для сравнения без расширения.
 */
fun String.scheduleFileNameKey(): String =
    withoutSpreadsheetExtension().trim().lowercase()

/**
 * Нормализует пробельные символы внутри строки.
 */
fun String.normalizedSpaces(): String =
    trim().replace(WHITESPACE_REGEX, " ")

/**
 * Нормализует имя группы расписания для сравнения.
 */
fun String.normalizedGroupName(): String =
    trim()
        .lowercase()
        .replace('ё', 'е')
        .replace(WHITESPACE_REGEX, " ")
        .replace(PARENTHESES_SPACES_REGEX, "\$1")

/**
 * Объединяет название заголовка и подзаголовка группы в одно имя.
 */
fun combineGroupName(headerName: String, subheaderName: String): String {
    val normalizedHeader = headerName.normalizedGroupName()
    val normalizedSubheader = subheaderName.normalizedGroupName()

    return when {
        normalizedSubheader.startsWith(normalizedHeader) -> subheaderName
        subheaderName.startsWith("(") || subheaderName.startsWith("[") -> headerName + subheaderName
        else -> "$headerName($subheaderName)"
    }
}

/**
 * Сокращает текст ответа сервера для логирования.
 */
fun String?.toLogText(maxLength: Int = 500): String =
    this?.trim()
        ?.replace(WHITESPACE_REGEX, " ")
        ?.take(maxLength)
        ?.ifEmpty { "<empty>" }
        ?: "<empty>"
