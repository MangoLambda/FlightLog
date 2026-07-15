package com.example.flightlog.maps

import org.junit.Assert.assertTrue
import org.junit.Test

class MapProviderTest {
    @Test fun openCycleMapUsesCycleTiles() {
        val style = MapProvider.configured("test-key", MapStyle.OPEN_CYCLE_MAP).styleJson()

        assertTrue(style.contains("tile.thunderforest.com/cycle/"))
    }

    @Test fun cleanTerrainUsesLandscapeTiles() {
        val style = MapProvider.configured("test-key", MapStyle.CLEAN_TERRAIN).styleJson()

        assertTrue(style.contains("tile.thunderforest.com/landscape/"))
    }
}
