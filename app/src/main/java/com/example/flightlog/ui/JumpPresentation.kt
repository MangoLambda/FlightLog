package com.example.flightlog.ui

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.tracking.MotionSample
import kotlin.math.sqrt

internal data class FlightArcPoint(
    val progress: Double,
    val elapsedSeconds: Double,
    val distanceMeters: Double,
    val heightMeters: Double,
)

internal data class AccelerationPoint(
    val millisFromTakeoff: Long,
    val magnitudeG: Double,
)

internal fun jumpNumbers(jumps: List<JumpEventEntity>): Map<Long, Int> =
    jumps.sortedWith(compareBy(JumpEventEntity::takeoffAt, JumpEventEntity::id))
        .mapIndexed { index, jump -> jump.id to index + 1 }
        .toMap()

internal fun flightArc(
    flightSeconds: Double,
    heightMeters: Double,
    distanceMeters: Double,
    pointCount: Int = 61,
): List<FlightArcPoint> {
    val flight = flightSeconds.takeIf { it.isFinite() }?.coerceIn(0.0, 2.5) ?: 0.0
    val height = heightMeters.takeIf { it.isFinite() }?.coerceIn(0.0, 8.0) ?: 0.0
    val distance = distanceMeters.takeIf { it.isFinite() }?.coerceIn(0.0, 50.0) ?: 0.0
    val count = pointCount.coerceAtLeast(2)
    return List(count) { index ->
        val progress = index.toDouble() / (count - 1)
        FlightArcPoint(
            progress = progress,
            elapsedSeconds = flight * progress,
            distanceMeters = distance * progress,
            heightMeters = 4.0 * height * progress * (1.0 - progress),
        )
    }
}

internal fun accelerationTrace(jump: JumpEventEntity, samples: List<MotionSample>): List<AccelerationPoint> =
    samples.sortedBy { it.timestampMillis }.map { sample ->
        val magnitude = sqrt(
            sample.accelerationX * sample.accelerationX +
                sample.accelerationY * sample.accelerationY +
                sample.accelerationZ * sample.accelerationZ,
        )
        AccelerationPoint(sample.timestampMillis - jump.takeoffAt, magnitude / STANDARD_GRAVITY)
    }

private const val STANDARD_GRAVITY = 9.80665
