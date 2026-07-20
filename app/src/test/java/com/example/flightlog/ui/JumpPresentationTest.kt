package com.example.flightlog.ui

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.SensorQuality
import com.example.flightlog.tracking.MotionSample
import org.junit.Assert.assertEquals
import org.junit.Test

class JumpPresentationTest {
    @Test fun numberingStartsAtOnePerRideAndIncludesEveryStatus() {
        val jumps = listOf(
            jump(id = 40, takeoffAt = 3_000, status = JumpStatus.REJECTED),
            jump(id = 90, takeoffAt = 1_000, status = JumpStatus.CONFIRMED),
            jump(id = 10, takeoffAt = 2_000, status = JumpStatus.PENDING),
        )

        val numbers = jumpNumbers(jumps)

        assertEquals(1, numbers[90])
        assertEquals(2, numbers[10])
        assertEquals(3, numbers[40])
    }

    @Test fun initialReviewSelectionPrefersTheFirstPendingJump() {
        val jumps = listOf(
            jump(id = 1, takeoffAt = 1_000, status = JumpStatus.CONFIRMED),
            jump(id = 2, takeoffAt = 3_000, status = JumpStatus.PENDING),
            jump(id = 3, takeoffAt = 2_000, status = JumpStatus.PENDING),
        )

        assertEquals(3L, initialReviewJumpId(jumps, selectedJumpId = null))
    }

    @Test fun initialReviewSelectionKeepsAValidExistingSelection() {
        val jumps = listOf(
            jump(id = 1, takeoffAt = 1_000, status = JumpStatus.CONFIRMED),
            jump(id = 2, takeoffAt = 2_000, status = JumpStatus.PENDING),
        )

        assertEquals(1L, initialReviewJumpId(jumps, selectedJumpId = 1))
    }

    @Test fun mapCoordinatesUseGpsTimesForEndpointsAndStoredCoordinateForNumber() {
        val jump = jump(id = 7, takeoffAt = 1_000).copy(
            landingAt = 1_600,
            latitude = 45.51,
            longitude = -73.51,
        )
        val coordinates = jumpMapCoordinates(jump, listOf(
            trackPoint(900, 45.50, -73.50),
            trackPoint(1_700, 45.52, -73.52),
        ))

        assertEquals(45.51, coordinates.center?.latitude ?: 0.0, .0001)
        assertEquals(-73.51, coordinates.center?.longitude ?: 0.0, .0001)
        assertEquals(45.50, coordinates.takeoff?.latitude ?: 0.0, .0001)
        assertEquals(-73.50, coordinates.takeoff?.longitude ?: 0.0, .0001)
        assertEquals(45.52, coordinates.landing?.latitude ?: 0.0, .0001)
        assertEquals(-73.52, coordinates.landing?.longitude ?: 0.0, .0001)
    }

    @Test fun mapCoordinatesCanRecoverMissingStoredCoordinateFromRoute() {
        val jump = jump(id = 8, takeoffAt = 1_000).copy(landingAt = 1_600)
        val coordinates = jumpMapCoordinates(jump, listOf(
            trackPoint(1_000, 45.50, -73.50),
            trackPoint(1_600, 45.52, -73.52),
        ))

        assertEquals(45.51, coordinates.center?.latitude ?: 0.0, .0001)
        assertEquals(-73.51, coordinates.center?.longitude ?: 0.0, .0001)
    }

    @Test fun contextPointsUseTheSensorWindowAroundTheFlight() {
        val jump = jump(id = 8, takeoffAt = 20_000).copy(landingAt = 20_500)
        val points = listOf(
            trackPoint(9_999, 45.0, -73.0),
            trackPoint(10_000, 45.1, -73.1),
            trackPoint(20_000, 45.2, -73.2),
            trackPoint(30_500, 45.3, -73.3),
            trackPoint(30_501, 45.4, -73.4),
        )

        assertEquals(listOf(10_000L, 20_000L, 30_500L), jumpContextPoints(jump, points).map { it.recordedAt })
    }

    @Test fun contextPointsFallBackToNearestRouteFixes() {
        val jump = jump(id = 8, takeoffAt = 100_000)
        val points = listOf(70_000L, 80_000L, 120_000L, 130_000L).mapIndexed { index, timestamp ->
            trackPoint(timestamp, 45.0 + index, -73.0)
        }

        assertEquals(listOf(70_000L, 80_000L, 120_000L, 130_000L), jumpContextPoints(jump, points).map { it.recordedAt })
    }

    @Test fun accelerationIsRelativeToTakeoff() {
        val jump = jump(id = 1, takeoffAt = 1_000)
        val acceleration = accelerationTrace(jump, listOf(
            MotionSample(900, 0f, 0f, 9.80665f, 0f, 0f, 0f),
        ))

        assertEquals(-100, acceleration.single().millisFromTakeoff)
        assertEquals(1.0, acceleration.single().magnitudeG, .001)
    }

    @Test fun peakGForceUsesFiftyMillisecondWindowWithTwentyMillisecondSamples() {
        val jump = jump(id = 1, takeoffAt = 1_000)
        val oneG = 9.80665f
        val samples = listOf(
            MotionSample(990, 0f, 0f, oneG, 0f, 0f, 0f),
            MotionSample(1_010, 0f, 0f, oneG, 0f, 0f, 0f),
            MotionSample(1_030, 0f, 0f, oneG * 10, 0f, 0f, 0f),
            MotionSample(1_050, 0f, 0f, oneG, 0f, 0f, 0f),
        )

        assertEquals(3.25, filteredPeakGForce(jump, samples) ?: 0.0, .001)
    }

    @Test fun takeoffSpeedUsesLatestGpsPointStrictlyBeforePump() {
        val jump = jump(id = 1, takeoffAt = 10_000)
        val acceleration = listOf(
            AccelerationPoint(-200, 1.2),
            AccelerationPoint(-100, 2.8),
            AccelerationPoint(-20, 1.0),
        )
        val points = listOf(
            trackPoint(9_800, 45.50, -73.50, speedMps = 8.0),
            trackPoint(9_900, 45.51, -73.51, speedMps = 12.0),
            trackPoint(10_000, 45.52, -73.52, speedMps = 25.0),
        )

        assertEquals(8.0, prePumpSpeedMetersPerSecond(jump, acceleration, points) ?: 0.0, .001)
    }

    @Test fun takeoffSpeedIsUnavailableWithoutRecentPrePumpGps() {
        val jump = jump(id = 1, takeoffAt = 10_000)
        val acceleration = listOf(AccelerationPoint(-100, 2.8))
        val points = listOf(trackPoint(4_000, 45.50, -73.50, speedMps = 8.0))

        assertEquals(null, prePumpSpeedMetersPerSecond(jump, acceleration, points))
    }

    @Test fun pumpMarkerIgnoresEarlierAccelerationSpikes() {
        val acceleration = listOf(
            AccelerationPoint(-5_000, 8.0),
            AccelerationPoint(-200, 2.8),
            AccelerationPoint(-20, 1.0),
        )

        assertEquals(-200L, pumpAccelerationPoint(acceleration)?.millisFromTakeoff)
    }

    @Test fun flightGpsSpeedsIncludeEverySampleFromTakeoffThroughLanding() {
        val jump = jump(id = 1, takeoffAt = 10_000)
        val points = listOf(
            trackPoint(10_401, 45.0, -73.0, speedMps = 9.0),
            trackPoint(9_999, 45.0, -73.0, speedMps = 1.0),
            trackPoint(10_200, 45.0, -73.0, speedMps = 7.0),
            trackPoint(10_000, 45.0, -73.0, speedMps = 6.0),
            trackPoint(10_400, 45.0, -73.0, speedMps = 8.0),
        )

        val samples = flightGpsSpeedSamples(jump, points)

        assertEquals(listOf(0L, 200L, 400L), samples.map { it.millisFromTakeoff })
        assertEquals(listOf(6.0, 7.0, 8.0), samples.map { it.speedMps })
    }

    private fun jump(id: Long, takeoffAt: Long, status: JumpStatus = JumpStatus.PENDING) = JumpEventEntity(
        id = id,
        rideId = 1,
        takeoffAt = takeoffAt,
        landingAt = takeoffAt + 400,
        estimatedFlightSeconds = .4,
        estimatedHeightMeters = .2,
        estimatedDistanceMeters = 2.0,
        confidence = 80,
        status = status,
        sensorQuality = SensorQuality.FULL,
    )

    private fun trackPoint(recordedAt: Long, latitude: Double, longitude: Double, speedMps: Double = 8.0) = TrackPointEntity(
        rideId = 1,
        recordedAt = recordedAt,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = null,
        speedMps = speedMps,
        bearingDegrees = null,
        accuracyMeters = 3f,
    )
}
