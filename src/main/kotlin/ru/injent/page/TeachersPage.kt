package ru.injent.page

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import ru.injent.database.Teacher
import ru.injent.service.teacher.TeacherService

fun Routing.teachersPage(teacherService: TeacherService) {
    get("/teachers") {
        if (!call.isHtmxRequest) {
            call.respond(FreeMarkerContent("index.html", indexModel(call)))
            return@get
        }

        call.respond(FreeMarkerContent("teachers/teachers.html", teachersModel(teacherService.getAll())))
    }

    post("/teachers/add") {
        val teacherId = teacherService.create()
        call.respond(FreeMarkerContent("teachers/teachers_list_container.html", teachersModel(teacherService.getAll(), teacherId)))
    }

    post("/teachers/{id}") {
        val teacherId = call.parameters["id"]?.toIntOrNull()
        if (teacherId == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }

        val params = call.receiveParameters()
        teacherService.update(
            id = teacherId,
            lastName = params["lastName"].orEmpty(),
            firstName = params["firstName"].orEmpty(),
            middleName = params["middleName"].orEmpty(),
            departments = params["departments"],
        )
        call.respond(FreeMarkerContent("teachers/teachers_list_container.html", teachersModel(teacherService.getAll())))
    }

    delete("/teachers/{id}") {
        val teacherId = call.parameters["id"]?.toIntOrNull()
        if (teacherId == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@delete
        }

        teacherService.delete(teacherId)
        call.respond(FreeMarkerContent("teachers/teachers_list_container.html", teachersModel(teacherService.getAll())))
    }

    post("/teachers/delete") {
        val ids = call.receiveParameters()
            .getAll("teacherId")
            .orEmpty()
            .mapNotNull(String::toIntOrNull)

        teacherService.delete(ids)
        call.respond(FreeMarkerContent("teachers/teachers_list_container.html", teachersModel(teacherService.getAll())))
    }
}

private fun teachersModel(
    teachers: List<Teacher>,
    openTeacherId: Int? = null,
): Map<String, Any?> =
    mapOf(
        "teachers" to teachers,
        "openTeacherId" to openTeacherId,
    )

private val ApplicationCall.isHtmxRequest: Boolean
    get() = request.headers["HX-Request"] == "true"
