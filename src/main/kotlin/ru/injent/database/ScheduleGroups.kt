package ru.injent.database

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

object ScheduleGroups : IntIdTable("schedule_groups") {
    val fileId = varchar("file_id", 160)
    val name = varchar("name", 160)
    val normalizedName = varchar("normalized_name", 160)
    val syncStatus = varchar("sync_status", 32).default(SCHEDULE_GROUP_SYNC_STATUS_ACTIVE)
}

const val SCHEDULE_GROUP_SYNC_STATUS_ACTIVE = "ACTIVE"
const val SCHEDULE_GROUP_SYNC_STATUS_DELETE_PENDING = "DELETE_PENDING"
