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

@Entity(tableName = "cached_stations")
data class CachedStationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val code: String?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val geofenceRadius: Double?,
    val createdAt: String?
)

@Entity(tableName = "cached_checkpoints")
data class CachedCheckpointEntity(
    @PrimaryKey val id: String,
    val stationId: String,
    val stationName: String,
    val name: String,
    val code: String,
    val qrCode: String,
    val nfcUid: String? = null,
    val latitude: Double,
    val longitude: Double,
    val order: Int,
    val minIntervalSeconds: Int = 60,
    val isActive: Boolean
)

@Entity(tableName = "cached_patrol_logs")
data class CachedPatrolLogEntity(
    @PrimaryKey val id: String,
    val name: String,
    val guard: String,
    val guardName: String,
    val station: String,
    val stationName: String,
    val assignedBy: String?,
    val assignedByName: String?,
    val startWindow: String?,
    val deadline: String?,
    val startTime: String?,
    val endTime: String?,
    val status: String,
    val isApproved: Boolean,
    val approvedBy: String?,
    val approvedByName: String?,
    val approvedAt: String?,
    val anomaliesCount: Int,
    val notes: String?,
    val scansCount: Int
)

@Entity(tableName = "cached_patrol_events")
data class CachedPatrolEventEntity(
    @PrimaryKey val clientEventId: String,
    val patrolLogId: String,
    val checkpointId: String,
    val checkpointCode: String,
    val checkpointOrder: Int,
    val scannedAt: String,
    val clientTimestamp: String,
    val gpsCoords: String?,
    val accuracy: Double?,
    val verificationMethod: String,
    val notes: String?,
    val isSynced: Boolean = false
)

