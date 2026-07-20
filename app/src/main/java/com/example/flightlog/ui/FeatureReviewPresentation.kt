package com.example.flightlog.ui

import com.example.flightlog.data.FeatureRunEvidence
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val MAX_VISIBLE_FEATURE_REFERENCES = 6
internal const val DEFAULT_VISIBLE_FEATURE_REFERENCES = 3

internal fun reconcileFeatureReferenceSelection(
    available: List<FeatureRunEvidence>,
    selectedIds: Set<Long>,
): Set<Long> {
    val availableIds = available.map { it.observation.id }
    val retained = selectedIds.filterTo(linkedSetOf()) { it in availableIds }
    if (selectedIds.isEmpty()) {
        availableIds.take(DEFAULT_VISIBLE_FEATURE_REFERENCES).forEach(retained::add)
    }
    return retained.take(MAX_VISIBLE_FEATURE_REFERENCES).toCollection(linkedSetOf())
}

internal fun takeoffDistanceMeters(candidate: FeatureRunEvidence, reference: FeatureRunEvidence): Double =
    haversineMeters(
        candidate.observation.latitude,
        candidate.observation.longitude,
        reference.observation.latitude,
        reference.observation.longitude,
    )

internal fun bearingDifferenceDegrees(first: Double?, second: Double?): Double? {
    if (first == null || second == null) return null
    val raw = abs((first - second) % 360.0)
    return minOf(raw, 360.0 - raw)
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = lat1 * PI / 180.0
    val phi2 = lat2 * PI / 180.0
    val dPhi = (lat2 - lat1) * PI / 180.0
    val dLambda = (lon2 - lon1) * PI / 180.0
    val a = sin(dPhi / 2).let { it * it } + cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
    return 6_371_000.0 * 2.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
