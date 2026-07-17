package com.example.flightlog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.flightlog.data.FlightLogDatabase
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.JumpMotionTraceEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.RideRepository
import com.example.flightlog.data.SectionEffortEntity
import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.StopEventEntity
import com.example.flightlog.data.TelemetryChunkEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.data.TrailEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.data.TrailPauseZoneEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.data.TrailStopObservationEntity
import com.example.flightlog.domain.PauseZoneState
import com.example.flightlog.domain.RideState
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.SectionState
import com.example.flightlog.domain.SensorQuality
import com.example.flightlog.domain.TelemetryKind
import com.example.flightlog.domain.TrailState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideStorageEstimateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun estimatesEachRideFromItsOwnedPayloadsAndRows() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, FlightLogDatabase::class.java).build()
        val dao = database.dao()
        val repository = RideRepository(database)
        val rideId = dao.insertRide(RideEntity(startedAt = 1_000, endedAt = 2_000, state = RideState.COMPLETED))
        val emptyRideId = dao.insertRide(RideEntity(startedAt = 3_000, endedAt = 4_000, state = RideState.COMPLETED))

        dao.insertTrackPoint(TrackPointEntity(
            rideId = rideId, recordedAt = 1_000, latitude = 45.5, longitude = -73.5,
            altitudeMeters = 100.0, speedMps = 5.0, bearingDegrees = 10f, accuracyMeters = 3f,
        ))
        dao.insertTelemetryChunk(TelemetryChunkEntity(
            rideId = rideId, kind = TelemetryKind.MOTION, startedAt = 1_000, endedAt = 1_100,
            encodingVersion = 1, sampleCount = 1, payload = byteArrayOf(1, 2, 3), checksum = "test",
        ))
        val jumpId = dao.insertJump(JumpEventEntity(
            rideId = rideId, takeoffAt = 1_200, landingAt = 1_500,
            estimatedFlightSeconds = .3, estimatedHeightMeters = .1, estimatedDistanceMeters = 1.5,
            confidence = 80, sensorQuality = SensorQuality.FULL,
        ))
        dao.insertJumpMotionTrace(JumpMotionTraceEntity(
            jumpId = jumpId, startedAt = 1_100, endedAt = 1_600,
            encodingVersion = 1, sampleCount = 1, payload = byteArrayOf(4, 5), checksum = "test",
        ))
        dao.insertSpatialProfiles(listOf(SpatialProfileEntity(
            rideId = rideId, distanceBin = 0, distanceMeters = 0.0, recordedAt = 1_000,
            latitude = 45.5, longitude = -73.5, speedMps = 5.0, accuracyMeters = 3f,
        )))
        val stopId = dao.insertStopEvents(listOf(StopEventEntity(
            rideId = rideId, startedAt = 1_300, endedAt = 1_600,
            latitude = 45.5, longitude = -73.5, accuracyMeters = 3f, durationMillis = 300,
        ))).single()
        val trailId = dao.insertTrail(TrailEntity(
            name = "Shared definition", state = TrailState.CONFIRMED, canonicalRideId = rideId,
            lengthMeters = 100.0, endMeters = 100.0,
        ))
        val sectionId = dao.insertSection(TrailSectionEntity(
            trailId = trailId, name = "Whole trail", kind = SectionKind.WHOLE_TRAIL,
            state = SectionState.CONFIRMED, startMeters = 0.0, endMeters = 100.0,
        ))
        dao.insertPauseZone(TrailPauseZoneEntity(
            trailId = trailId, name = "Shared pause", startMeters = 30.0, endMeters = 40.0,
            state = PauseZoneState.USER_LOCKED, supportCount = 1, eligiblePassCount = 1,
            confidence = 100, medianPauseMillis = 300,
        ))
        val passId = dao.insertPass(TrailPassEntity(
            trailId = trailId, rideId = rideId, startedAt = 1_000, endedAt = 2_000,
            startMeters = 0.0, endMeters = 100.0, matchConfidence = 100, interrupted = false,
        ))
        dao.insertEfforts(listOf(SectionEffortEntity(
            passId = passId, sectionId = sectionId, elapsedMillis = 1_000,
            entrySpeedMps = 5.0, minimumSpeedMps = 4.0, averageSpeedMps = 5.0,
            exitSpeedMps = 6.0, maximumSpeedMps = 7.0, sampleQuality = 90,
            lateralOffsetMeters = 0.0, lateralUncertaintyMeters = 3.0, valid = true,
        )))
        dao.insertStopObservations(listOf(TrailStopObservationEntity(
            trailId = trailId, passId = passId, stopEventId = stopId, distanceMeters = 35.0,
            startMeters = 30.0, endMeters = 40.0, durationMillis = 300, confidence = 100,
        )))

        val sizes = repository.rideStorageBytes.first()

        assertEquals(1_149L, sizes[rideId])
        assertEquals(128L, sizes[emptyRideId])

        dao.deleteMotionForRide(rideId)
        assertEquals(1_018L, repository.rideStorageBytes.first()[rideId])
        database.close()
    }
}
