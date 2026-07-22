package com.example.flightlog

import org.junit.Assert.assertEquals
import org.junit.Test
import com.example.flightlog.data.ManualTrailAssignmentEntity
import com.example.flightlog.data.TrailEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.domain.TrailState

class HistorySelectionTest {
    @Test fun tappingARideTogglesOnlyThatRide() {
        assertEquals(setOf(1L, 3L), toggleRideSelection(setOf(1L, 2L, 3L), 2L))
        assertEquals(setOf(1L, 2L, 3L, 4L), toggleRideSelection(setOf(1L, 2L, 3L), 4L))
    }

    @Test fun selectAllTogglesBetweenEveryEligibleRideAndNone() {
        val eligible = setOf(1L, 2L, 3L)

        assertEquals(eligible, toggleAllRideSelection(setOf(1L), eligible))
        assertEquals(emptySet<Long>(), toggleAllRideSelection(eligible, eligible))
    }

    @Test fun manualAssignmentTakesPrecedenceOverAutomaticPass() {
        val trails = listOf(trail(1, "Manual trail"), trail(2, "Automatic trail"))
        val result = primaryTrailNames(
            listOf(ManualTrailAssignmentEntity(rideId = 10, trailId = 1, startMeters = 0.0, endMeters = 100.0)),
            listOf(pass(10, 2, confidence = 100, complete = true)),
            trails,
        )

        assertEquals("Manual trail", result[10L])
    }

    @Test fun highestConfidenceCompletePassIsSelected() {
        val result = primaryTrailNames(
            emptyList(),
            listOf(pass(10, 1, confidence = 60, complete = true), pass(10, 2, confidence = 90, complete = true), pass(10, 3, confidence = 100, complete = false)),
            listOf(trail(1, "Lower"), trail(2, "Higher"), trail(3, "Incomplete")),
        )

        assertEquals("Higher", result[10L])
    }

    @Test fun equalConfidencePassesUseStableTrailIdTieBreak() {
        val result = primaryTrailNames(
            emptyList(),
            listOf(pass(10, 2, confidence = 80, complete = true, id = 20), pass(10, 1, confidence = 80, complete = true, id = 10)),
            listOf(trail(1, "First"), trail(2, "Second")),
        )

        assertEquals("First", result[10L])
    }

    @Test fun confirmedTrailBeatsHigherConfidenceGeneratedTrail() {
        val result = primaryTrailNames(
            emptyList(),
            listOf(pass(10, 1, confidence = 70, complete = true), pass(10, 2, confidence = 95, complete = true)),
            listOf(trail(1, "Renamed trail", TrailState.CONFIRMED), trail(2, "Trail near 10", TrailState.CANDIDATE)),
        )

        assertEquals("Renamed trail", result[10L])
    }

    @Test fun ridesWithoutAQualifyingTrailAreUnassignedByTheUi() {
        assertEquals(emptyMap<Long, String>(), primaryTrailNames(emptyList(), emptyList(), emptyList()))
    }

    private fun trail(id: Long, name: String, state: TrailState = TrailState.CANDIDATE) = TrailEntity(
        id = id, name = name, state = state, canonicalRideId = 1, lengthMeters = 100.0,
    )

    private fun pass(rideId: Long, trailId: Long, confidence: Int, complete: Boolean, id: Long = 0) = TrailPassEntity(
        id = id, trailId = trailId, rideId = rideId, startedAt = 1, endedAt = 2,
        startMeters = 0.0, endMeters = 100.0, matchConfidence = confidence, interrupted = false,
        completeCoverage = complete,
    )
}
