package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_shifts")
data class CachedShiftEntity(
    @PrimaryKey val id: String,
    val stationId: String,
    val stationName: String,
    val guardId: String,
    val guardName: String,
    val employeeNumber: String?,
    val date: String,
    val startTime: String,
    val endTime: String,
    val shiftType: String,
    val partnerId: String?,
    val partnerName: String?,
    val partnerEmployeeNumber: String?,
    val attendanceStatus: String
)

@Entity(tableName = "cached_ob_entries")
data class CachedOBEntity(
    @PrimaryKey val id: String,
    val entryNumber: String,
    val stationName: String,
    val guardName: String,
    val category: String,
    val categoryDisplay: String?,
    val occurrenceText: String,
    val checkRecord: String?,
    val createdAt: String
)

@Entity(tableName = "cached_incidents")
data class CachedIncidentEntity(
    @PrimaryKey val id: String,
    val stationName: String,
    val reportingGuardName: String,
    val priority: String,
    val priorityDisplay: String?,
    val title: String,
    val description: String,
    val location: String,
    val status: String,
    val createdAt: String
)
