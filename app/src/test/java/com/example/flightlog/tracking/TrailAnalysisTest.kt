package com.example.flightlog.tracking

import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.RoughnessKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailAnalysisTest {
    @Test fun matchesSameDirectionDespiteImpreciseRideBoundaries() {
        val canonical = profileLine(rideId = 1, count = 120)
        val leading = profileLine(rideId = 2, count = 20, latitudeStart = 45.4998)
        val repeated = profileLine(rideId = 2, count = 120, timeStart = 10_000)
        val trailing = profileLine(rideId = 2, count = 20, latitudeStart = 45.5012, timeStart = 40_000)
        assertNotNull(TrailAnalysis.match(leading + repeated + trailing, canonical))
        assertNull(TrailAnalysis.match(repeated.reversed(), canonical))
    }

    @Test fun interruptedSectionIsInvalidButNeighborCanRemainValid() {
        val canonical = profileLine(rideId = 1, count = 80)
        val candidate = profileLine(rideId = 2, count = 80).mapIndexed { index, point ->
            if (index == 30) point.copy(observedSpanMillis = 12_000) else point
        }
        val normalized = candidate.zip(canonical)
        val stopped = TrailSectionEntity(id = 1, trailId = 1, name = "Stopped", kind = SectionKind.MANUAL, startMeters = 100.0, endMeters = 200.0)
        val clear = TrailSectionEntity(id = 2, trailId = 1, name = "Clear", kind = SectionKind.MANUAL, startMeters = 250.0, endMeters = 350.0)
        assertFalse(TrailAnalysis.effort(1, stopped, normalized)!!.valid)
        assertTrue(TrailAnalysis.effort(1, clear, normalized)!!.valid)
    }

    @Test fun pocketAndMountedSignalsRemainSeparateAndDifferentlyQualified() {
        val points = (0 until 80).map { index ->
            com.example.flightlog.data.TrackPointEntity(
                rideId = 1, recordedAt = index * 100L, latitude = 45.5 + index * .00001,
                longitude = -73.5, altitudeMeters = null, speedMps = 8.0,
                bearingDegrees = 0f, accuracyMeters = 3f,
            )
        }
        val motion = (0 until 400).map { index ->
            MotionSample(index * 20L, if (index % 2 == 0) 0f else 6f, 0f, 9.8f, 0f, 0f, 1f)
        }
        val mounted = TrailAnalysis.spatialProfiles(1, points, motion, MountingMode.BIKE_MOUNTED).first { it.roughnessScore != null }
        val pocket = TrailAnalysis.spatialProfiles(2, points, motion, MountingMode.POCKET).first { it.roughnessScore != null }
        assertTrue(mounted.roughnessKind == RoughnessKind.BIKE_ROUGHNESS)
        assertTrue(pocket.roughnessKind == RoughnessKind.RIDER_DISTURBANCE)
        assertTrue(mounted.roughnessConfidence!! > pocket.roughnessConfidence!!)
        assertTrue(mounted.roughnessScore!! > pocket.roughnessScore!!)
    }

    private fun profileLine(
        rideId: Long, count: Int, latitudeStart: Double = 45.5, timeStart: Long = 0,
    ) = (0 until count).map { index ->
        SpatialProfileEntity(
            rideId = rideId, distanceBin = index, distanceMeters = index * 5.0,
            recordedAt = timeStart + index * 500L,
            latitude = latitudeStart + index * 0.00001, longitude = -73.5,
            speedMps = 10.0, accuracyMeters = 3f,
        )
    }
}
