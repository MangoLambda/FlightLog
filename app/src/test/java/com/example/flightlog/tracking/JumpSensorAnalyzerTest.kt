package com.example.flightlog.tracking

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.SensorQuality
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpSensorAnalyzerTest {
    @Test fun rotationTransformsDeviceAccelerationIntoWorldVertical() {
        val jump = jump()
        val halfAngle = Math.toRadians(90.0) / 2.0
        val telemetry = MotionTelemetry(
            orientationSource = OrientationSource.GAME_ROTATION_VECTOR,
            accelerometer = listOf(Vector3Sample(1_100, 0f, 9.80665f, 0f)),
            orientation = listOf(RotationSample(1_100, sin(halfAngle).toFloat(), 0f, 0f, kotlin.math.cos(halfAngle).toFloat())),
        )

        val analysis = JumpSensorAnalyzer.analyze(jump, telemetry, MountingMode.POCKET)

        assertEquals(1.0, analysis.orientationCoverage, .001)
        assertEquals(0.0, analysis.worldVerticalAcceleration.single().value, .001)
    }

    @Test fun deliveredRateUsesMedianIntervalsAndReportsLargestGap() {
        val rate = JumpSensorAnalyzer.rate(listOf(0, 10, 20, 30, 80), requestedHz = 100)

        assertEquals(100.0, rate.deliveredHz!!, .001)
        assertEquals(50L, rate.maximumGapMillis)
        assertEquals(5, rate.sampleCount)
    }

    private fun jump(): JumpEventEntity {
        val flight = .6
        return JumpEventEntity(
            id = 1,
            rideId = 1,
            takeoffAt = 1_000,
            landingAt = 1_600,
            estimatedFlightSeconds = flight,
            estimatedHeightMeters = 9.80665 * flight.pow(2) / 8.0,
            estimatedDistanceMeters = 3.0,
            confidence = 78,
            sensorQuality = SensorQuality.FULL,
        )
    }

}
