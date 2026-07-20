package com.example.flightlog.data

import com.example.flightlog.domain.SensorQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureReviewEvidenceTest {
    @Test fun routeUsesTenSecondContext() {
        val jump = jump(20_000).copy(landingAt = 20_500)
        val points = listOf(9_999L, 10_000L, 20_000L, 30_500L, 30_501L).map(::point)
        assertEquals(listOf(10_000L, 20_000L, 30_500L), featureRunRoutePoints(jump, points).map { it.recordedAt })
    }

    @Test fun routeFallsBackToNearestCompactedPoints() {
        val points = listOf(70_000L, 80_000L, 120_000L, 130_000L).map(::point)
        assertEquals(points, featureRunRoutePoints(jump(100_000), points))
    }

    private fun jump(takeoff: Long) = JumpEventEntity(
        id = 1, rideId = 1, takeoffAt = takeoff, landingAt = takeoff + 500,
        estimatedFlightSeconds = .5, estimatedHeightMeters = .3, estimatedDistanceMeters = 2.0,
        confidence = 90, sensorQuality = SensorQuality.FULL,
    )

    private fun point(timestamp: Long) = TrackPointEntity(
        rideId = 1, recordedAt = timestamp, latitude = 45.0, longitude = -73.0,
        altitudeMeters = null, speedMps = 5.0, bearingDegrees = null, accuracyMeters = 2f,
    )
}
