package ru.injent.service.teacher

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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

class TeacherService(
    databasePath: String = "compassadmin.db",
) {
    private val database = Database.connect("jdbc:sqlite:$databasePath", driver = "org.sqlite.JDBC")

    init {
        transaction(database) {
            SchemaUtils.create(Teachers)
        }
    }

    fun getAll(): List<Teacher> = transaction(database) {
        Teachers
            .selectAll()
            .orderBy(Teachers.lastName to SortOrder.ASC, Teachers.firstName to SortOrder.ASC)
            .map { row ->
                Teacher(
                    id = row[Teachers.id].value,
                    lastName = row[Teachers.lastName],
                    firstName = row[Teachers.firstName],
                    middleName = row[Teachers.middleName],
                    departments = row[Teachers.departments],
                )
            }
    }

    fun create(): Int = transaction(database) {
        Teachers.insertAndGetId {
            it[lastName] = ""
            it[firstName] = ""
            it[middleName] = ""
            it[departments] = ""
        }.value
    }

    fun update(
        id: Int,
        lastName: String,
        firstName: String,
        middleName: String,
        departments: String?,
    ) = transaction(database) {
        Teachers.update({ Teachers.id eq id }) {
            it[Teachers.lastName] = lastName.trim()
            it[Teachers.firstName] = firstName.trim()
            it[Teachers.middleName] = middleName.trim()
            if (departments != null) {
                it[Teachers.departments] = departments.trim()
            }
        }
    }

    fun delete(id: Int) = transaction(database) {
        Teachers.deleteWhere { Teachers.id eq id }
    }

    fun delete(ids: Collection<Int>) = transaction(database) {
        ids.forEach { id ->
            Teachers.deleteWhere { Teachers.id eq id }
        }
    }
}

private object Teachers : IntIdTable("teachers") {
    val lastName = varchar("last_name", 160)
    val firstName = varchar("first_name", 160)
    val middleName = varchar("middle_name", 160)
    val departments = text("departments")
}
