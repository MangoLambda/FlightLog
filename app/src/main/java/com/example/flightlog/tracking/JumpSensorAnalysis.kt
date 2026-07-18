package com.example.flightlog.tracking

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.SensorQuality
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.pow
import kotlin.math.sqrt

object SensorSamplingProfile {
    const val MOTION_HZ = 100
    const val MOTION_PERIOD_US = 1_000_000 / MOTION_HZ
}

data class SensorRateSummary(
    val requestedHz: Int,
    val deliveredHz: Double?,
    val sampleCount: Int,
    val maximumGapMillis: Long?,
)

data class TimedValue(val timestampMillis: Long, val value: Double)

data class JumpSensorAnalysis(
    val accelerometerRate: SensorRateSummary,
    val gyroscopeRate: SensorRateSummary,
    val orientationRate: SensorRateSummary,
    val orientationSource: OrientationSource,
    val orientationCoverage: Double,
    val maximumRotationDegrees: Double?,
    val airtimeHeightMeters: Double,
    val estimatedConfidence: Int,
    val worldVerticalAcceleration: List<TimedValue>,
)

object JumpSensorAnalyzer {
    private const val STANDARD_GRAVITY = 9.80665
    private const val MAX_ORIENTATION_SUPPORT_MILLIS = 50L

    fun analyze(
        jump: JumpEventEntity,
        telemetry: MotionTelemetry,
        mountingMode: MountingMode?,
    ): JumpSensorAnalysis {
        val acceleration = telemetry.accelerometer.sortedBy { it.timestampMillis }
        val gyroscope = telemetry.gyroscope.sortedBy { it.timestampMillis }
        val orientation = telemetry.orientation.sortedBy { it.timestampMillis }
        val accelerationRate = rate(acceleration.map { it.timestampMillis }, SensorSamplingProfile.MOTION_HZ)
        val gyroscopeRate = rate(gyroscope.map { it.timestampMillis }, SensorSamplingProfile.MOTION_HZ)
        val orientationRate = rate(orientation.map { it.timestampMillis }, SensorSamplingProfile.MOTION_HZ)

        val oriented = acceleration.mapNotNull { sample ->
            interpolateOrientation(sample.timestampMillis, orientation)?.let { quaternion ->
                val world = quaternion.rotate(sample.x.toDouble(), sample.y.toDouble(), sample.z.toDouble())
                TimedValue(sample.timestampMillis, world.third - STANDARD_GRAVITY)
            }
        }
        val orientationCoverage = if (acceleration.isEmpty()) 0.0 else oriented.size.toDouble() / acceleration.size
        val maximumRotation = if (orientationCoverage >= 0.8) maximumRotationDegrees(jump, orientation) else null
        val airtimeHeight = (STANDARD_GRAVITY * jump.estimatedFlightSeconds.coerceIn(0.0, 2.5).pow(2) / 8.0)
            .coerceIn(0.0, 8.0)
        val confidence = confidence(
            jump = jump,
            mountingMode = mountingMode,
            acceleration = acceleration,
            gyroscope = gyroscope,
            oriented = oriented,
            orientationCoverage = orientationCoverage,
            maximumRotationDegrees = maximumRotation,
        )
        return JumpSensorAnalysis(
            accelerometerRate = accelerationRate,
            gyroscopeRate = gyroscopeRate,
            orientationRate = orientationRate,
            orientationSource = telemetry.orientationSource,
            orientationCoverage = orientationCoverage,
            maximumRotationDegrees = maximumRotation,
            airtimeHeightMeters = airtimeHeight,
            estimatedConfidence = confidence,
            worldVerticalAcceleration = oriented,
        )
    }

    private fun confidence(
        jump: JumpEventEntity,
        mountingMode: MountingMode?,
        acceleration: List<Vector3Sample>,
        gyroscope: List<Vector3Sample>,
        oriented: List<TimedValue>,
        orientationCoverage: Double,
        maximumRotationDegrees: Double?,
    ): Int {
        val base = when (jump.sensorQuality) {
            SensorQuality.FULL -> 78
            SensorQuality.ACCELEROMETER_ONLY -> 58
            SensorQuality.DEGRADED -> 35
        }
        val landingMagnitude = acceleration.minByOrNull { abs(it.timestampMillis - jump.landingAt) }?.let(::magnitude)
        val landingAdjustment = when {
            landingMagnitude == null -> 0
            landingMagnitude >= 20.0 -> 4
            landingMagnitude < 16.0 -> -5
            else -> 0
        }
        val mountingAdjustment = if (mountingMode == MountingMode.BIKE_MOUNTED) 5 else 0
        val rotationPenalty = if (orientationCoverage >= 0.8 && maximumRotationDegrees != null) {
            val threshold = if (mountingMode == MountingMode.BIKE_MOUNTED) 90.0 else 60.0
            ((maximumRotationDegrees - threshold).coerceAtLeast(0.0) / 8.0).toInt().coerceAtMost(15)
        } else {
            val maximumGyro = gyroscope.asSequence()
                .filter { it.timestampMillis in jump.takeoffAt..jump.landingAt }
                .maxOfOrNull(::magnitude) ?: 0.0
            ((maximumGyro - 8.0).coerceAtLeast(0.0) * 2).toInt().coerceAtMost(20)
        }
        val verticalAdjustment = if (orientationCoverage >= 0.8) {
            val pump = oriented.filter { it.timestampMillis in (jump.takeoffAt - 250)..jump.takeoffAt }
                .maxOfOrNull { it.value } ?: Double.NEGATIVE_INFINITY
            val landing = oriented.filter { it.timestampMillis in jump.landingAt..(jump.landingAt + 150) }
                .maxOfOrNull { it.value } ?: Double.NEGATIVE_INFINITY
            if (pump >= 2.0 && landing >= 5.0) 3 else 0
        } else {
            0
        }
        return (base + landingAdjustment + mountingAdjustment - rotationPenalty + verticalAdjustment)
            .coerceIn(15, 95)
    }

    internal fun rate(timestamps: List<Long>, requestedHz: Int): SensorRateSummary {
        val unique = timestamps.distinct().sorted()
        val gaps = unique.zipWithNext { a, b -> b - a }.filter { it > 0 }
        return SensorRateSummary(
            requestedHz = requestedHz,
            deliveredHz = gaps.medianMillis()?.takeIf { it > 0 }?.let { 1_000.0 / it },
            sampleCount = unique.size,
            maximumGapMillis = gaps.maxOrNull(),
        )
    }

    private fun interpolateOrientation(timestamp: Long, samples: List<RotationSample>): Quaternion? {
        if (samples.isEmpty()) return null
        val insertion = samples.binarySearchBy(timestamp) { it.timestampMillis }
        if (insertion >= 0) return samples[insertion].quaternion()
        val afterIndex = -insertion - 1
        val before = samples.getOrNull(afterIndex - 1)
        val after = samples.getOrNull(afterIndex)
        if (before == null) return after?.takeIf { abs(it.timestampMillis - timestamp) <= MAX_ORIENTATION_SUPPORT_MILLIS }?.quaternion()
        if (after == null) return before.takeIf { abs(it.timestampMillis - timestamp) <= MAX_ORIENTATION_SUPPORT_MILLIS }?.quaternion()
        if (timestamp - before.timestampMillis > MAX_ORIENTATION_SUPPORT_MILLIS ||
            after.timestampMillis - timestamp > MAX_ORIENTATION_SUPPORT_MILLIS
        ) return null
        val progress = (timestamp - before.timestampMillis).toDouble() /
            (after.timestampMillis - before.timestampMillis).coerceAtLeast(1)
        return Quaternion.slerp(before.quaternion(), after.quaternion(), progress)
    }

    private fun maximumRotationDegrees(jump: JumpEventEntity, samples: List<RotationSample>): Double? {
        val reference = interpolateOrientation(jump.takeoffAt, samples) ?: return null
        return samples.asSequence().filter { it.timestampMillis in jump.takeoffAt..jump.landingAt }
            .map { reference.angleDegrees(it.quaternion()) }
            .maxOrNull()
    }

    private data class Quaternion(val x: Double, val y: Double, val z: Double, val w: Double) {
        fun normalized(): Quaternion {
            val norm = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 0 } ?: return IDENTITY
            return Quaternion(x / norm, y / norm, z / norm, w / norm)
        }

        fun rotate(xValue: Double, yValue: Double, zValue: Double): Triple<Double, Double, Double> {
            val q = normalized()
            val tx = 2.0 * (q.y * zValue - q.z * yValue)
            val ty = 2.0 * (q.z * xValue - q.x * zValue)
            val tz = 2.0 * (q.x * yValue - q.y * xValue)
            return Triple(
                xValue + q.w * tx + q.y * tz - q.z * ty,
                yValue + q.w * ty + q.z * tx - q.x * tz,
                zValue + q.w * tz + q.x * ty - q.y * tx,
            )
        }

        fun angleDegrees(other: Quaternion): Double {
            val a = normalized()
            val b = other.normalized()
            val dot = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w).coerceIn(0.0, 1.0)
            return Math.toDegrees(2.0 * acos(dot))
        }

        companion object {
            val IDENTITY = Quaternion(0.0, 0.0, 0.0, 1.0)

            fun slerp(start: Quaternion, end: Quaternion, progress: Double): Quaternion {
                val a = start.normalized()
                var b = end.normalized()
                var dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
                if (dot < 0.0) {
                    b = Quaternion(-b.x, -b.y, -b.z, -b.w)
                    dot = -dot
                }
                if (dot > 0.9995) return Quaternion(
                    a.x + progress * (b.x - a.x),
                    a.y + progress * (b.y - a.y),
                    a.z + progress * (b.z - a.z),
                    a.w + progress * (b.w - a.w),
                ).normalized()
                val theta = acos(dot.coerceIn(-1.0, 1.0))
                val sinTheta = kotlin.math.sin(theta)
                val startWeight = kotlin.math.sin((1.0 - progress) * theta) / sinTheta
                val endWeight = kotlin.math.sin(progress * theta) / sinTheta
                return Quaternion(
                    a.x * startWeight + b.x * endWeight,
                    a.y * startWeight + b.y * endWeight,
                    a.z * startWeight + b.z * endWeight,
                    a.w * startWeight + b.w * endWeight,
                )
            }
        }
    }

    private fun RotationSample.quaternion() = Quaternion(x.toDouble(), y.toDouble(), z.toDouble(), w.toDouble()).normalized()
    private fun magnitude(sample: Vector3Sample): Double = sqrt(
        sample.x.toDouble().pow(2) + sample.y.toDouble().pow(2) + sample.z.toDouble().pow(2),
    )
    private fun List<Long>.medianMillis(): Double? = if (isEmpty()) null else sorted().let { values ->
        if (values.size % 2 == 1) values[values.size / 2].toDouble()
        else (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
    }
}
