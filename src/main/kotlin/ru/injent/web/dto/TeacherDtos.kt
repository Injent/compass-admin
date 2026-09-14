package ru.injent.web.dto

import kotlinx.serialization.Serializable
import ru.injent.domain.Teacher

@Serializable
data class TeacherView(
    val id: Int,
    val lastName: String,
    val firstName: String,
    val middleName: String,
    val departments: String,
    val fullName: String,
)

@Serializable
data class TeacherInput(
    val lastName: String,
    val firstName: String,
    val middleName: String = "",
    val departments: String = "",
) {
    fun isValid(): Boolean = lastName.isNotBlank() && firstName.isNotBlank()
}

@Serializable
data class DeleteTeachersRequest(val ids: List<Int>)

fun Teacher.toView(): TeacherView =
    TeacherView(id, lastName, firstName, middleName, departments, fullName)
