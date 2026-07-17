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
    @Test fun cleanOrientationAndPressureProduceConservativeFusedHeight() {
        val jump = jump()
        val analysis = JumpSensorAnalyzer.analyze(jump, telemetry(), MountingMode.POCKET)

        assertEquals(JumpEstimateMethod.AIRTIME_BAROMETER, analysis.estimateMethod)
        assertEquals(PressureQuality.ACCEPTED, analysis.pressureQuality)
        assertEquals(100.0, analysis.accelerometerRate.deliveredHz!!, .01)
        assertEquals(25.0, analysis.pressureRate.deliveredHz!!, .01)
        assertEquals(1.0, analysis.orientationCoverage, .001)
        assertEquals(0.5, analysis.barometricHeightMeters!!, .04)
        assertEquals(analysis.airtimeHeightMeters * .75 + analysis.barometricHeightMeters!! * .25, analysis.fusedHeightMeters, .0001)
        assertEquals(90, analysis.estimatedConfidence)
    }

    @Test fun noisyPressureFallsBackToAirtimeWithoutConfidencePenalty() {
        val jump = jump()
        val noisy = telemetry().let { telemetry ->
            val baselineTimestamps = telemetry.pressure
                .filter { it.timestampMillis in 400L..900L }
                .map { it.timestampMillis }
            val midpoint = baselineTimestamps.size / 2
            val offsetByTimestamp = baselineTimestamps.mapIndexed { index, timestamp ->
                timestamp to when {
                    index < midpoint -> -.1f
                    index == midpoint -> 0f
                    else -> .1f
                }
            }.toMap()
            telemetry.copy(pressure = telemetry.pressure.map { sample ->
                offsetByTimestamp[sample.timestampMillis]?.let { offset ->
                    sample.copy(pressureHpa = sample.pressureHpa + offset)
                } ?: sample
            })
        }

        val analysis = JumpSensorAnalyzer.analyze(jump, noisy, MountingMode.POCKET)

        assertEquals(PressureQuality.NOISY_BASELINE, analysis.pressureQuality)
        assertEquals(JumpEstimateMethod.AIRTIME, analysis.estimateMethod)
        assertEquals(85, analysis.estimatedConfidence)
        assertEquals(analysis.airtimeHeightMeters, analysis.fusedHeightMeters, .0001)
    }

    @Test fun cleanPressureThatDisagreesDoesNotChangeHeightAndLowersConfidence() {
        val jump = jump()
        val conflicting = telemetry(apexHeightMeters = 2.0)

        val analysis = JumpSensorAnalyzer.analyze(jump, conflicting, MountingMode.POCKET)

        assertEquals(PressureQuality.DISAGREES, analysis.pressureQuality)
        assertEquals(JumpEstimateMethod.AIRTIME, analysis.estimateMethod)
        assertEquals(80, analysis.estimatedConfidence)
    }

    @Test fun slowPressureIsRejected() {
        val jump = jump()
        val slow = telemetry().let { telemetry ->
            telemetry.copy(pressure = telemetry.pressure.filterIndexed { index, _ -> index % 3 == 0 })
        }

        assertEquals(
            PressureQuality.RATE_TOO_LOW,
            JumpSensorAnalyzer.analyze(jump, slow, MountingMode.POCKET).pressureQuality,
        )
    }

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

    private fun telemetry(apexHeightMeters: Double = .5): MotionTelemetry {
        val start = 250L
        val end = 1_950L
        val accelerometer = (start..end step 10).map { timestamp ->
            val z = when {
                timestamp in 850L..1_000L -> 12.5f
                timestamp in 1_010L..1_590L -> 0f
                timestamp in 1_600L..1_750L -> 20f
                else -> 9.80665f
            }
            Vector3Sample(timestamp, 0f, 0f, z)
        }
        val orientation = (start..end step 10).map { RotationSample(it, 0f, 0f, 0f, 1f) }
        val gyroscope = (start..end step 10).map { Vector3Sample(it, 0f, 0f, 0f) }
        val pressure = (400L..1_960L step 40).map { timestamp ->
            val progress = ((timestamp - 1_000).toDouble() / 600.0).coerceIn(0.0, 1.0)
            val height = if (timestamp in 1_000L..1_600L) apexHeightMeters * 4.0 * progress * (1.0 - progress) else 0.0
            PressureSample(timestamp, pressureForHeight(1_000.0, height).toFloat())
        }
        return MotionTelemetry(
            orientationSource = OrientationSource.GAME_ROTATION_VECTOR,
            accelerometer = accelerometer,
            gyroscope = gyroscope,
            orientation = orientation,
            pressure = pressure,
        )
    }

    private fun pressureForHeight(baselineHpa: Double, heightMeters: Double): Double =
        baselineHpa * (1.0 - heightMeters / 44_330.0).pow(1.0 / 0.190294957)
}
