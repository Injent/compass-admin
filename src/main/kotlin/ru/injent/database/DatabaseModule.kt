package ru.injent.database

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import ru.injent.service.ScheduleGroupService

val databaseModule = module {
    single {
        val databasePath = System.getenv("COMPASS_DB_PATH") ?: "compassadmin.db"
        Database.connect("jdbc:sqlite:$databasePath", driver = "org.sqlite.JDBC")
            .also(::initializeDatabase)
    }
    singleOf(::ScheduleGroupService)
}

private fun initializeDatabase(database: Database) {
    transaction(database) {
        SchemaUtils.create(Teachers, ScheduleGroups)
    }
}
