package com.example.flightlog.ui

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.SensorQuality
import com.example.flightlog.tracking.MotionSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun modeledArcStartsAndEndsOnGroundWithMidpointAtRequestedHeight() {
        val arc = flightArc(flightSeconds = .8, heightMeters = 1.5, distanceMeters = 6.0, pointCount = 3)

        assertEquals(0.0, arc.first().heightMeters, .0001)
        assertEquals(1.5, arc[1].heightMeters, .0001)
        assertEquals(0.0, arc.last().heightMeters, .0001)
        assertEquals(6.0, arc.last().distanceMeters, .0001)
        assertEquals(.8, arc.last().elapsedSeconds, .0001)
    }

    @Test fun invalidArcInputsAreBoundedAndAccelerationIsRelativeToTakeoff() {
        val arc = flightArc(Double.NaN, 99.0, -4.0, pointCount = 1)
        val jump = jump(id = 1, takeoffAt = 1_000)
        val acceleration = accelerationTrace(jump, listOf(
            MotionSample(900, 0f, 0f, 9.80665f, 0f, 0f, 0f),
        ))

        assertEquals(2, arc.size)
        assertTrue(arc.all { it.heightMeters in 0.0..8.0 && it.distanceMeters == 0.0 })
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
}
