package com.example.flightlog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RideEntity::class, TrackPointEntity::class, JumpEventEntity::class,
        TelemetryChunkEntity::class, SpatialProfileEntity::class, TrailEntity::class,
        TrailSectionEntity::class, TrailPassEntity::class, SectionEffortEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FlightLogDatabase : RoomDatabase() {
    abstract fun dao(): FlightLogDao

    companion object {
        @Volatile private var instance: FlightLogDatabase? = null

        fun get(context: Context): FlightLogDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FlightLogDatabase::class.java,
                "flightlog.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE rides ADD COLUMN mountingMode TEXT")
                db.execSQL("ALTER TABLE rides ADD COLUMN archivedAt INTEGER")
                db.execSQL("ALTER TABLE rides ADD COLUMN analysisVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE rides SET uuid = printf('00000000-0000-0000-0000-%012d', id)")
                db.execSQL("CREATE UNIQUE INDEX index_rides_uuid ON rides(uuid)")
                db.execSQL("""CREATE TABLE telemetry_chunks (uuid TEXT NOT NULL, rideId INTEGER NOT NULL, kind TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL, encodingVersion INTEGER NOT NULL, sampleCount INTEGER NOT NULL, payload BLOB NOT NULL, checksum TEXT NOT NULL, expiresAt INTEGER, PRIMARY KEY(uuid), FOREIGN KEY(rideId) REFERENCES rides(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX index_telemetry_chunks_rideId ON telemetry_chunks(rideId)")
                db.execSQL("CREATE INDEX index_telemetry_chunks_rideId_kind_startedAt ON telemetry_chunks(rideId, kind, startedAt)")
                db.execSQL("CREATE INDEX index_telemetry_chunks_expiresAt ON telemetry_chunks(expiresAt)")
                db.execSQL("""CREATE TABLE spatial_profiles (rideId INTEGER NOT NULL, distanceBin INTEGER NOT NULL, distanceMeters REAL NOT NULL, recordedAt INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, speedMps REAL NOT NULL, accuracyMeters REAL NOT NULL, observedSpanMillis INTEGER NOT NULL DEFAULT 0, roughnessScore REAL, roughnessKind TEXT, roughnessConfidence INTEGER, PRIMARY KEY(rideId, distanceBin), FOREIGN KEY(rideId) REFERENCES rides(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX index_spatial_profiles_rideId ON spatial_profiles(rideId)")
                db.execSQL("""CREATE TABLE trails (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, state TEXT NOT NULL, canonicalRideId INTEGER NOT NULL, lengthMeters REAL NOT NULL, startMeters REAL NOT NULL DEFAULT 0, endMeters REAL NOT NULL DEFAULT 0, supportCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(canonicalRideId) REFERENCES rides(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE UNIQUE INDEX index_trails_uuid ON trails(uuid)")
                db.execSQL("CREATE INDEX index_trails_canonicalRideId ON trails(canonicalRideId)")
                db.execSQL("CREATE INDEX index_trails_state ON trails(state)")
                db.execSQL("""CREATE TABLE trail_sections (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, trailId INTEGER NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL, startMeters REAL NOT NULL, endMeters REAL NOT NULL, FOREIGN KEY(trailId) REFERENCES trails(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE UNIQUE INDEX index_trail_sections_uuid ON trail_sections(uuid)")
                db.execSQL("CREATE INDEX index_trail_sections_trailId ON trail_sections(trailId)")
                db.execSQL("""CREATE TABLE trail_passes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, trailId INTEGER NOT NULL, rideId INTEGER NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL, startMeters REAL NOT NULL, endMeters REAL NOT NULL, matchConfidence INTEGER NOT NULL, interrupted INTEGER NOT NULL, FOREIGN KEY(trailId) REFERENCES trails(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(rideId) REFERENCES rides(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE UNIQUE INDEX index_trail_passes_uuid ON trail_passes(uuid)")
                db.execSQL("CREATE INDEX index_trail_passes_trailId ON trail_passes(trailId)")
                db.execSQL("CREATE INDEX index_trail_passes_rideId ON trail_passes(rideId)")
                db.execSQL("CREATE INDEX index_trail_passes_trailId_startedAt ON trail_passes(trailId, startedAt)")
                db.execSQL("""CREATE TABLE section_efforts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, passId INTEGER NOT NULL, sectionId INTEGER NOT NULL, elapsedMillis INTEGER NOT NULL, entrySpeedMps REAL NOT NULL, minimumSpeedMps REAL NOT NULL, averageSpeedMps REAL NOT NULL, exitSpeedMps REAL NOT NULL, maximumSpeedMps REAL NOT NULL, roughnessScore REAL, roughnessKind TEXT, sampleQuality INTEGER NOT NULL, lateralOffsetMeters REAL NOT NULL, lateralUncertaintyMeters REAL NOT NULL, valid INTEGER NOT NULL, FOREIGN KEY(passId) REFERENCES trail_passes(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(sectionId) REFERENCES trail_sections(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE UNIQUE INDEX index_section_efforts_uuid ON section_efforts(uuid)")
                db.execSQL("CREATE INDEX index_section_efforts_passId ON section_efforts(passId)")
                db.execSQL("CREATE INDEX index_section_efforts_sectionId ON section_efforts(sectionId)")
            }
        }
    }
}
