package com.example.flightlog

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.flightlog.data.FlightLogDatabase
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlightLogMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before fun prepare() { context.deleteDatabase(name) }
    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun migrationFromVersionOnePreservesRideAndPoints() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE rides (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER, state TEXT NOT NULL, distanceMeters REAL NOT NULL, movingTimeMillis INTEGER NOT NULL, maxSpeedMps REAL NOT NULL)")
                    db.execSQL("CREATE TABLE track_points (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, rideId INTEGER NOT NULL, recordedAt INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, altitudeMeters REAL, speedMps REAL NOT NULL, bearingDegrees REAL, accuracyMeters REAL NOT NULL, FOREIGN KEY(rideId) REFERENCES rides(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_track_points_rideId ON track_points(rideId)")
                    db.execSQL("CREATE INDEX index_track_points_rideId_recordedAt ON track_points(rideId, recordedAt)")
                    db.execSQL("CREATE TABLE jump_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, rideId INTEGER NOT NULL, takeoffAt INTEGER NOT NULL, landingAt INTEGER NOT NULL, estimatedFlightSeconds REAL NOT NULL, estimatedHeightMeters REAL NOT NULL, estimatedDistanceMeters REAL NOT NULL, correctedFlightSeconds REAL, correctedHeightMeters REAL, correctedDistanceMeters REAL, confidence INTEGER NOT NULL, status TEXT NOT NULL, sensorQuality TEXT NOT NULL, latitude REAL, longitude REAL, FOREIGN KEY(rideId) REFERENCES rides(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_jump_events_rideId ON jump_events(rideId)")
                    db.execSQL("CREATE INDEX index_jump_events_rideId_takeoffAt ON jump_events(rideId, takeoffAt)")
                    db.execSQL("INSERT INTO rides VALUES (1, 1000, 2000, 'COMPLETED', 100.0, 10000, 12.0)")
                    db.execSQL("INSERT INTO track_points VALUES (1, 1, 1000, 45.5, -73.5, 10.0, 5.0, 90.0, 3.0)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { it.writableDatabase }

        val database = Room.databaseBuilder(context, FlightLogDatabase::class.java, name)
            .addMigrations(
                FlightLogDatabase.MIGRATION_1_2,
                FlightLogDatabase.MIGRATION_2_3,
                FlightLogDatabase.MIGRATION_3_4,
                FlightLogDatabase.MIGRATION_4_5,
                FlightLogDatabase.MIGRATION_5_6,
                FlightLogDatabase.MIGRATION_6_7,
                FlightLogDatabase.MIGRATION_7_8,
            ).build()
        val ride = runBlocking { database.dao().ride(1) }
        val points = runBlocking { database.dao().trackPoints(1) }
        assertNotNull(ride)
        UUID.fromString(ride!!.uuid)
        assertEquals(1, points.size)
        assertEquals(8, database.openHelper.readableDatabase.version)
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM jump_motion_traces").use {
            assertEquals(true, it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        database.close()
    }
}
