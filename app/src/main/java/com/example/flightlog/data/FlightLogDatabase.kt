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
        JumpMotionTraceEntity::class,
        TelemetryChunkEntity::class, SpatialProfileEntity::class, TrailEntity::class,
        StopEventEntity::class, TrailPauseZoneEntity::class, TrailSectionEntity::class,
        TrailPassEntity::class, TrailStopObservationEntity::class, SectionEffortEntity::class,
        PhysicalFeatureEntity::class, FeatureObservationEntity::class, ManualTrailAssignmentEntity::class,
    ],
    version = 9,
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
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build().also { instance = it }
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE spatial_profiles ADD COLUMN altitudeMeters REAL")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE spatial_profiles ADD COLUMN maximumSampleGapMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE section_efforts ADD COLUMN invalidReason TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE stop_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, rideId INTEGER NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, accuracyMeters REAL NOT NULL, durationMillis INTEGER NOT NULL, FOREIGN KEY(rideId) REFERENCES rides(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE UNIQUE INDEX index_stop_events_uuid ON stop_events(uuid)")
                db.execSQL("CREATE INDEX index_stop_events_rideId ON stop_events(rideId)")
                db.execSQL("CREATE INDEX index_stop_events_rideId_startedAt ON stop_events(rideId, startedAt)")
                db.execSQL("""CREATE TABLE trail_pause_zones (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, trailId INTEGER NOT NULL, name TEXT NOT NULL, startMeters REAL NOT NULL, endMeters REAL NOT NULL, state TEXT NOT NULL, supportCount INTEGER NOT NULL, eligiblePassCount INTEGER NOT NULL, confidence INTEGER NOT NULL, medianPauseMillis INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(trailId) REFERENCES trails(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE UNIQUE INDEX index_trail_pause_zones_uuid ON trail_pause_zones(uuid)")
                db.execSQL("CREATE INDEX index_trail_pause_zones_trailId ON trail_pause_zones(trailId)")
                db.execSQL("CREATE INDEX index_trail_pause_zones_trailId_startMeters ON trail_pause_zones(trailId, startMeters)")
                db.execSQL("ALTER TABLE trail_sections ADD COLUMN precedingPauseZoneId INTEGER")
                db.execSQL("ALTER TABLE trail_sections ADD COLUMN followingPauseZoneId INTEGER")
                db.execSQL("ALTER TABLE trail_passes ADD COLUMN completeCoverage INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trail_passes ADD COLUMN stopCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trail_passes ADD COLUMN stoppedDurationMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trail_passes ADD COLUMN hasReversal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trail_passes ADD COLUMN bridgedGapMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trail_passes ADD COLUMN fullRunEligible INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""CREATE TABLE trail_stop_observations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, trailId INTEGER NOT NULL, passId INTEGER NOT NULL, stopEventId INTEGER NOT NULL, distanceMeters REAL NOT NULL, startMeters REAL NOT NULL, endMeters REAL NOT NULL, durationMillis INTEGER NOT NULL, confidence INTEGER NOT NULL, FOREIGN KEY(trailId) REFERENCES trails(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(passId) REFERENCES trail_passes(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(stopEventId) REFERENCES stop_events(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE UNIQUE INDEX index_trail_stop_observations_uuid ON trail_stop_observations(uuid)")
                db.execSQL("CREATE INDEX index_trail_stop_observations_trailId ON trail_stop_observations(trailId)")
                db.execSQL("CREATE INDEX index_trail_stop_observations_passId ON trail_stop_observations(passId)")
                db.execSQL("CREATE INDEX index_trail_stop_observations_stopEventId ON trail_stop_observations(stopEventId)")
                db.execSQL("CREATE INDEX index_trail_stop_observations_trailId_distanceMeters ON trail_stop_observations(trailId, distanceMeters)")
                db.execSQL("ALTER TABLE section_efforts ADD COLUMN reachedWithoutPriorStop INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE section_efforts ADD COLUMN estimated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE section_efforts ADD COLUMN bridgedGapMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE jump_motion_traces (jumpId INTEGER NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL, encodingVersion INTEGER NOT NULL, sampleCount INTEGER NOT NULL, payload BLOB NOT NULL, checksum TEXT NOT NULL, PRIMARY KEY(jumpId), FOREIGN KEY(jumpId) REFERENCES jump_events(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jump_events ADD COLUMN estimatedFlightKind TEXT NOT NULL DEFAULT 'UNCERTAIN'")
                db.execSQL("ALTER TABLE jump_events ADD COLUMN correctedFlightKind TEXT")
                db.execSQL("ALTER TABLE jump_events ADD COLUMN flightKindConfidence INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE physical_features (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, approachBearingDegrees REAL, exitBearingDegrees REAL, confidence INTEGER NOT NULL, observationCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""")
                db.execSQL("CREATE UNIQUE INDEX index_physical_features_uuid ON physical_features(uuid)")
                db.execSQL("CREATE INDEX index_physical_features_updatedAt ON physical_features(updatedAt)")
                db.execSQL("""CREATE TABLE feature_observations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, jumpId INTEGER NOT NULL, featureId INTEGER, assignmentState TEXT NOT NULL, assignmentSource TEXT NOT NULL, matchConfidence INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, gpsAccuracyMeters REAL NOT NULL, approachBearingDegrees REAL, exitBearingDegrees REAL, takeoffSpeedMps REAL, airtimeSeconds REAL NOT NULL, heightMeters REAL NOT NULL, distanceMeters REAL NOT NULL, landingPeakG REAL, landingSmoothness INTEGER, mountingMode TEXT, metricVersion INTEGER NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(jumpId) REFERENCES jump_events(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(featureId) REFERENCES physical_features(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
                db.execSQL("CREATE INDEX index_feature_observations_featureId ON feature_observations(featureId)")
                db.execSQL("CREATE UNIQUE INDEX index_feature_observations_jumpId ON feature_observations(jumpId)")
                db.execSQL("CREATE INDEX index_feature_observations_assignmentState ON feature_observations(assignmentState)")
            }
        }
        /** Old passes were allowed at 40% coverage.  Rebuild them under the strict rule. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE manual_trail_assignments (rideId INTEGER NOT NULL, trailId INTEGER NOT NULL, startMeters REAL NOT NULL, endMeters REAL NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(rideId), FOREIGN KEY(rideId) REFERENCES rides(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(trailId) REFERENCES trails(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX index_manual_trail_assignments_trailId ON manual_trail_assignments(trailId)")
                db.execSQL("DELETE FROM trail_passes")
                db.execSQL("UPDATE rides SET analysisVersion = 0")
            }
        }
    }
}
