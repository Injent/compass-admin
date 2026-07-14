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

        call.respond(FreeMarkerContent("teachers/teachers.html", teachersModel(teacherService.getAll(), query = call.teacherQuery)))
    }

    get("/teachers/list") {
        call.respond(FreeMarkerContent("teachers/teachers_list_container.html", teachersModel(teacherService.getAll(), query = call.teacherQuery)))
    }

    post("/teachers/add") {
        call.respond(
            FreeMarkerContent(
                "teachers/teachers_list_container.html",
                teachersModel(teacherService.getAll(), NEW_TEACHER_ID, draftTeacher = NewTeacher)
            )
        )
    }

    post("/teachers/new") {
        val params = call.receiveParameters()
        val lastName = params["lastName"].orEmpty().trim()
        val firstName = params["firstName"].orEmpty().trim()

        if (lastName.isBlank() || firstName.isBlank()) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }

        teacherService.create(
            lastName = lastName,
            firstName = firstName,
            middleName = params["middleName"].orEmpty(),
            departments = params["departments"],
        )
        call.respond(FreeMarkerContent("teachers/teachers_list_container.html", teachersModel(teacherService.getAll())))
    }

    get("/teachers/{id}/edit") {
        val teacherId = call.parameters["id"]?.toIntOrNull()
        if (teacherId == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }

        val teachers = teacherService.getAll()
        val teacher = teachers.firstOrNull { teacher -> teacher.id == teacherId }
        if (teacher == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        call.respond(
            FreeMarkerContent(
                "teachers/teacher_panel.html",
                mapOf(
                    "teacher" to teacher,
                    "teacherNumber" to teachers.indexOfFirst { it.id == teacherId } + 1,
                    "openTeacherId" to teacherId,
                )
            )
        )
    }

    get("/teachers/chunk") {
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        call.respond(FreeMarkerContent("teachers/teachers_chunk.html", teachersChunkModel(teacherService.getAll(), offset, query = call.teacherQuery)))
    }

    post("/teachers/{id}") {
        val teacherId = call.parameters["id"]?.toIntOrNull()
        if (teacherId == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }

        val params = call.receiveParameters()
        val lastName = params["lastName"].orEmpty().trim()
        val firstName = params["firstName"].orEmpty().trim()

        if (lastName.isBlank() || firstName.isBlank()) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }

        teacherService.update(
            id = teacherId,
            lastName = lastName,
            firstName = firstName,
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
    limit: Int = TEACHERS_PAGE_SIZE,
    query: String = "",
    draftTeacher: Teacher? = null,
): Map<String, Any?> =
    teachersChunkModel(
        if (draftTeacher == null) teachers else listOf(draftTeacher) + teachers,
        offset = 0,
        limit = limit,
        query = query,
    ) + mapOf(
        "openTeacherId" to openTeacherId,
    )

private fun teachersChunkModel(
    teachers: List<Teacher>,
    offset: Int,
    limit: Int = TEACHERS_PAGE_SIZE,
    query: String = "",
): Map<String, Any?> {
    val filteredTeachers = teachers.filterByTeacherQuery(query)
    val safeOffset = offset.coerceIn(0, filteredTeachers.size)
    val safeLimit = limit.coerceAtLeast(0)
    val nextOffset = (safeOffset + safeLimit).coerceAtMost(filteredTeachers.size)

    return mapOf(
        "teachers" to filteredTeachers.subList(safeOffset, nextOffset),
        "teachersOffset" to safeOffset,
        "hasTeachers" to filteredTeachers.isNotEmpty(),
        "hasMoreTeachers" to (nextOffset < filteredTeachers.size),
        "nextTeachersOffset" to nextOffset,
        "openTeacherId" to null,
        "teacherQuery" to query,
    )
}

private const val TEACHERS_PAGE_SIZE = 80
private const val NEW_TEACHER_ID = -1
private val NewTeacher = Teacher(
    id = NEW_TEACHER_ID,
    lastName = "",
    firstName = "",
    middleName = "",
    departments = "",
)

private val ApplicationCall.teacherQuery: String
    get() = request.queryParameters["q"].orEmpty().trim()

private fun List<Teacher>.filterByTeacherQuery(query: String): List<Teacher> {
    if (query.isBlank()) return this
    return filter { teacher -> teacher.fullName.contains(query, ignoreCase = true) }
}

private val ApplicationCall.isHtmxRequest: Boolean
    get() = request.headers["HX-Request"] == "true"
