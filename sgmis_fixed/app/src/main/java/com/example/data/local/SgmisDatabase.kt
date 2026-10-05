package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedShiftEntity::class,
        CachedOBEntity::class,
        CachedIncidentEntity::class,
        CachedStationEntity::class,
        CachedCheckpointEntity::class,
        CachedPatrolLogEntity::class,
        CachedPatrolEventEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class SgmisDatabase : RoomDatabase() {
    abstract fun shiftDao(): ShiftDao
    abstract fun obDao(): OBDao
    abstract fun incidentDao(): IncidentDao
    abstract fun stationDao(): StationDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun patrolDao(): PatrolDao

    companion object {
        @Volatile
        private var INSTANCE: SgmisDatabase? = null

        fun getDatabase(context: Context): SgmisDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SgmisDatabase::class.java,
                    "sgmis_operational_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
