package ru.injent.database

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

object Teachers : IntIdTable("teachers") {
    val lastName = varchar("last_name", 160)
    val firstName = varchar("first_name", 160)
    val middleName = varchar("middle_name", 160)
    val departments = text("departments")
}
