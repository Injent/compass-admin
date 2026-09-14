package ru.injent.web.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import ru.injent.domain.Teacher
import ru.injent.service.teacher.TeacherService
import ru.injent.web.dto.ApiError
import ru.injent.web.dto.DeleteTeachersRequest
import ru.injent.web.dto.TeacherInput
import ru.injent.web.dto.toView

/**
 * Маршруты управления базой данных преподавателей.
 */
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
