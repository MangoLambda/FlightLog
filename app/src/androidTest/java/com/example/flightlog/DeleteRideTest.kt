package com.example.flightlog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.flightlog.data.*
import com.example.flightlog.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteRideTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun finishedRideCascadesAllOwnedDataWhileActiveRideIsProtected() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, FlightLogDatabase::class.java).build()
        val dao = database.dao()
        val finishedId = dao.insertRide(RideEntity(startedAt = 1_000, endedAt = 2_000, state = RideState.COMPLETED))
        dao.insertTrackPoint(TrackPointEntity(
            rideId = finishedId, recordedAt = 1_000, latitude = 45.5, longitude = -73.5,
            altitudeMeters = null, speedMps = 5.0, bearingDegrees = null, accuracyMeters = 3f,
        ))
        val jumpId = dao.insertJump(JumpEventEntity(
            rideId = finishedId, takeoffAt = 1_200, landingAt = 1_500,
            estimatedFlightSeconds = .3, estimatedHeightMeters = .1, estimatedDistanceMeters = 1.5,
            confidence = 80, sensorQuality = SensorQuality.FULL,
        ))
        dao.insertJumpMotionTrace(JumpMotionTraceEntity(
            jumpId = jumpId, startedAt = 1_000, endedAt = 1_500,
            encodingVersion = 1, sampleCount = 1, payload = byteArrayOf(1), checksum = "test",
        ))
        dao.insertTelemetryChunk(TelemetryChunkEntity(
            rideId = finishedId, kind = TelemetryKind.GPS, startedAt = 1_000, endedAt = 1_000,
            encodingVersion = 1, sampleCount = 1, payload = byteArrayOf(1), checksum = "test",
        ))
        dao.insertSpatialProfiles(listOf(SpatialProfileEntity(
            rideId = finishedId, distanceBin = 0, distanceMeters = 0.0, recordedAt = 1_000,
            latitude = 45.5, longitude = -73.5, speedMps = 5.0, accuracyMeters = 3f,
        )))
        val trailId = dao.insertTrail(TrailEntity(
            name = "Referenced trail", state = TrailState.CONFIRMED, canonicalRideId = finishedId,
            lengthMeters = 100.0, endMeters = 100.0,
        ))
        val sectionId = dao.insertSection(TrailSectionEntity(
            trailId = trailId, name = "Whole trail", kind = SectionKind.WHOLE_TRAIL,
            state = SectionState.CONFIRMED, startMeters = 0.0, endMeters = 100.0,
        ))
        val passId = dao.insertPass(TrailPassEntity(
            trailId = trailId, rideId = finishedId, startedAt = 1_000, endedAt = 2_000,
            startMeters = 0.0, endMeters = 100.0, matchConfidence = 100, interrupted = false,
        ))
        dao.insertEfforts(listOf(SectionEffortEntity(
            passId = passId, sectionId = sectionId, elapsedMillis = 1_000,
            entrySpeedMps = 5.0, minimumSpeedMps = 4.0, averageSpeedMps = 5.0,
            exitSpeedMps = 6.0, maximumSpeedMps = 7.0, sampleQuality = 90,
            lateralOffsetMeters = 0.0, lateralUncertaintyMeters = 3.0, valid = true,
        )))

        assertEquals(1, dao.deleteFinishedRide(finishedId))
        assertNull(dao.ride(finishedId))
        assertEquals(0, dao.trackPoints(finishedId).size)
        assertEquals(0, dao.jumps(finishedId).size)
        assertEquals(0, dao.allJumpMotionTraces().size)
        assertEquals(0, dao.telemetryChunks(finishedId).size)
        assertEquals(0, dao.allTrails().size)
        assertEquals(0, dao.allSections().size)
        assertEquals(0, dao.allPasses().size)
        assertEquals(0, dao.allEfforts().size)

        val activeId = dao.insertRide(RideEntity(startedAt = 3_000, state = RideState.RECORDING))
        assertEquals(0, dao.deleteFinishedRide(activeId))
        assertNotNull(dao.ride(activeId))
        database.close()
    }
}
