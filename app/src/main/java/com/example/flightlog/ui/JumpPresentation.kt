package com.example.flightlog.ui

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.tracking.JumpMotionTrace
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

internal fun initialReviewJumpId(jumps: List<JumpEventEntity>, selectedJumpId: Long?): Long? {
    if (jumps.any { it.id == selectedJumpId }) return selectedJumpId
    return jumps.asSequence()
        .sortedWith(compareBy<JumpEventEntity>({ it.status != JumpStatus.PENDING }, { it.takeoffAt }, { it.id }))
        .firstOrNull()
        ?.id
}

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

internal fun jumpContextPoints(jump: JumpEventEntity, points: List<TrackPointEntity>): List<TrackPointEntity> {
    val window = (jump.takeoffAt - JumpMotionTrace.PRE_TAKEOFF_MILLIS)..
        (jump.landingAt + JumpMotionTrace.POST_LANDING_MILLIS)
    return points.filter { it.recordedAt in window }.ifEmpty {
        points.sortedBy { abs(it.recordedAt - jump.takeoffAt) }.take(20).sortedBy { it.recordedAt }
    }
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

internal fun filteredPeakGForce(
    jump: JumpEventEntity,
    samples: List<MotionSample>,
    filterMillis: Long = PEAK_G_FILTER_MILLIS,
): Double? {
    val acceleration = accelerationTrace(jump, samples)
        .filter { it.millisFromTakeoff in -250L..(jump.landingAt - jump.takeoffAt + 250L) }
    if (acceleration.size < 3) return null

    var start = 0
    var sum = 0.0
    var peak: Double? = null
    acceleration.forEachIndexed { end, point ->
        sum += point.magnitudeG
        while (
            start < end &&
            point.millisFromTakeoff - acceleration[start + 1].millisFromTakeoff >= filterMillis
        ) {
            sum -= acceleration[start++].magnitudeG
        }
        val span = point.millisFromTakeoff - acceleration[start].millisFromTakeoff
        val count = end - start + 1
        if (count >= 3 && span >= filterMillis) {
            peak = maxOf(peak ?: 0.0, sum / count)
        }
    }
    return peak
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
internal const val PEAK_G_FILTER_MILLIS = 50L
