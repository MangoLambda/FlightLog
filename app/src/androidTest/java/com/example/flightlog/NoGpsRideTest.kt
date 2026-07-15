package com.example.flightlog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.flightlog.data.FlightLogDatabase
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.TelemetryChunkEntity
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.RideState
import com.example.flightlog.domain.SensorQuality
import com.example.flightlog.domain.TelemetryKind
import com.example.flightlog.tracking.RideProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoGpsRideTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun processingRideWithoutGpsDeletesAllSensorData() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, FlightLogDatabase::class.java).build()
        val dao = database.dao()
        val rideId = dao.insertRide(RideEntity(
            startedAt = 1_000, endedAt = 2_000, state = RideState.COMPLETED,
            mountingMode = MountingMode.BIKE_MOUNTED,
        ))
        dao.insertTelemetryChunk(TelemetryChunkEntity(
            rideId = rideId, kind = TelemetryKind.MOTION, startedAt = 1_000, endedAt = 2_000,
            encodingVersion = 1, sampleCount = 10, payload = byteArrayOf(1, 2, 3), checksum = "sensor-data",
            expiresAt = 10_000,
        ))
        dao.insertJump(JumpEventEntity(
            rideId = rideId, takeoffAt = 1_200, landingAt = 1_500,
            estimatedFlightSeconds = .3, estimatedHeightMeters = .1, estimatedDistanceMeters = 1.5,
            confidence = 75, sensorQuality = SensorQuality.FULL,
        ))

        RideProcessor(database).compactAndAnalyze(rideId)

        assertEquals(0, dao.telemetryChunks(rideId).size)
        assertEquals(0, dao.jumps(rideId).size)
        assertNotNull(dao.ride(rideId)?.archivedAt)
        assertEquals(1, dao.ride(rideId)?.analysisVersion)
        database.close()
    }
}
