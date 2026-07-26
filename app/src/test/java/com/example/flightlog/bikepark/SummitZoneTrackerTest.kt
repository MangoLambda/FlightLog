package com.example.flightlog.bikepark

import org.junit.Assert.assertEquals
import org.junit.Test

class SummitZoneTrackerTest {
    @Test
    fun firstAccurateFixAtSummitStopsWaitingForSummit() {
        val tracker = SummitZoneTracker()

        assertEquals(
            ParkDayState.WAITING_IN_SUMMIT,
            tracker.observe(ParkDayState.WAITING_FOR_SUMMIT, inSummit = true),
        )
    }

    @Test
    fun leavingSummitStillRequiresTwoFixes() {
        val tracker = SummitZoneTracker()
        tracker.observe(ParkDayState.WAITING_FOR_SUMMIT, inSummit = true)

        assertEquals(
            ParkDayState.WAITING_IN_SUMMIT,
            tracker.observe(ParkDayState.WAITING_IN_SUMMIT, inSummit = false),
        )
        assertEquals(
            ParkDayState.PENDING_START,
            tracker.observe(ParkDayState.WAITING_IN_SUMMIT, inSummit = false),
        )
    }
}
