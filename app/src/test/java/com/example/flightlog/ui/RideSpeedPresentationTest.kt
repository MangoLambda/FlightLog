package com.example.flightlog.ui

import com.example.flightlog.averageMovingSpeedMps
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.SensorQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideSpeedPresentationTest {
    @Test fun `new jumps are confirmed by default`() {
        val jump = JumpEventEntity(
            rideId = 1, takeoffAt = 1_000, landingAt = 1_500,
            estimatedFlightSeconds = .5, estimatedHeightMeters = .3,
            estimatedDistanceMeters = 4.0, confidence = 80,
            sensorQuality = SensorQuality.FULL,
        )

        assertEquals(JumpStatus.CONFIRMED, jump.status)
    }

    @Test fun `speed colors clamp to fixed endpoints`() {
        assertEquals("#D9F7FF", speedColorHex(0.0))
        assertEquals("#D9F7FF", speedColorHex(5.0))
        assertEquals("#2B0505", speedColorHex(70.0))
        assertEquals("#2B0505", speedColorHex(100.0))
    }

    @Test fun `speed colors interpolate between stops`() {
        assertEquals("#78C1FF", speedColorHex(10.0))
    }

    @Test fun `fastest route segments include ties`() {
        val points = listOf(2.0, 5.0, 5.0, 3.0).mapIndexed { index, speed -> point(index, speed) }
        assertEquals(setOf(0, 1), fastestSegmentIndexes(points))
        assertEquals(emptySet<Int>(), fastestSegmentIndexes(points.take(1)))
    }

    @Test fun `average speed uses distance and moving time`() {
        assertEquals(5.0, averageMovingSpeedMps(1_500.0, 300_000L)!!, 0.0)
        assertNull(averageMovingSpeedMps(1_500.0, 0L))
    }

    private fun point(index: Int, speed: Double) = TrackPointEntity(
        rideId = 1, recordedAt = index.toLong(), latitude = 45.0 + index, longitude = -73.0,
        altitudeMeters = null, speedMps = speed, bearingDegrees = null, accuracyMeters = 3f,
    )
}
