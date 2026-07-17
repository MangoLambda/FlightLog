package com.example.flightlog

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
