package com.example.flightlog.data

import com.example.flightlog.domain.PauseZoneState
import com.example.flightlog.domain.RideState
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.TrailState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailDefinitionsTest {
    @Test fun boundaryEditPreservesContainedCustomItemsAndFlagsOutsideItems() {
        val profiles = profiles(rideId = 1)
        val sections = listOf(
            TrailSectionEntity(id = 10, trailId = 1, name = "Inside", kind = SectionKind.MANUAL, startMeters = 30.0, endMeters = 50.0),
            TrailSectionEntity(id = 11, trailId = 1, name = "Outside", kind = SectionKind.MANUAL, startMeters = 5.0, endMeters = 20.0),
        )
        val zones = listOf(
            pauseZone(id = 20, start = 60.0, end = 70.0),
            pauseZone(id = 21, start = 85.0, end = 95.0),
        )

        val result = remapTrailItems(
            draft = TrailDefinitionDraft(1, "Trail", 1, 25.0, 80.0),
            previousReferenceRideId = 1,
            previousProfiles = profiles,
            newProfiles = profiles,
            sections = sections,
            pauseZones = zones,
        )

        assertEquals(setOf("section:10", "pause:20"), result.preserved.map { it.key }.toSet())
        assertEquals(setOf("section:11", "pause:21"), result.removed.map { it.key }.toSet())
    }

    @Test fun referenceChangeRemapsCustomItemsByLocation() {
        val old = profiles(rideId = 1, distanceOffset = 0.0)
        val replacement = profiles(rideId = 2, distanceOffset = 100.0)
        val section = TrailSectionEntity(
            id = 10, trailId = 1, name = "Feature", kind = SectionKind.MANUAL,
            startMeters = 20.0, endMeters = 40.0,
        )

        val result = remapTrailItems(
            draft = TrailDefinitionDraft(1, "Trail", 2, 100.0, 200.0),
            previousReferenceRideId = 1,
            previousProfiles = old,
            newProfiles = replacement,
            sections = listOf(section),
            pauseZones = emptyList(),
        )

        assertTrue(result.removed.isEmpty())
        assertEquals(120.0, result.preserved.single().startMeters, .01)
        assertEquals(140.0, result.preserved.single().endMeters, .01)
    }

    @Test fun bulkDeleteChoosesHighestConfidenceRemainingCompleteRide() {
        val rides = listOf(ride(1), ride(2), ride(3))
        val trail = trail(id = 10, canonicalRideId = 1)

        val preview = buildBulkRideDeletePreview(
            selectedRideIds = setOf(1),
            rides = rides,
            trails = listOf(trail),
            passesByTrail = mapOf(10L to listOf(
                pass(id = 20, trailId = 10, rideId = 2, confidence = 80, startedAt = 3_000),
                pass(id = 21, trailId = 10, rideId = 3, confidence = 95, startedAt = 2_000),
            )),
            profilesByRide = rides.associate { it.id to profiles(it.id) },
            sectionsByTrail = emptyMap(),
            pauseZonesByTrail = emptyMap(),
        )

        assertEquals(3L, preview.reassignments.single().replacementRideId)
        assertTrue(preview.deletedTrails.isEmpty())
        assertEquals(setOf(10L), preview.retainedTrailIdsToRebuild)
    }

    @Test fun bulkDeleteWarnsWhenEveryReplacementIsSelectedOrIncomplete() {
        val rides = listOf(ride(1), ride(2), ride(3))
        val trail = trail(id = 10, canonicalRideId = 1)

        val preview = buildBulkRideDeletePreview(
            selectedRideIds = setOf(1, 2),
            rides = rides,
            trails = listOf(trail),
            passesByTrail = mapOf(10L to listOf(
                pass(id = 20, trailId = 10, rideId = 2),
                pass(id = 21, trailId = 10, rideId = 3).copy(completeCoverage = false),
            )),
            profilesByRide = rides.associate { it.id to profiles(it.id) },
            sectionsByTrail = emptyMap(),
            pauseZonesByTrail = emptyMap(),
        )

        assertTrue(preview.reassignments.isEmpty())
        assertEquals(listOf("Trail 10"), preview.deletedTrails.map { it.trailName })
        assertTrue(preview.retainedTrailIdsToRebuild.isEmpty())
    }

    @Test fun deletingOnlyAMatchedRideRebuildsButDoesNotDeleteItsTrail() {
        val rides = listOf(ride(1), ride(2))
        val trail = trail(id = 10, canonicalRideId = 1)

        val preview = buildBulkRideDeletePreview(
            selectedRideIds = setOf(2),
            rides = rides,
            trails = listOf(trail),
            passesByTrail = mapOf(10L to listOf(pass(id = 20, trailId = 10, rideId = 2))),
            profilesByRide = rides.associate { it.id to profiles(it.id) },
            sectionsByTrail = emptyMap(),
            pauseZonesByTrail = emptyMap(),
        )

        assertTrue(preview.reassignments.isEmpty())
        assertTrue(preview.deletedTrails.isEmpty())
        assertEquals(setOf(10L), preview.retainedTrailIdsToRebuild)
    }

    @Test fun reassignmentWarnsAboutCustomItemsThatCannotMapToTheReplacementLine() {
        val rides = listOf(ride(1), ride(2))
        val oldProfiles = profiles(1)
        val branchProfiles = profiles(2).mapIndexed { index, profile ->
            if (index in 3..17) profile.copy(latitude = profile.latitude + .002) else profile
        }
        val feature = TrailSectionEntity(
            id = 50,
            trailId = 10,
            name = "Main-line feature",
            kind = SectionKind.MANUAL,
            startMeters = 40.0,
            endMeters = 60.0,
        )

        val preview = buildBulkRideDeletePreview(
            selectedRideIds = setOf(1),
            rides = rides,
            trails = listOf(trail(10, 1)),
            passesByTrail = mapOf(10L to listOf(pass(20, 10, 2))),
            profilesByRide = mapOf(1L to oldProfiles, 2L to branchProfiles),
            sectionsByTrail = mapOf(10L to listOf(feature)),
            pauseZonesByTrail = emptyMap(),
        )

        assertEquals(listOf("Main-line feature"), preview.removedCustomItems.map { it.name })
        assertTrue(preview.deletedTrails.isEmpty())
    }

    @Test fun bulkDeleteRejectsAnActiveRideBeforePlanningAnyMutation() {
        val active = ride(1).copy(endedAt = null, state = RideState.RECORDING)

        val result = runCatching {
            buildBulkRideDeletePreview(
                selectedRideIds = setOf(active.id),
                rides = listOf(active),
                trails = emptyList(),
                passesByTrail = emptyMap(),
                profilesByRide = emptyMap(),
                sectionsByTrail = emptyMap(),
                pauseZonesByTrail = emptyMap(),
            )
        }

        assertTrue(result.isFailure)
    }

    private fun profiles(rideId: Long, distanceOffset: Double = 0.0) = (0..20).map { index ->
        SpatialProfileEntity(
            rideId = rideId,
            distanceBin = index,
            distanceMeters = distanceOffset + index * 5.0,
            recordedAt = index * 1_000L,
            latitude = 45.5 + index * .0001,
            longitude = -73.5,
            speedMps = 5.0,
            accuracyMeters = 3f,
        )
    }

    private fun pauseZone(id: Long, start: Double, end: Double) = TrailPauseZoneEntity(
        id = id,
        trailId = 1,
        name = "Pause $id",
        startMeters = start,
        endMeters = end,
        state = PauseZoneState.USER_LOCKED,
        supportCount = 0,
        eligiblePassCount = 0,
        confidence = 100,
        medianPauseMillis = 0,
    )

    private fun ride(id: Long) = RideEntity(
        id = id,
        startedAt = id * 1_000,
        endedAt = id * 1_000 + 500,
        state = RideState.COMPLETED,
        distanceMeters = 100.0,
    )

    private fun trail(id: Long, canonicalRideId: Long) = TrailEntity(
        id = id,
        name = "Trail $id",
        state = TrailState.CONFIRMED,
        canonicalRideId = canonicalRideId,
        lengthMeters = 100.0,
        startMeters = 0.0,
        endMeters = 100.0,
    )

    private fun pass(
        id: Long,
        trailId: Long,
        rideId: Long,
        confidence: Int = 90,
        startedAt: Long = 1_000,
    ) = TrailPassEntity(
        id = id,
        trailId = trailId,
        rideId = rideId,
        startedAt = startedAt,
        endedAt = startedAt + 500,
        startMeters = 0.0,
        endMeters = 100.0,
        matchConfidence = confidence,
        interrupted = false,
        completeCoverage = true,
        hasReversal = false,
    )
}
