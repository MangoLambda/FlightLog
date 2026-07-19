package com.example.flightlog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.flightlog.data.FlightLogDatabase
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.RideState
import com.example.flightlog.domain.SensorQuality
import com.example.flightlog.domain.TelemetryKind
import com.example.flightlog.tracking.JumpMotionTrace
import com.example.flightlog.tracking.MotionTelemetry
import com.example.flightlog.tracking.OrientationSource
import com.example.flightlog.tracking.RotationSample
import com.example.flightlog.tracking.Vector3Sample
import com.example.flightlog.tracking.RideProcessor
import com.example.flightlog.tracking.TelemetryCodec
import com.example.flightlog.tracking.toEntity
import kotlin.math.pow
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
        val raw = MotionTelemetry(accelerometer = listOf(100L, 250L, 1_000L, 1_500L, 1_850L, 1_900L).map {
            Vector3Sample(it, 0f, 0f, 9.8f)
        })
        dao.insertTelemetryChunk(TelemetryCodec.encodeMotion(raw).toEntity(
            rideId = rideId, kind = TelemetryKind.MOTION, expiresAt = 10_000,
        ))

        RideProcessor(database).materializeMissingJumpTraces()

        val trace = dao.jumpMotionTrace(jumpId)
        assertNotNull(trace)
        assertEquals(
            listOf(100L, 250L, 1_000L, 1_500L, 1_850L, 1_900L),
            JumpMotionTrace.decode(trace!!).accelerometer.map { it.timestampMillis },
        )
        database.close()
    }

    @Test fun postRideProcessingUsesAirtimeAndPreservesCorrections() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, FlightLogDatabase::class.java).build()
        val dao = database.dao()
        val rideId = dao.insertRide(RideEntity(
            startedAt = 100,
            endedAt = 2_000,
            state = RideState.COMPLETED,
            mountingMode = MountingMode.POCKET,
        ))
        dao.insertTrackPoint(TrackPointEntity(
            rideId = rideId,
            recordedAt = 100,
            latitude = 45.0,
            longitude = -73.0,
            altitudeMeters = 100.0,
            speedMps = 5.0,
            bearingDegrees = 0f,
            accuracyMeters = 3f,
        ))
        val jumpId = dao.insertJump(JumpEventEntity(
            rideId = rideId,
            takeoffAt = 1_000,
            landingAt = 1_600,
            estimatedFlightSeconds = .6,
            estimatedHeightMeters = .1,
            estimatedDistanceMeters = 2.0,
            correctedHeightMeters = 1.25,
            confidence = 78,
            sensorQuality = SensorQuality.FULL,
        ))
        dao.insertTelemetryChunk(TelemetryCodec.encodeMotion(fusionTelemetry()).toEntity(
            rideId = rideId,
            kind = TelemetryKind.MOTION,
            expiresAt = 10_000,
        ))

        RideProcessor(database).compactAndAnalyze(rideId)

        val analyzed = dao.jumps(rideId).single { it.id == jumpId }
        val airtimeHeight = 9.80665 * .6.pow(2) / 8.0
        assertEquals(airtimeHeight, analyzed.estimatedHeightMeters, .04)
        assertEquals(1.25, analyzed.correctedHeightMeters!!, .001)
        assertEquals(85, analyzed.confidence)
        database.close()
    }

    @Test fun postRideProcessingDoesNotReestimateWithoutMotionTelemetry() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, FlightLogDatabase::class.java).build()
        val dao = database.dao()
        val rideId = dao.insertRide(RideEntity(
            startedAt = 100,
            endedAt = 2_000,
            state = RideState.COMPLETED,
            mountingMode = MountingMode.POCKET,
        ))
        dao.insertTrackPoint(TrackPointEntity(
            rideId = rideId,
            recordedAt = 100,
            latitude = 45.0,
            longitude = -73.0,
            altitudeMeters = null,
            speedMps = 5.0,
            bearingDegrees = null,
            accuracyMeters = 3f,
        ))
        dao.insertJump(JumpEventEntity(
            rideId = rideId,
            takeoffAt = 1_000,
            landingAt = 1_600,
            estimatedFlightSeconds = .6,
            estimatedHeightMeters = .123,
            estimatedDistanceMeters = 2.0,
            confidence = 67,
            sensorQuality = SensorQuality.DEGRADED,
        ))

        RideProcessor(database).compactAndAnalyze(rideId)

        val jump = dao.jumps(rideId).single()
        assertEquals(.123, jump.estimatedHeightMeters, .0001)
        assertEquals(67, jump.confidence)
        database.close()
    }

    private fun fusionTelemetry(): MotionTelemetry {
        val start = 250L
        val end = 1_950L
        val accelerometer = (start..end step 10).map { timestamp ->
            val z = when {
                timestamp in 850L..1_000L -> 12.5f
                timestamp in 1_010L..1_590L -> 0f
                timestamp in 1_600L..1_750L -> 20f
                else -> 9.80665f
            }
            Vector3Sample(timestamp, 0f, 0f, z)
        }
        return MotionTelemetry(
            orientationSource = OrientationSource.GAME_ROTATION_VECTOR,
            accelerometer = accelerometer,
            gyroscope = (start..end step 10).map { Vector3Sample(it, 0f, 0f, 0f) },
            orientation = (start..end step 10).map { RotationSample(it, 0f, 0f, 0f, 1f) },
        )
    }
}
