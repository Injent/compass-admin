package ru.injent.page

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import ru.injent.database.Teacher
import ru.injent.service.teacher.TeacherService

fun Routing.teachersPage(teacherService: TeacherService) {
    get("/api/teachers") {
        call.respond(teacherService.getAll().map(Teacher::toView))
    }

    post("/api/teachers") {
        val input = call.receive<TeacherInput>()
        if (!input.isValid()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("Фамилия и имя обязательны"))
            return@post
        }

        val id = teacherService.create(input.lastName, input.firstName, input.middleName, input.departments)
        call.respond(HttpStatusCode.Created, teacherService.getAll().first { it.id == id }.toView())
    }

    put("/api/teachers/{id}") {
        val teacherId = call.parameters["id"]?.toIntOrNull()
        val input = call.receive<TeacherInput>()
        if (teacherId == null || !input.isValid()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("Некорректные данные преподавателя"))
            return@put
        }

        teacherService.update(teacherId, input.lastName, input.firstName, input.middleName, input.departments)
        call.respond(teacherService.getAll().first { it.id == teacherId }.toView())
    }

    delete("/api/teachers/{id}") {
        val teacherId = call.parameters["id"]?.toIntOrNull()
        if (teacherId == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("Некорректный идентификатор"))
            return@delete
        }
        teacherService.delete(teacherId)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/api/teachers/delete") {
        teacherService.delete(call.receive<DeleteTeachersRequest>().ids.distinct())
        call.respond(HttpStatusCode.NoContent)
    }

    get("/teachers") {
        call.respond(FreeMarkerContent("index.html", indexModel(call)))
    }
}

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

@Serializable
data class ApiError(val error: String)

private fun Teacher.toView(): TeacherView =
    TeacherView(id, lastName, firstName, middleName, departments, fullName)
