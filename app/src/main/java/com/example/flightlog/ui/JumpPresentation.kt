package com.example.flightlog.ui

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.tracking.MotionSample
import kotlin.math.abs
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

internal data class MapCoordinate(val latitude: Double, val longitude: Double)

internal data class JumpMapCoordinates(
    val center: MapCoordinate?,
    val takeoff: MapCoordinate?,
    val landing: MapCoordinate?,
)

internal fun jumpNumbers(jumps: List<JumpEventEntity>): Map<Long, Int> =
    jumps.sortedWith(compareBy(JumpEventEntity::takeoffAt, JumpEventEntity::id))
        .mapIndexed { index, jump -> jump.id to index + 1 }
        .toMap()

internal fun jumpMapCoordinates(jump: JumpEventEntity, points: List<TrackPointEntity>): JumpMapCoordinates {
    val stored = if (jump.latitude != null && jump.longitude != null) {
        MapCoordinate(jump.latitude, jump.longitude)
    } else {
        null
    }
    val takeoff = points.nearestMapCoordinate(jump.takeoffAt)
    val landing = points.nearestMapCoordinate(jump.landingAt)
    val center = stored ?: midpoint(takeoff, landing) ?: takeoff ?: landing
    return JumpMapCoordinates(center, takeoff ?: stored, landing ?: stored)
}

private fun List<TrackPointEntity>.nearestMapCoordinate(timestamp: Long): MapCoordinate? {
    val point = minByOrNull { abs(it.recordedAt - timestamp) } ?: return null
    if (abs(point.recordedAt - timestamp) > 5_000L) return null
    return MapCoordinate(point.latitude, point.longitude)
}

private fun midpoint(first: MapCoordinate?, second: MapCoordinate?): MapCoordinate? =
    if (first == null || second == null) null
    else MapCoordinate(
        latitude = (first.latitude + second.latitude) / 2.0,
        longitude = (first.longitude + second.longitude) / 2.0,
    )

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
