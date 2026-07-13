package ru.injent.service.validator

import ru.injent.service.google.Cell
import ru.injent.service.google.SheetValidator
import ru.injent.service.google.SheetValidatorScope

var shortTeacherNames: Set<String> = emptySet()

val LessonValidator = SheetValidator {
    lessonCells().forEach { cell ->
        cell.test {
            value.orEmpty()
                .lines()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach(::validateLessonLine)
        }
    }
}

private fun SheetValidatorScope.lessonCells(): List<Cell> =
    firstLessonRowIdx().let { firstLessonRowIdx ->
        rows.asSequence()
            .drop(firstLessonRowIdx)
            .flatMap(List<Cell>::asSequence)
            .filter { cell ->
                cell.colIdx >= LESSON_START_COL_IDX &&
                    cell.rowIdx >= firstLessonRowIdx &&
                    !cell.value.isNullOrBlank()
            }
            .toList()
    }

private fun SheetValidatorScope.firstLessonRowIdx(): Int {
    val headerCells = rows.getOrNull(HEADER_ROW_IDX)
        ?.filter { cell ->
            cell.rowIdx == HEADER_ROW_IDX &&
                cell.colIdx >= LESSON_START_COL_IDX &&
                !cell.value.isNullOrBlank()
        }
        .orEmpty()

    return headerCells.maxOfOrNull { headerCell ->
        val hasSubheaders = rows.getOrNull(SUBHEADER_ROW_IDX)
            ?.any { subheaderCell ->
                subheaderCell.rowIdx == SUBHEADER_ROW_IDX &&
                    subheaderCell.colIdx in headerCell.colIdx..headerCell.endColIdx &&
                    !subheaderCell.value.isNullOrBlank()
            }
            ?: false

        if (hasSubheaders) SUBHEADER_ROW_IDX + 1 else headerCell.endRowIdx + 1
    } ?: SUBHEADER_ROW_IDX
}

private fun validateLessonLine(line: String) {
    if (line.endsWith(".")) {
        error("Строка пары не должна заканчиваться точкой")
    }

    validateRoom(line)
    val roundBracketRanges = validateBracketsBalance(line)

    if (ROOM_SIGN in line && roundBracketRanges.isEmpty()) {
        error("Если указана аудитория через №, в строке должны быть круглые скобки с преподавателем")
    }

    roundBracketRanges.forEach { range ->
        validateTeacherName(line.substring(range.first + 1, range.last))
    }
}

private fun validateRoom(line: String) {
    val roomSignIndexes = line.indexesOf(ROOM_SIGN)
    if (line.hasDotWithWrongCase()) {
        error("Обозначение ДОТ должно быть написано в верхнем регистре")
    }
    if (roomSignIndexes.isNotEmpty() && DOT_REGEX.containsMatchIn(line)) {
        error("ДОТ используется вместо аудитории и не должен указываться вместе с №")
    }

    roomSignIndexes.forEach { signIdx ->
        if (signIdx > 0 && line[signIdx - 1] != ' ') {
            error("Перед символом № должен быть пробел")
        }

        val roomStartIdx = signIdx + 1
        if (roomStartIdx >= line.length) {
            error("После символа № должен сразу идти номер аудитории")
        }

        val roomNumber = ROOM_NUMBER_REGEX.find(line, roomStartIdx)
        if (roomNumber == null || roomNumber.range.first != roomStartIdx || roomNumber.value.none(Char::isDigit)) {
            error("После символа № должен сразу идти корректный номер аудитории")
        }
    }
}

private fun validateBracketsBalance(line: String): List<IntRange> {
    val stack = ArrayDeque<Pair<Char, Int>>()
    val roundBracketRanges = mutableListOf<IntRange>()

    line.forEachIndexed { idx, char ->
        when (char) {
            '(',
            '[' -> stack.addLast(char to idx)

            ')' -> {
                val opened = stack.removeLastOrNullCompat()
                    ?: error("Закрывающая круглая скобка не имеет открывающей")
                if (opened.first != '(') {
                    error("Круглые и квадратные скобки должны быть правильно сбалансированы")
                }
                roundBracketRanges += opened.second..idx
            }

            ']' -> {
                val opened = stack.removeLastOrNullCompat()
                    ?: error("Закрывающая квадратная скобка не имеет открывающей")
                if (opened.first != '[') {
                    error("Круглые и квадратные скобки должны быть правильно сбалансированы")
                }
            }
        }
    }

    if (stack.isNotEmpty()) {
        error("Открытая скобка должна быть закрыта")
    }

    return roundBracketRanges
}

private fun validateTeacherName(rawName: String) {
    val teacherName = rawName.normalizedSpaces()

    if (teacherName.isBlank()) {
        error("В круглых скобках должен быть указан преподаватель")
    }
    if (TEACHER_SEPARATOR_REGEX.containsMatchIn(teacherName)) {
        error("В одних круглых скобках может быть указан только один преподаватель")
    }

    val dotCount = teacherName.count { it == '.' }
    if (dotCount !in 1..2) {
        error("У преподавателя должно быть 1 или 2 точки в инициалах")
    }
    if (!TEACHER_NAME_REGEX.matches(teacherName)) {
        error("ФИО преподавателя должно быть в формате Фамилия И.О. или Фамилия И.")
    }

    if (shortTeacherNames.isNotEmpty() && shortTeacherNames.none { it.normalizedSpaces() == teacherName }) {
        error("Преподаватель '$teacherName' отсутствует в списке разрешенных коротких имен")
    }
}

private fun <T> ArrayDeque<T>.removeLastOrNullCompat(): T? =
    if (isEmpty()) null else removeLast()

private fun String.indexesOf(char: Char): List<Int> =
    mapIndexedNotNull { idx, current -> idx.takeIf { current == char } }

private fun String.normalizedSpaces(): String =
    trim().replace(WHITESPACE_REGEX, " ")

private fun String.hasDotWithWrongCase(): Boolean =
    WORD_REGEX.findAll(this)
        .map(MatchResult::value)
        .any { word -> word.equals(DOT_WORD, ignoreCase = true) && word != DOT_WORD }

private const val LESSON_START_COL_IDX = 3
private const val HEADER_ROW_IDX = 1
private const val SUBHEADER_ROW_IDX = 2
private const val ROOM_SIGN = '№'
private const val DOT_WORD = "ДОТ"

private val ROOM_NUMBER_REGEX = Regex("[\\p{L}\\d-]+")
private val DOT_REGEX = Regex("\\bДОТ\\b")
private val TEACHER_SEPARATOR_REGEX = Regex("[,;:]")
private val TEACHER_NAME_REGEX = Regex("[\\p{Lu}][\\p{L}'-]+\\s+[\\p{Lu}]\\.(?:[\\p{Lu}]\\.)?")
private val WHITESPACE_REGEX = Regex("\\s+")
private val WORD_REGEX = Regex("\\p{L}+")
