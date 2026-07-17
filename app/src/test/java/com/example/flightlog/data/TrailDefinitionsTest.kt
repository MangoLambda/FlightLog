package com.example.flightlog.data

import com.example.flightlog.domain.PauseZoneState
import com.example.flightlog.domain.SectionKind
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

    private fun profiles(rideId: Long, distanceOffset: Double = 0.0) = (0..20).map { index ->
        SpatialProfileEntity(
            rideId = rideId,
            distanceBin = index,
            distanceMeters = distanceOffset + index * 5.0,
            recordedAt = index * 1_000L,
            latitude = 45.5 + index * .00001,
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
}
