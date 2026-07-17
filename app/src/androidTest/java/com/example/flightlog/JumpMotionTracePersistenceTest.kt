package com.example.flightlog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.flightlog.data.FlightLogDatabase
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.domain.RideState
import com.example.flightlog.domain.SensorQuality
import com.example.flightlog.domain.TelemetryKind
import com.example.flightlog.tracking.JumpMotionTrace
import com.example.flightlog.tracking.MotionSample
import com.example.flightlog.tracking.RideProcessor
import com.example.flightlog.tracking.TelemetryCodec
import com.example.flightlog.tracking.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JumpMotionTracePersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun missingTraceIsMaterializedFromOverlappingRawMotion() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, FlightLogDatabase::class.java).build()
        val dao = database.dao()
        val rideId = dao.insertRide(RideEntity(startedAt = 100, endedAt = 2_000, state = RideState.COMPLETED))
        val jumpId = dao.insertJump(JumpEventEntity(
            rideId = rideId, takeoffAt = 1_000, landingAt = 1_500,
            estimatedFlightSeconds = .5, estimatedHeightMeters = .3, estimatedDistanceMeters = 2.0,
            confidence = 80, sensorQuality = SensorQuality.FULL,
        ))
        val raw = listOf(100L, 250L, 1_000L, 1_500L, 1_850L, 1_900L).map {
            MotionSample(it, 0f, 0f, 9.8f, 0f, 0f, 0f)
        }
        dao.insertTelemetryChunk(TelemetryCodec.encodeMotion(raw).toEntity(
            rideId = rideId, kind = TelemetryKind.MOTION, expiresAt = 10_000,
        ))

        RideProcessor(database).materializeMissingJumpTraces()

        val trace = dao.jumpMotionTrace(jumpId)
        assertNotNull(trace)
        assertEquals(listOf(250L, 1_000L, 1_500L, 1_850L), JumpMotionTrace.decode(trace!!).map { it.timestampMillis })
        database.close()
    }
}
