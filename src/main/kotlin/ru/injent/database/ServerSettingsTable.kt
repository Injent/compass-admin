package ru.injent.database

import org.jetbrains.exposed.v1.core.Table

object ServerSettingsTable : Table("server_settings") {
    val key = varchar("key", 80)
    val value = text("value")
    override val primaryKey = PrimaryKey(key)
}
