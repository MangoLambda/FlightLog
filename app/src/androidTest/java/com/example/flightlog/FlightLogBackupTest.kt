package com.example.flightlog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.flightlog.data.FlightLogDatabase
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.data.StopEventEntity
import com.example.flightlog.data.TrailEntity
import com.example.flightlog.data.TrailPauseZoneEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.data.TrailStopObservationEntity
import com.example.flightlog.data.SectionEffortEntity
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.domain.RideState
import com.example.flightlog.domain.TrailState
import com.example.flightlog.domain.PauseZoneState
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.SectionState
import com.example.flightlog.domain.SensorQuality
import com.example.flightlog.domain.FlightKind
import com.example.flightlog.export.FlightLogBackup
import com.example.flightlog.tracking.JumpMotionTrace
import com.example.flightlog.tracking.MotionTelemetry
import com.example.flightlog.tracking.Vector3Sample
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlightLogBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun completeArchiveRoundTripIsDeduplicated() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, FlightLogDatabase::class.java).build()
        val ride = RideEntity(startedAt = 1_000, endedAt = 2_000, state = RideState.COMPLETED)
        val rideId = source.dao().insertRide(ride)
        source.dao().insertTrackPoint(TrackPointEntity(
            rideId = rideId, recordedAt = 1_000, latitude = 45.5, longitude = -73.5,
            altitudeMeters = 10.0, speedMps = 5.0, bearingDegrees = 90f, accuracyMeters = 3f,
        ))
        val jumpId = source.dao().insertJump(JumpEventEntity(
            rideId = rideId, takeoffAt = 1_200, landingAt = 1_500,
            estimatedFlightSeconds = .3, estimatedHeightMeters = .1, estimatedDistanceMeters = 1.5,
            confidence = 80, sensorQuality = SensorQuality.FULL,
            estimatedFlightKind = FlightKind.JUMP, correctedFlightKind = FlightKind.DROP,
            flightKindConfidence = 84,
        ))
        source.dao().insertJumpMotionTrace(JumpMotionTrace.encode(jumpId, MotionTelemetry(
            accelerometer = listOf(
                Vector3Sample(1_100, 0f, 0f, 9.8f),
                Vector3Sample(1_500, 0f, 0f, 18f),
            ),
        )))
        source.dao().insertSpatialProfiles(listOf(SpatialProfileEntity(
            rideId = rideId,
            distanceBin = 0,
            distanceMeters = 0.0,
            recordedAt = 1_000,
            latitude = 45.5,
            longitude = -73.5,
            altitudeMeters = 10.0,
            speedMps = 5.0,
            accuracyMeters = 3f,
            maximumSampleGapMillis = 1_234,
        )))
        val stopId = source.dao().insertStopEvents(listOf(StopEventEntity(
            rideId = rideId, startedAt = 1_100, endedAt = 12_100,
            latitude = 45.5, longitude = -73.5, accuracyMeters = 3f, durationMillis = 11_000,
        ))).single()
        val trailId = source.dao().insertTrail(TrailEntity(
            name = "Backup trail", state = TrailState.CONFIRMED, canonicalRideId = rideId,
            lengthMeters = 100.0, startMeters = 0.0, endMeters = 100.0,
        ))
        val zoneId = source.dao().insertPauseZone(TrailPauseZoneEntity(
            trailId = trailId, name = "Regroup", startMeters = 45.0, endMeters = 55.0,
            state = PauseZoneState.USER_LOCKED, supportCount = 2, eligiblePassCount = 2,
            confidence = 90, medianPauseMillis = 11_000,
        ))
        val sectionId = source.dao().insertSection(TrailSectionEntity(
            trailId = trailId, name = "Split 1", kind = SectionKind.SPLIT,
            state = SectionState.CONFIRMED, startMeters = 0.0, endMeters = 45.0,
            followingPauseZoneId = zoneId,
        ))
        val passId = source.dao().insertPass(TrailPassEntity(
            trailId = trailId, rideId = rideId, startedAt = 1_000, endedAt = 2_000,
            startMeters = 0.0, endMeters = 100.0, matchConfidence = 90, interrupted = false,
            completeCoverage = true, stopCount = 1, stoppedDurationMillis = 11_000,
        ))
        source.dao().insertStopObservations(listOf(TrailStopObservationEntity(
            trailId = trailId, passId = passId, stopEventId = stopId, distanceMeters = 50.0,
            startMeters = 48.0, endMeters = 52.0, durationMillis = 11_000, confidence = 90,
        )))
        source.dao().insertEfforts(listOf(SectionEffortEntity(
            passId = passId, sectionId = sectionId, elapsedMillis = 1_000,
            entrySpeedMps = 5.0, minimumSpeedMps = 4.0, averageSpeedMps = 5.0,
            exitSpeedMps = 6.0, maximumSpeedMps = 7.0, sampleQuality = 90,
            lateralOffsetMeters = 0.0, lateralUncertaintyMeters = 3.0, valid = true,
            reachedWithoutPriorStop = true,
        )))
        val bytes = ByteArrayOutputStream().also { FlightLogBackup(context, source).export(it) }.toByteArray()

        val destination = Room.inMemoryDatabaseBuilder(context, FlightLogDatabase::class.java).build()
        val backup = FlightLogBackup(context, destination)
        val first = backup.import(ByteArrayInputStream(bytes))
        val importedRideId = destination.dao().allRides().single().id
        assertEquals(1, destination.dao().telemetryChunks(importedRideId).size)
        val importedJump = destination.dao().jumps(importedRideId).single()
        assertEquals(FlightKind.DROP, importedJump.displayFlightKind)
        assertEquals(84, importedJump.flightKindConfidence)
        assertEquals(2, destination.dao().jumpMotionTrace(importedJump.id)?.sampleCount)
        assertEquals(10.0, destination.dao().spatialProfiles(importedRideId).single().altitudeMeters!!, .01)
        assertEquals(1_234L, destination.dao().spatialProfiles(importedRideId).single().maximumSampleGapMillis)
        assertEquals(1, destination.dao().allStopEvents().size)
        assertEquals(PauseZoneState.USER_LOCKED, destination.dao().allPauseZones().single().state)
        assertEquals(zoneId > 0, destination.dao().allSections().single().followingPauseZoneId != null)
        assertEquals(1, destination.dao().allStopObservations().size)
        assertEquals(true, destination.dao().allEfforts().single().reachedWithoutPriorStop)
        val second = backup.import(ByteArrayInputStream(bytes))
        assertEquals(1, first.ridesAdded)
        assertEquals(0, second.ridesAdded)
        assertEquals(1, second.duplicateRides)
        assertEquals(1, destination.dao().allRides().size)
        assertEquals(1, destination.dao().telemetryChunks(importedRideId).size)
        source.close()
        destination.close()
    }
}
