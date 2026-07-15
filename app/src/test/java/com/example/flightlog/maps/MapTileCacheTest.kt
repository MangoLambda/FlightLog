package com.example.flightlog.maps

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class MapTileCacheTest {
    @Test fun normalizesRollingLimitToSupportedSteps() {
        assertEquals(50, MapTileCache.normalizeLimit(1))
        assertEquals(100, MapTileCache.normalizeLimit(76))
        assertEquals(250, MapTileCache.normalizeLimit(250))
        assertEquals(1_000, MapTileCache.normalizeLimit(5_000))
    }

    @Test fun createsStableCalendarMonthKey() {
        assertEquals("2026-07", MapTileCache.monthKey(YearMonth.of(2026, 7)))
    }
}
