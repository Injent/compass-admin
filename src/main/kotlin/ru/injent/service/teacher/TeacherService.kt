package ru.injent.service.teacher

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import ru.injent.database.TeachersTable
import ru.injent.domain.Teacher

/**
 * Сервис управления записями преподавателей в базе данных.
 */
class TeacherService(
    private val database: Database,
) {
    fun getAll(): List<Teacher> = transaction(database) {
        TeachersTable
            .selectAll()
            .orderBy(TeachersTable.lastName to SortOrder.ASC, TeachersTable.firstName to SortOrder.ASC)
            .map { row ->
                Teacher(
                    id = row[TeachersTable.id].value,
                    lastName = row[TeachersTable.lastName],
                    firstName = row[TeachersTable.firstName],
                    middleName = row[TeachersTable.middleName],
                    departments = row[TeachersTable.departments],
                )
            }
    }

    fun create(
        lastName: String,
        firstName: String,
        middleName: String,
        departments: String? = null,
    ): Int = transaction(database) {
        TeachersTable.insertAndGetId {
            it[TeachersTable.lastName] = lastName.trim()
            it[TeachersTable.firstName] = firstName.trim()
            it[TeachersTable.middleName] = middleName.trim()
            it[TeachersTable.departments] = departments?.trim().orEmpty()
        }.value
    }

    fun update(
        id: Int,
        lastName: String,
        firstName: String,
        middleName: String,
        departments: String?,
    ) = transaction(database) {
        TeachersTable.update({ TeachersTable.id eq id }) {
            it[TeachersTable.lastName] = lastName.trim()
            it[TeachersTable.firstName] = firstName.trim()
            it[TeachersTable.middleName] = middleName.trim()
            if (departments != null) {
                it[TeachersTable.departments] = departments.trim()
            }
        }
    }

    fun delete(id: Int) = transaction(database) {
        TeachersTable.deleteWhere { TeachersTable.id eq id }
    }

    fun delete(ids: Collection<Int>) = transaction(database) {
        ids.forEach { id ->
            TeachersTable.deleteWhere { TeachersTable.id eq id }
        }
    }
}
