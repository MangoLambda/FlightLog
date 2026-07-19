package com.example.flightlog.ui

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.tracking.MotionSample
import kotlin.math.abs
import kotlin.math.sqrt

internal data class AccelerationPoint(
    val millisFromTakeoff: Long,
    val magnitudeG: Double,
)

internal data class GpsSpeedPoint(
    val millisFromTakeoff: Long,
    val speedMps: Double,
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

internal fun accelerationTrace(jump: JumpEventEntity, samples: List<MotionSample>): List<AccelerationPoint> =
    samples.sortedBy { it.timestampMillis }.map { sample ->
        val magnitude = sqrt(
            sample.accelerationX * sample.accelerationX +
                sample.accelerationY * sample.accelerationY +
                sample.accelerationZ * sample.accelerationZ,
        )
        AccelerationPoint(sample.timestampMillis - jump.takeoffAt, magnitude / STANDARD_GRAVITY)
    }

internal fun flightGpsSpeedSamples(jump: JumpEventEntity, points: List<TrackPointEntity>): List<GpsSpeedPoint> =
    points.asSequence()
        .filter { it.recordedAt in jump.takeoffAt..jump.landingAt }
        .sortedBy { it.recordedAt }
        .map { GpsSpeedPoint(it.recordedAt - jump.takeoffAt, it.speedMps) }
        .toList()

internal fun prePumpSpeedMetersPerSecond(
    jump: JumpEventEntity,
    acceleration: List<AccelerationPoint>,
    points: List<TrackPointEntity>,
): Double? {
    val pump = pumpAccelerationPoint(acceleration) ?: return null
    val pumpAt = jump.takeoffAt + pump.millisFromTakeoff
    return points.asSequence()
        .filter { it.recordedAt < pumpAt && pumpAt - it.recordedAt <= 5_000L }
        .maxByOrNull { it.recordedAt }
        ?.speedMps
}

internal fun pumpAccelerationPoint(acceleration: List<AccelerationPoint>): AccelerationPoint? =
    acceleration.filter { it.millisFromTakeoff in -250L..0L }.maxByOrNull { it.magnitudeG }

private const val STANDARD_GRAVITY = 9.80665
