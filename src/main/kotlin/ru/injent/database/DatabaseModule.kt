package ru.injent.database

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.dsl.onClose
import ru.injent.service.ScheduleChangeTracker
import ru.injent.service.ScheduleGroupService
import ru.injent.service.config.FinSupportSettingsService
import ru.injent.service.config.ServerSettingsStore

/**
 * Koin-модуль базы данных и инициализации Exposed.
 */
val databaseModule = module {
    single {
        val databasePath = System.getenv("COMPASS_DB_PATH") ?: "compassadmin.db"
        val url = "jdbc:sqlite:$databasePath?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL"
        Database.connect(url, driver = "org.sqlite.JDBC")
            .also(::initializeDatabase)
    }
    singleOf(::ScheduleGroupService)
    singleOf(::ScheduleChangeTracker)
    single { ServerSettingsStore(get()) } onClose { it?.close() }
    single { FinSupportSettingsService(get<ServerSettingsStore>().preferences) }
}

private fun initializeDatabase(database: Database) {
    transaction(database) {
        SchemaUtils.create(TeachersTable, ScheduleGroupsTable, ScheduleFileApprovalsTable, ServerSettingsTable)
    }
}
