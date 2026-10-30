package ru.injent.service.config

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import ru.injent.database.ServerSettingsTable
import java.io.File

// Keep the persisted key compatible with existing settings.
internal val finSupportSettingsKey = stringPreferencesKey("profkom")

class ServerSettingsStore(database: Database, file: File = File("data/prefs.preferences_pb")) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val preferences = PreferenceDataStoreFactory.create(
        scope = scope,
        migrations = listOf(object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences) = currentData[finSupportSettingsKey] == null
            override suspend fun migrate(currentData: Preferences): Preferences {
                val legacy = transaction(database) {
                    ServerSettingsTable.selectAll().where { ServerSettingsTable.key eq "profkom" }
                        .singleOrNull()?.get(ServerSettingsTable.value)
                }
                return currentData.toMutablePreferences().apply {
                    if (legacy != null) this[finSupportSettingsKey] = legacy
                }
            }
            override suspend fun cleanUp() = Unit
        }),
        produceFile = {
            file.absoluteFile.also { it.parentFile.mkdirs() }
        },
    )

    override fun close() { runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() } }
}
