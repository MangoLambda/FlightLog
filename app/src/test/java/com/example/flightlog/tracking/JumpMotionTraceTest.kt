package com.example.flightlog.tracking

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.domain.SensorQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class JumpMotionTraceTest {
    @Test fun extractsOnlyTheBoundedWindowInTimestampOrder() {
        val jump = JumpEventEntity(
            id = 7, rideId = 1, takeoffAt = 1_000, landingAt = 1_500,
            estimatedFlightSeconds = .5, estimatedHeightMeters = .3, estimatedDistanceMeters = 2.0,
            confidence = 80, sensorQuality = SensorQuality.FULL,
        )
        val telemetry = MotionTelemetry(
            accelerometer = listOf(1_851L, 1_500L, 249L, 250L, 1_000L, 1_850L).map(::sample),
            pressure = listOf(PressureSample(250L, 1_000f), PressureSample(1_851L, 999f)),
        )

        val selected = JumpMotionTrace.samples(jump, telemetry)

        assertEquals(listOf(250L, 1_000L, 1_500L, 1_850L), selected.accelerometer.map { it.timestampMillis })
        assertEquals(listOf(250L), selected.pressure.map { it.timestampMillis })
    }

    @Test fun persistentTraceRoundTripsThroughVersionedCodec() {
        val telemetry = MotionTelemetry(accelerometer = listOf(sample(500), sample(600)))

        val trace = JumpMotionTrace.encode(12, telemetry)
        val decoded = JumpMotionTrace.decode(trace)

        assertEquals(12, trace.jumpId)
        assertEquals(telemetry, decoded)
    }

    private fun sample(timestamp: Long) = Vector3Sample(timestamp, 1f, 2f, 3f)
}
