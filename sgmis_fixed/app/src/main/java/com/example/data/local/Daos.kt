package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ShiftDao {
    @Query("SELECT * FROM cached_shifts WHERE date = :date LIMIT 1")
    fun getShiftByDate(date: String): Flow<CachedShiftEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShift(shift: CachedShiftEntity)

    @Query("DELETE FROM cached_shifts")
    suspend fun clearShifts()
}

@Dao
interface OBDao {
    @Query("SELECT * FROM cached_ob_entries ORDER BY createdAt DESC")
    fun getAllEntries(): Flow<List<CachedOBEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<CachedOBEntity>)

    @Query("DELETE FROM cached_ob_entries")
    suspend fun clearEntries()
}

@Dao
interface IncidentDao {
    @Query("SELECT * FROM cached_incidents ORDER BY createdAt DESC")
    fun getAllIncidents(): Flow<List<CachedIncidentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncidents(incidents: List<CachedIncidentEntity>)

    @Query("DELETE FROM cached_incidents")
    suspend fun clearIncidents()
}

@Dao
interface StationDao {
    @Query("SELECT * FROM cached_stations ORDER BY name ASC")
    suspend fun getAllStations(): List<CachedStationEntity>

    @Query("SELECT * FROM cached_stations ORDER BY name ASC")
    fun getAllStationsFlow(): Flow<List<CachedStationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStations(stations: List<CachedStationEntity>)

    @Query("DELETE FROM cached_stations")
    suspend fun clearStations()
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM cached_checkpoints ORDER BY `order` ASC")
    suspend fun getAllCheckpoints(): List<CachedCheckpointEntity>

    @Query("SELECT * FROM cached_checkpoints WHERE stationId = :stationId ORDER BY `order` ASC")
    suspend fun getCheckpointsByStation(stationId: String): List<CachedCheckpointEntity>

    @Query("SELECT * FROM cached_checkpoints ORDER BY `order` ASC")
    fun getAllCheckpointsFlow(): Flow<List<CachedCheckpointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoints(checkpoints: List<CachedCheckpointEntity>)

    @Query("DELETE FROM cached_checkpoints")
    suspend fun clearCheckpoints()
}

@Dao
interface PatrolDao {
    @Query("SELECT * FROM cached_patrol_logs ORDER BY startTime DESC, startWindow DESC")
    fun getAllPatrolLogsFlow(): Flow<List<CachedPatrolLogEntity>>

    @Query("SELECT * FROM cached_patrol_logs ORDER BY startTime DESC, startWindow DESC")
    suspend fun getAllPatrolLogs(): List<CachedPatrolLogEntity>

    @Query("SELECT * FROM cached_patrol_logs WHERE id = :id LIMIT 1")
    suspend fun getPatrolLogById(id: String): CachedPatrolLogEntity?

    @Query("SELECT * FROM cached_patrol_logs WHERE status = 'IN_PROGRESS' OR status = 'ACTIVE' LIMIT 1")
    fun getActivePatrolFlow(): Flow<CachedPatrolLogEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatrolLogs(logs: List<CachedPatrolLogEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatrolLog(log: CachedPatrolLogEntity)

    @Query("DELETE FROM cached_patrol_logs")
    suspend fun clearPatrolLogs()

    @Query("SELECT * FROM cached_patrol_events WHERE patrolLogId = :patrolLogId AND isSynced = 0 ORDER BY checkpointOrder ASC")
    suspend fun getUnsyncedEventsForPatrol(patrolLogId: String): List<CachedPatrolEventEntity>

    @Query("SELECT * FROM cached_patrol_events WHERE isSynced = 0 ORDER BY checkpointOrder ASC")
    suspend fun getAllUnsyncedEvents(): List<CachedPatrolEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatrolEvent(event: CachedPatrolEventEntity)

    @Query("UPDATE cached_patrol_events SET isSynced = 1 WHERE clientEventId IN (:clientEventIds)")
    suspend fun markEventsSynced(clientEventIds: List<String>)

    @Query("DELETE FROM cached_patrol_events WHERE patrolLogId = :patrolLogId")
    suspend fun clearEventsForPatrol(patrolLogId: String)
}


