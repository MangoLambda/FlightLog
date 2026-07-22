package com.example.flightlog

import com.example.flightlog.data.ManualTrailAssignmentEntity
import com.example.flightlog.data.TrailEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.domain.TrailState

/** Returns the trail label to use as the primary heading for each ride. */
internal fun primaryTrailNames(
    manualAssignments: List<ManualTrailAssignmentEntity>,
    passes: List<TrailPassEntity>,
    trails: List<TrailEntity>,
): Map<Long, String> {
    val trailsById = trails.associateBy { it.id }
    val trailNames = trailsById.mapValues { it.value.name }
    val manualByRide = manualAssignments
        .asSequence()
        .filter { it.trailId in trailNames }
        .sortedWith(compareByDescending<ManualTrailAssignmentEntity> { it.updatedAt }.thenBy { it.trailId })
        .associateBy { it.rideId }

    val automaticByRide = passes
        .asSequence()
        .filter { it.completeCoverage && it.trailId in trailNames }
        .groupBy { it.rideId }
        .mapValues { (_, ridePasses) ->
            ridePasses.minWithOrNull(
                compareByDescending<TrailPassEntity> { trailsById[it.trailId]?.state == TrailState.CONFIRMED }
                    .thenByDescending { it.matchConfidence }
                    .thenBy { it.trailId }
                    .thenBy { it.id },
            )!!
        }

    return (manualByRide.keys + automaticByRide.keys).associateWith { rideId ->
        val trailId = manualByRide[rideId]?.trailId ?: automaticByRide[rideId]?.trailId
        trailNames[trailId]!!
    }
}
