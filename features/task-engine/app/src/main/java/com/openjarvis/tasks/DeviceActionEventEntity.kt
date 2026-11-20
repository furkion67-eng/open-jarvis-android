package com.openjarvis.tasks

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Privacy-minimal device-action outcome; never stores contacts, phone numbers or message bodies. */
@Entity(
    tableName = "jarvis_device_action_events",
    indices = [Index(value = ["createdAt"])]
)
data class DeviceActionEventEntity(
    @PrimaryKey val id: String,
    val actionType: String,
    val outcomeCode: String,
    val safeSummary: String,
    val createdAt: Long
)
