package com.example.flightlog.data

import com.example.flightlog.domain.PauseZoneState
import com.example.flightlog.domain.RideState
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

data class TrailReassignmentPreview(
    val trailId: Long,
    val trailName: String,
    val previousReferenceRideId: Long,
    val replacementRideId: Long,
    val draft: TrailDefinitionDraft,
    val removedItems: List<TrailCustomItemChange>,
)

data class TrailDeletionPreview(
    val trailId: Long,
    val trailName: String,
)

data class BulkRideDeletePreview(
    val rideIds: Set<Long>,
    val reassignments: List<TrailReassignmentPreview>,
    val deletedTrails: List<TrailDeletionPreview>,
    val retainedTrailIdsToRebuild: Set<Long>,
) {
    val removedCustomItems: List<TrailCustomItemChange>
        get() = reassignments.flatMap { it.removedItems }
}

data class BulkRideDeleteResult(
    val deletedRideCount: Int,
    val reassignedTrailCount: Int,
    val deletedTrailCount: Int,
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

internal fun remapTrailRange(
    startMeters: Double,
    endMeters: Double,
    previousProfiles: List<SpatialProfileEntity>,
    newProfiles: List<SpatialProfileEntity>,
): Pair<Double, Double>? {
    val start = mapDistance(startMeters, previousProfiles, newProfiles) ?: return null
    val end = mapDistance(endMeters, previousProfiles, newProfiles) ?: return null
    return (start to end).takeIf { end - start >= 10.0 }
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

internal fun buildBulkRideDeletePreview(
    selectedRideIds: Set<Long>,
    rides: List<RideEntity>,
    trails: List<TrailEntity>,
    passesByTrail: Map<Long, List<TrailPassEntity>>,
    profilesByRide: Map<Long, List<SpatialProfileEntity>>,
    sectionsByTrail: Map<Long, List<TrailSectionEntity>>,
    pauseZonesByTrail: Map<Long, List<TrailPauseZoneEntity>>,
): BulkRideDeletePreview {
    require(selectedRideIds.isNotEmpty()) { "Select at least one ride" }
    val ridesById = rides.associateBy { it.id }
    require(selectedRideIds.all { id ->
        ridesById[id]?.state?.let { it != RideState.RECORDING && it != RideState.PAUSED } == true
    }) { "Some selected rides can no longer be deleted" }

    val reassignments = mutableListOf<TrailReassignmentPreview>()
    val deletedTrails = mutableListOf<TrailDeletionPreview>()
    trails.filter { it.canonicalRideId in selectedRideIds }.sortedBy { it.id }.forEach { trail ->
        val previousProfiles = profilesByRide[trail.canonicalRideId].orEmpty()
        val replacement = passesByTrail[trail.id].orEmpty().asSequence()
            .filter { pass ->
                pass.rideId !in selectedRideIds && pass.completeCoverage && !pass.interrupted && !pass.hasReversal &&
                    ridesById[pass.rideId]?.state?.let { it != RideState.RECORDING && it != RideState.PAUSED } == true
            }
            .sortedWith(compareByDescending<TrailPassEntity> { it.matchConfidence }.thenByDescending { it.startedAt })
            .mapNotNull { pass ->
                val newProfiles = profilesByRide[pass.rideId].orEmpty()
                val mappedBounds = remapTrailRange(
                    trail.startMeters, trail.endMeters, previousProfiles, newProfiles,
                ) ?: return@mapNotNull null
                val draft = TrailDefinitionDraft(
                    trailId = trail.id,
                    name = trail.name,
                    referenceRideId = pass.rideId,
                    startMeters = mappedBounds.first,
                    endMeters = mappedBounds.second,
                )
                val items = remapTrailItems(
                    draft = draft,
                    previousReferenceRideId = trail.canonicalRideId,
                    previousProfiles = previousProfiles,
                    newProfiles = newProfiles,
                    sections = sectionsByTrail[trail.id].orEmpty(),
                    pauseZones = pauseZonesByTrail[trail.id].orEmpty(),
                )
                TrailReassignmentPreview(
                    trailId = trail.id,
                    trailName = trail.name,
                    previousReferenceRideId = trail.canonicalRideId,
                    replacementRideId = pass.rideId,
                    draft = draft,
                    removedItems = items.removed,
                )
            }
            .firstOrNull()
        if (replacement == null) {
            deletedTrails += TrailDeletionPreview(trail.id, trail.name)
        } else {
            reassignments += replacement
        }
    }
    val deletedTrailIds = deletedTrails.mapTo(hashSetOf()) { it.trailId }
    val retainedTrailIdsToRebuild = trails.asSequence()
        .filter { it.id !in deletedTrailIds }
        .filter { trail ->
            reassignments.any { it.trailId == trail.id } ||
                passesByTrail[trail.id].orEmpty().any { it.rideId in selectedRideIds }
        }
        .map { it.id }
        .toSortedSet()
    return BulkRideDeletePreview(
        rideIds = selectedRideIds.toSortedSet(),
        reassignments = reassignments,
        deletedTrails = deletedTrails,
        retainedTrailIdsToRebuild = retainedTrailIdsToRebuild,
    )
}
