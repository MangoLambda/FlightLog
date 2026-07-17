package com.example.flightlog.data

import com.example.flightlog.domain.PauseZoneState
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.tracking.RideMath
import com.example.flightlog.tracking.TrailAnalysis
import com.example.flightlog.tracking.LocationSample

data class TrailDefinitionDraft(
    val trailId: Long?,
    val name: String,
    val referenceRideId: Long,
    val startMeters: Double,
    val endMeters: Double,
)

enum class TrailCustomItemKind { SECTION, PAUSE_ZONE }

data class TrailCustomItemChange(
    val key: String,
    val name: String,
    val kind: TrailCustomItemKind,
    val startMeters: Double,
    val endMeters: Double,
)

data class TrailEditImpact(
    val draft: TrailDefinitionDraft,
    val previousMatchedRideCount: Int,
    val previousCompleteRideCount: Int,
    val matchedRideCount: Int,
    val completeRideCount: Int,
    val preservedItems: List<TrailCustomItemChange>,
    val removedItems: List<TrailCustomItemChange>,
)

internal data class RemappedTrailItems(
    val preserved: List<TrailCustomItemChange>,
    val removed: List<TrailCustomItemChange>,
)

internal fun remapTrailItems(
    draft: TrailDefinitionDraft,
    previousReferenceRideId: Long,
    previousProfiles: List<SpatialProfileEntity>,
    newProfiles: List<SpatialProfileEntity>,
    sections: List<TrailSectionEntity>,
    pauseZones: List<TrailPauseZoneEntity>,
): RemappedTrailItems {
    val changes = buildList {
        sections.filter { it.kind == SectionKind.MANUAL }.forEach { section ->
            add(remapItem(
                key = "section:${section.id}",
                name = section.name,
                kind = TrailCustomItemKind.SECTION,
                startMeters = section.startMeters,
                endMeters = section.endMeters,
                sameReference = previousReferenceRideId == draft.referenceRideId,
                previousProfiles = previousProfiles,
                newProfiles = newProfiles,
            ))
        }
        pauseZones.filter { it.state == PauseZoneState.USER_LOCKED || it.state == PauseZoneState.DISMISSED }
            .forEach { zone ->
                add(remapItem(
                    key = "pause:${zone.id}",
                    name = zone.name,
                    kind = TrailCustomItemKind.PAUSE_ZONE,
                    startMeters = zone.startMeters,
                    endMeters = zone.endMeters,
                    sameReference = previousReferenceRideId == draft.referenceRideId,
                    previousProfiles = previousProfiles,
                    newProfiles = newProfiles,
                ))
            }
    }
    val valid = changes.filterNotNull().filter {
        it.startMeters >= draft.startMeters && it.endMeters <= draft.endMeters && it.startMeters < it.endMeters
    }
    val validKeys = valid.mapTo(hashSetOf()) { it.key }
    val originals = buildList {
        sections.filter { it.kind == SectionKind.MANUAL }.forEach {
            add(TrailCustomItemChange("section:${it.id}", it.name, TrailCustomItemKind.SECTION, it.startMeters, it.endMeters))
        }
        pauseZones.filter { it.state == PauseZoneState.USER_LOCKED || it.state == PauseZoneState.DISMISSED }.forEach {
            add(TrailCustomItemChange("pause:${it.id}", it.name, TrailCustomItemKind.PAUSE_ZONE, it.startMeters, it.endMeters))
        }
    }
    return RemappedTrailItems(valid, originals.filter { it.key !in validKeys })
}

private fun remapItem(
    key: String,
    name: String,
    kind: TrailCustomItemKind,
    startMeters: Double,
    endMeters: Double,
    sameReference: Boolean,
    previousProfiles: List<SpatialProfileEntity>,
    newProfiles: List<SpatialProfileEntity>,
): TrailCustomItemChange? {
    if (sameReference) return TrailCustomItemChange(key, name, kind, startMeters, endMeters)
    val mappedStart = mapDistance(startMeters, previousProfiles, newProfiles) ?: return null
    val mappedEnd = mapDistance(endMeters, previousProfiles, newProfiles) ?: return null
    if (mappedStart >= mappedEnd) return null
    return TrailCustomItemChange(key, name, kind, mappedStart, mappedEnd)
}

private fun mapDistance(
    distanceMeters: Double,
    previousProfiles: List<SpatialProfileEntity>,
    newProfiles: List<SpatialProfileEntity>,
): Double? {
    val source = previousProfiles.minByOrNull { kotlin.math.abs(it.distanceMeters - distanceMeters) } ?: return null
    val target = newProfiles.minByOrNull { coordinateDistanceMeters(source, it) } ?: return null
    val separation = coordinateDistanceMeters(source, target)
    val corridor = maxOf(15.0, source.accuracyMeters * 2.0, target.accuracyMeters * 2.0)
    return target.distanceMeters.takeIf { separation <= corridor }
}

private fun coordinateDistanceMeters(a: SpatialProfileEntity, b: SpatialProfileEntity): Double =
    RideMath.distanceMeters(
        LocationSample(0, a.latitude, a.longitude, a.accuracyMeters, 0.0),
        LocationSample(0, b.latitude, b.longitude, b.accuracyMeters, 0.0),
    )

internal fun projectedTrailCoverage(
    rides: List<RideEntity>,
    profilesByRide: Map<Long, List<SpatialProfileEntity>>,
    draft: TrailDefinitionDraft,
): Pair<Int, Int> {
    val canonical = profilesByRide[draft.referenceRideId].orEmpty()
    val selectedCanonical = canonical.filter { it.distanceMeters in draft.startMeters..draft.endMeters }
    var matched = 0
    var complete = 0
    rides.forEach { ride ->
        val profiles = profilesByRide[ride.id].orEmpty()
        val match = when {
            profiles.isEmpty() -> null
            ride.id == draft.referenceRideId -> TrailAnalysis.Match(100, profiles.map { it to it })
            else -> TrailAnalysis.match(profiles, selectedCanonical)
        } ?: return@forEach
        matched++
        if (TrailAnalysis.hasContinuousRangeCoverage(match.normalized, draft.startMeters, draft.endMeters)) complete++
    }
    return matched to complete
}
