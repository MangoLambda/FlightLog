package com.example.flightlog.tracking

import androidx.room.withTransaction
import com.example.flightlog.data.FeatureObservationEntity
import com.example.flightlog.data.FlightLogDatabase
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.PhysicalFeatureEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.domain.FeatureAssignmentSource
import com.example.flightlog.domain.FeatureAssignmentState
import com.example.flightlog.domain.FlightKind
import com.example.flightlog.domain.JumpStatus
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class FeatureMatch(val featureId: Long, val confidence: Int)

object PhysicalFeatureMatcher {
    const val AUTOMATIC_CONFIDENCE = 75
    const val MINIMUM_MARGIN = 15
    const val REVIEW_CONFIDENCE = 45

    fun ranked(observation: FeatureObservationEntity, kind: FlightKind, features: List<PhysicalFeatureEntity>): List<FeatureMatch> =
        features.mapNotNull { feature ->
            if (feature.kind != FlightKind.UNCERTAIN && kind != FlightKind.UNCERTAIN && feature.kind != kind) return@mapNotNull null
            val meters = distance(observation.latitude, observation.longitude, feature.latitude, feature.longitude)
            val corridor = maxOf(12.0, observation.gpsAccuracyMeters * 2.0)
            if (meters > maxOf(35.0, corridor)) return@mapNotNull null
            var score = 100.0 - (meters / maxOf(20.0, corridor) * 45.0)
            score -= bearingPenalty(observation.approachBearingDegrees, feature.approachBearingDegrees)
            score -= bearingPenalty(observation.exitBearingDegrees, feature.exitBearingDegrees) * .6
            FeatureMatch(feature.id, score.toInt().coerceIn(0, 100))
        }.sortedByDescending { it.confidence }

    fun automatic(ranked: List<FeatureMatch>): FeatureMatch? {
        val best = ranked.firstOrNull() ?: return null
        val margin = best.confidence - (ranked.getOrNull(1)?.confidence ?: 0)
        return best.takeIf { it.confidence >= AUTOMATIC_CONFIDENCE && margin >= MINIMUM_MARGIN }
    }

    private fun bearingPenalty(a: Double?, b: Double?): Double =
        if (a == null || b == null) 5.0 else angularDifference(a, b) / 180.0 * 35.0

    private fun angularDifference(a: Double, b: Double) = abs((b - a + 540.0) % 360.0 - 180.0)

    internal fun distance(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val dLat = Math.toRadians(latB - latA)
        val dLon = Math.toRadians(lonB - lonA)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(latA)) * cos(Math.toRadians(latB)) * sin(dLon / 2).pow(2)
        return 6_371_000.0 * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }
}

class PhysicalFeatureAnalyzer(private val database: FlightLogDatabase) {
    private val dao = database.dao()

    suspend fun analyzeRide(ride: RideEntity, jumps: List<JumpEventEntity>, points: List<TrackPointEntity>, motion: MotionTelemetry) {
        database.withTransaction {
            jumps.filter { it.status == JumpStatus.CONFIRMED }.forEach { jump ->
                val prior = dao.featureObservationForJump(jump.id)
                if (prior?.assignmentSource == FeatureAssignmentSource.RIDER) return@forEach
                val takeoff = nearest(points, jump.takeoffAt) ?: return@forEach
                val before = nearest(points.filter { it.recordedAt <= jump.takeoffAt }, jump.takeoffAt - 1_500)
                val after = nearest(points.filter { it.recordedAt >= jump.landingAt }, jump.landingAt + 1_500)
                val samples = JumpMotionTrace.samples(jump, motion).accelerationFrames()
                val peakG = landingPeakG(jump, samples)
                val draft = FeatureObservationEntity(
                    id = prior?.id ?: 0,
                    jumpId = jump.id,
                    assignmentState = FeatureAssignmentState.UNGROUPED,
                    matchConfidence = 0,
                    latitude = jump.latitude ?: takeoff.latitude,
                    longitude = jump.longitude ?: takeoff.longitude,
                    gpsAccuracyMeters = takeoff.accuracyMeters,
                    approachBearingDegrees = before?.let { bearing(it, takeoff) } ?: takeoff.bearingDegrees?.toDouble(),
                    exitBearingDegrees = after?.let { bearing(takeoff, it) },
                    takeoffSpeedMps = before?.speedMps ?: takeoff.speedMps,
                    airtimeSeconds = jump.displayFlightSeconds,
                    heightMeters = jump.displayHeightMeters,
                    distanceMeters = jump.displayDistanceMeters,
                    landingPeakG = peakG,
                    landingSmoothness = peakG?.let { (100.0 - (it - 1.0) * 20.0).toInt().coerceIn(0, 100) },
                    mountingMode = ride.mountingMode,
                    createdAt = prior?.createdAt ?: jump.takeoffAt,
                )
                val ranked = PhysicalFeatureMatcher.ranked(draft, jump.displayFlightKind, dao.allPhysicalFeatures())
                val automatic = PhysicalFeatureMatcher.automatic(ranked)
                val observation = when {
                    automatic != null -> draft.copy(featureId = automatic.featureId, assignmentState = FeatureAssignmentState.MATCHED, matchConfidence = automatic.confidence)
                    ranked.firstOrNull()?.confidence ?: 0 >= PhysicalFeatureMatcher.REVIEW_CONFIDENCE -> draft.copy(featureId = ranked.first().featureId, assignmentState = FeatureAssignmentState.REVIEW, matchConfidence = ranked.first().confidence)
                    else -> {
                        val kind = jump.displayFlightKind
                        val number = dao.allPhysicalFeatures().count { it.kind == kind } + 1
                        val featureId = dao.insertPhysicalFeature(PhysicalFeatureEntity(
                            name = "${if (kind == FlightKind.DROP) "Drop" else "Jump"} $number",
                            kind = kind, latitude = draft.latitude, longitude = draft.longitude,
                            approachBearingDegrees = draft.approachBearingDegrees, exitBearingDegrees = draft.exitBearingDegrees,
                            confidence = jump.confidence,
                        ))
                        draft.copy(featureId = featureId, assignmentState = FeatureAssignmentState.MATCHED, matchConfidence = jump.confidence)
                    }
                }
                dao.insertFeatureObservation(observation)
            }
            refreshFeatures()
        }
    }

    suspend fun refreshFeatures() {
        dao.allPhysicalFeatures().forEach { feature ->
            val observations = dao.featureObservations(feature.id).filter { it.assignmentState == FeatureAssignmentState.MATCHED }
            if (observations.isNotEmpty()) dao.updatePhysicalFeature(feature.copy(
                latitude = observations.map { it.latitude }.average(), longitude = observations.map { it.longitude }.average(),
                approachBearingDegrees = circularMean(observations.mapNotNull { it.approachBearingDegrees }),
                exitBearingDegrees = circularMean(observations.mapNotNull { it.exitBearingDegrees }),
                confidence = observations.map { it.matchConfidence }.average().toInt(), observationCount = observations.size,
                updatedAt = System.currentTimeMillis(),
            ))
        }
        dao.deleteEmptyPhysicalFeatures()
    }

    private fun nearest(points: List<TrackPointEntity>, timestamp: Long) = points.minByOrNull { abs(it.recordedAt - timestamp) }
        ?.takeIf { abs(it.recordedAt - timestamp) <= 5_000 }
    private fun bearing(a: TrackPointEntity, b: TrackPointEntity): Double {
        val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude); val lon = Math.toRadians(b.longitude - a.longitude)
        return (Math.toDegrees(atan2(sin(lon) * cos(lat2), cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon))) + 360) % 360
    }
    private fun circularMean(values: List<Double>): Double? = if (values.isEmpty()) null else
        (Math.toDegrees(atan2(values.sumOf { sin(Math.toRadians(it)) }, values.sumOf { cos(Math.toRadians(it)) })) + 360) % 360
    private fun landingPeakG(jump: JumpEventEntity, samples: List<MotionSample>): Double? {
        val values = samples.filter { it.timestampMillis in (jump.landingAt - 100)..(jump.landingAt + 300) }.map {
            sqrt(it.accelerationX * it.accelerationX + it.accelerationY * it.accelerationY + it.accelerationZ * it.accelerationZ) / 9.80665
        }
        return values.takeIf { it.size >= 3 }?.maxOrNull()
    }
}
