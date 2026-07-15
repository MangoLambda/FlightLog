package com.example.flightlog.tracking

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.domain.JumpEstimate
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.RideTotals
import com.example.flightlog.domain.SensorQuality
import java.time.Instant
import java.time.ZoneId
import kotlin.math.*

data class LocationSample(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMps: Double,
)

object RideMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val GRAVITY = 9.80665

    fun isUsableLocation(previous: LocationSample?, next: LocationSample): Boolean {
        if (next.accuracyMeters > 35f || next.latitude !in -90.0..90.0 || next.longitude !in -180.0..180.0) return false
        if (next.speedMps !in 0.0..45.0) return false
        if (previous == null) return true
        val seconds = (next.timestamp - previous.timestamp) / 1_000.0
        if (seconds <= 0.0) return false
        val derivedSpeed = distanceMeters(previous, next) / seconds
        return derivedSpeed <= 45.0
    }

    fun distanceMeters(a: LocationSample, b: LocationSample): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun smoothedSpeedMetersPerSecond(samples: Collection<Double>): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    fun effectiveSpeedMetersPerSecond(
        reportedSpeedMps: Double?,
        previous: LocationSample?,
        next: LocationSample,
    ): Double {
        if (reportedSpeedMps != null && reportedSpeedMps.isFinite() && reportedSpeedMps in 0.0..45.0) {
            return reportedSpeedMps
        }
        if (previous == null) return 0.0
        val seconds = (next.timestamp - previous.timestamp) / 1_000.0
        if (seconds <= 0.0) return 0.0
        return (distanceMeters(previous, next) / seconds).coerceIn(0.0, 45.0)
    }

    fun jumpEstimate(flightSeconds: Double, horizontalSpeedMps: Double, confidence: Int): JumpEstimate {
        val safeFlight = flightSeconds.coerceIn(0.0, 2.5)
        return JumpEstimate(
            flightTimeSeconds = safeFlight,
            heightMeters = GRAVITY * safeFlight * safeFlight / 8.0,
            distanceMeters = horizontalSpeedMps.coerceIn(0.0, 45.0) * safeFlight,
            confidence = confidence.coerceIn(0, 100),
        )
    }

    fun aggregate(
        rides: List<RideEntity>,
        jumps: List<JumpEventEntity>,
        fromInclusive: Long = Long.MIN_VALUE,
        toExclusive: Long = Long.MAX_VALUE,
    ): RideTotals {
        val selectedRides = rides.filter { it.startedAt in fromInclusive until toExclusive }
        val rideIds = selectedRides.mapTo(hashSetOf()) { it.id }
        val selectedJumps = jumps.filter { it.rideId in rideIds }
        val confirmed = selectedJumps.filter { it.status == JumpStatus.CONFIRMED }
        return RideTotals(
            rides = selectedRides.size,
            distanceMeters = selectedRides.sumOf { it.distanceMeters },
            movingTimeMillis = selectedRides.sumOf { it.movingTimeMillis },
            confirmedJumps = confirmed.size,
            pendingJumps = selectedJumps.count { it.status == JumpStatus.PENDING },
            rejectedJumps = selectedJumps.count { it.status == JumpStatus.REJECTED },
            flightTimeSeconds = confirmed.sumOf { it.displayFlightSeconds },
            jumpedDistanceMeters = confirmed.sumOf { it.displayDistanceMeters },
        )
    }

    fun calendarYearBounds(now: Long, zoneId: ZoneId = ZoneId.systemDefault()): LongRange {
        val year = Instant.ofEpochMilli(now).atZone(zoneId).year
        val start = java.time.LocalDate.of(year, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endExclusive = java.time.LocalDate.of(year + 1, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return start until endExclusive
    }
}

class JumpDetector(
    private val sensorQuality: SensorQuality,
    private val mountingMode: MountingMode = MountingMode.POCKET,
    private val minimumJumpHeightMeters: Double = RecordingSettings.DEFAULT_POCKET_MINIMUM_HEIGHT_METERS.toDouble(),
    private val onJump: (takeoffNanos: Long, landingNanos: Long, confidence: Int) -> Unit,
) {
    private var lowAccelerationStarted: Long? = null
    private var takeoffNanos: Long? = null
    private var lastGyroMagnitude = 0.0
    private var maxFlightGyroMagnitude = 0.0

    fun onGyroscope(x: Float, y: Float, z: Float) {
        lastGyroMagnitude = sqrt((x * x + y * y + z * z).toDouble())
        if (takeoffNanos != null) {
            maxFlightGyroMagnitude = max(maxFlightGyroMagnitude, lastGyroMagnitude)
        }
    }

    fun onAcceleration(timestampNanos: Long, x: Float, y: Float, z: Float) {
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
        val activeTakeoff = takeoffNanos
        if (activeTakeoff == null) {
            val takeoffThreshold = if (mountingMode == MountingMode.POCKET) 6.0 else 4.5
            val takeoffWindowNanos = if (mountingMode == MountingMode.POCKET) 80_000_000L else 60_000_000L
            if (magnitude < takeoffThreshold) {
                val started = lowAccelerationStarted ?: timestampNanos.also { lowAccelerationStarted = it }
                if (timestampNanos - started >= takeoffWindowNanos) {
                    takeoffNanos = started
                    maxFlightGyroMagnitude = lastGyroMagnitude
                }
            } else {
                lowAccelerationStarted = null
            }
            return
        }

        val flightNanos = timestampNanos - activeTakeoff
        val landingThreshold = if (mountingMode == MountingMode.POCKET) 13.0 else 15.0
        if (magnitude > landingThreshold && flightNanos in 150_000_000L..2_500_000_000L) {
            val flightSeconds = flightNanos / 1_000_000_000.0
            val estimatedHeightMeters = 9.80665 * flightSeconds * flightSeconds / 8.0
            if (estimatedHeightMeters < minimumJumpHeightMeters) {
                reset()
                return
            }
            val base = when (sensorQuality) {
                SensorQuality.FULL -> 78
                SensorQuality.ACCELEROMETER_ONLY -> 58
                SensorQuality.DEGRADED -> 35
            }
            val rotationPenalty = ((maxFlightGyroMagnitude - 8.0).coerceAtLeast(0.0) * 2).toInt().coerceAtMost(20)
            val landingAdjustment = when {
                magnitude >= 20.0 -> 4
                magnitude < 16.0 -> -5
                else -> 0
            }
            val mountingAdjustment = if (mountingMode == MountingMode.BIKE_MOUNTED) 5 else 0
            onJump(activeTakeoff, timestampNanos, (base - rotationPenalty + landingAdjustment + mountingAdjustment).coerceIn(15, 95))
            reset()
        } else if (flightNanos > 2_500_000_000L) {
            reset()
        }
    }

    private fun reset() {
        lowAccelerationStarted = null
        takeoffNanos = null
        maxFlightGyroMagnitude = 0.0
    }
}
