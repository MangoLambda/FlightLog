package com.example.flightlog.data

import com.example.flightlog.tracking.MotionTelemetry
import com.example.flightlog.tracking.JumpMotionTrace
import kotlin.math.abs

data class FeatureRunEvidence(
    val observation: FeatureObservationEntity,
    val jump: JumpEventEntity,
    val ride: RideEntity,
    val routePoints: List<TrackPointEntity>,
    val motion: MotionTelemetry,
    val sensorTraceAvailable: Boolean,
)

data class FeatureReviewEvidence(
    val reviewObservation: FeatureObservationEntity,
    val proposedFeature: PhysicalFeatureEntity,
    val candidate: FeatureRunEvidence,
    val references: List<FeatureRunEvidence>,
)

sealed interface FeatureReviewEvidenceState {
    data object None : FeatureReviewEvidenceState
    data object Loading : FeatureReviewEvidenceState
    data class Available(val evidence: FeatureReviewEvidence) : FeatureReviewEvidenceState
    data class Unavailable(val observationId: Long, val message: String) : FeatureReviewEvidenceState
}

internal fun featureRunRoutePoints(
    jump: JumpEventEntity,
    points: List<TrackPointEntity>,
): List<TrackPointEntity> {
    val exact = points.filter { it.recordedAt in JumpMotionTrace.window(jump) }
    return exact.ifEmpty {
        points.sortedBy { abs(it.recordedAt - jump.takeoffAt) }.take(20).sortedBy { it.recordedAt }
    }
}
