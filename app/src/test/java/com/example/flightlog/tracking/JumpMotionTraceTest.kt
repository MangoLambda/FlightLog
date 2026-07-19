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
            accelerometer = listOf(11_501L, 1_500L, -9_001L, -9_000L, 1_000L, 11_500L).map(::sample),
        )

        val selected = JumpMotionTrace.samples(jump, telemetry)

        assertEquals(listOf(-9_000L, 1_000L, 1_500L, 11_500L), selected.accelerometer.map { it.timestampMillis })
    }

    @Test fun persistentTraceRoundTripsThroughVersionedCodec() {
        val telemetry = MotionTelemetry(accelerometer = listOf(sample(500), sample(600)))

        val trace = JumpMotionTrace.encode(12, telemetry)
        val decoded = JumpMotionTrace.decode(trace)

        assertEquals(12, trace.jumpId)
        assertEquals(telemetry, decoded)
    }

    @Test fun mergeAddsWiderRawContextWithoutDuplicatingStoredSamples() {
        val jump = JumpEventEntity(
            id = 7, rideId = 1, takeoffAt = 20_000, landingAt = 20_500,
            estimatedFlightSeconds = .5, estimatedHeightMeters = .3, estimatedDistanceMeters = 2.0,
            confidence = 80, sensorQuality = SensorQuality.FULL,
        )
        val stored = MotionTelemetry(accelerometer = listOf(sample(19_500), sample(20_000), sample(20_500)))
        val raw = MotionTelemetry(accelerometer = listOf(sample(10_000), sample(20_000), sample(30_500)))

        val merged = JumpMotionTrace.merge(jump, listOf(stored, raw))

        assertEquals(listOf(10_000L, 19_500L, 20_000L, 20_500L, 30_500L), merged.accelerometer.map { it.timestampMillis })
    }

    private fun sample(timestamp: Long) = Vector3Sample(timestamp, 1f, 2f, 3f)
}
