package ru.injent.database

data class Teacher(
    val id: Int,
    val lastName: String,
    val firstName: String,
    val middleName: String,
    val departments: String,
) {
    val fullName: String
        get() = listOf(lastName, firstName, middleName)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .ifBlank { "Новый преподаватель" }

    val shortName: String
        get() {
            if (lastName.isBlank() || firstName.isBlank()) return ""

            val firstInitial = firstName.firstOrNull()?.uppercaseChar()?.let { "$it." }.orEmpty()
            val middleInitial = middleName.firstOrNull()?.uppercaseChar()?.let { "$it." }.orEmpty()
            return "$lastName $firstInitial$middleInitial"
        }
}
