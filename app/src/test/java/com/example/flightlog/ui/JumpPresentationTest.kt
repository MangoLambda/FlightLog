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

    @Test fun accelerationIsRelativeToTakeoff() {
        val jump = jump(id = 1, takeoffAt = 1_000)
        val acceleration = accelerationTrace(jump, listOf(
            MotionSample(900, 0f, 0f, 9.80665f, 0f, 0f, 0f),
        ))

        assertEquals(-100, acceleration.single().millisFromTakeoff)
        assertEquals(1.0, acceleration.single().magnitudeG, .001)
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

    private fun trackPoint(recordedAt: Long, latitude: Double, longitude: Double) = TrackPointEntity(
        rideId = 1,
        recordedAt = recordedAt,
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = null,
        speedMps = 8.0,
        bearingDegrees = null,
        accuracyMeters = 3f,
    )
}
