package ru.injent.service.validator

import ru.injent.service.google.SheetValidator
import ru.injent.service.google.SheetValidatorScope
import ru.injent.service.teacher.TeacherService

class TeacherValidator(
    private val teacherService: TeacherService,
) : SheetValidator {
    override fun SheetValidatorScope.validate() {
        val shortTeacherNames = teacherService.getAll()
            .map { teacher -> teacher.shortName.normalizedSpaces() }
            .filter(String::isNotBlank)
            .toSet()

        lessonCells().forEach { cell ->
            cell.test {
                value.orEmpty()
                    .lines()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .flatMap(String::lessonTeacherNames)
                    .filter(String::isLessonTeacherNameFormatValid)
                    .forEach { teacherName ->
                        if (teacherName !in shortTeacherNames) {
                            error("Этого преподавателя нет в базе данных. Если вы уверены что он есть, то добавьте информацию о нем во вкладке Преподаватели")
                        }
                    }
            }
        }
    }
}
