package ru.injent.domain

/**
 * Доменная модель преподавателя.
 */
data class Teacher(
    val id: Int,
    val lastName: String,
    val firstName: String,
    val middleName: String,
    val departments: String,
) {
    /**
     * Полное имя преподавателя (Фамилия Имя Отчество).
     */
    val fullName: String
        get() = listOf(lastName, firstName, middleName)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { "Новый преподаватель" }

    /**
     * Сокращенное имя преподавателя в формате "Фамилия И.О.".
     */
    val shortName: String
        get() {
            if (lastName.isBlank() || firstName.isBlank()) return ""

            val firstInitial = firstName.firstOrNull()?.uppercaseChar()?.let { "$it." }.orEmpty()
            val middleInitial = middleName.firstOrNull()?.uppercaseChar()?.let { "$it." }.orEmpty()
            return "$lastName $firstInitial$middleInitial"
        }
}
