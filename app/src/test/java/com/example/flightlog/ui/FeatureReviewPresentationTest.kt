package com.example.flightlog.ui

import com.example.flightlog.data.FeatureObservationEntity
import com.example.flightlog.data.FeatureRunEvidence
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.domain.FeatureAssignmentState
import com.example.flightlog.domain.SensorQuality
import com.example.flightlog.tracking.MotionTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureReviewPresentationTest {
    @Test fun defaultSelectionUsesThreeNewestReferences() {
        val runs = (1L..5L).map(::run)
        assertEquals(setOf(1L, 2L, 3L), reconcileFeatureReferenceSelection(runs, emptySet()))
    }

    @Test fun selectionSurvivesRefreshDropsMissingAndCapsAtSix() {
        val runs = (2L..8L).map(::run)
        assertEquals(setOf(2L, 3L, 4L, 5L, 6L, 7L), reconcileFeatureReferenceSelection(runs, (1L..8L).toSet()))
    }

    @Test fun bearingDifferenceWrapsAcrossNorth() {
        assertEquals(20.0, bearingDifferenceDegrees(350.0, 10.0) ?: -1.0, 0.001)
        assertEquals(null, bearingDifferenceDegrees(null, 10.0))
    }

    @Test fun takeoffDistanceHandlesIdenticalAndNearbyCoordinates() {
        val first = run(1, 45.0, -73.0)
        val same = run(2, 45.0, -73.0)
        val nearby = run(3, 45.0001, -73.0)
        assertEquals(0.0, takeoffDistanceMeters(first, same), 0.001)
        assertTrue(takeoffDistanceMeters(first, nearby) in 10.0..12.5)
    }

    private fun run(id: Long, latitude: Double = 45.0, longitude: Double = -73.0): FeatureRunEvidence {
        val jump = JumpEventEntity(
            id = id, rideId = id, takeoffAt = id * 1_000, landingAt = id * 1_000 + 500,
            estimatedFlightSeconds = .5, estimatedHeightMeters = .3, estimatedDistanceMeters = 2.0,
            confidence = 90, sensorQuality = SensorQuality.FULL,
        )
        return FeatureRunEvidence(
            observation = FeatureObservationEntity(
                id = id, jumpId = id, featureId = 1, assignmentState = FeatureAssignmentState.MATCHED,
                matchConfidence = 90, latitude = latitude, longitude = longitude, gpsAccuracyMeters = 2f,
                airtimeSeconds = .5, heightMeters = .3, distanceMeters = 2.0,
            ),
            jump = jump,
            ride = RideEntity(id = id, startedAt = id * 1_000),
            routePoints = emptyList(),
            motion = MotionTelemetry.EMPTY,
            sensorTraceAvailable = false,
        )
    }
}
