package com.example.flightlog

import org.junit.Assert.assertEquals
import org.junit.Test

class DataSizeFormattingTest {
    @Test fun formatsBinaryDataSizesForDisplay() {
        assertEquals("0 B", formatDataSize(0))
        assertEquals("1023 B", formatDataSize(1_023))
        assertEquals("1.0 KB", formatDataSize(1_024))
        assertEquals("1.5 KB", formatDataSize(1_536))
        assertEquals("1.0 MB", formatDataSize(1_024 * 1_024L))
        assertEquals("1.00 GB", formatDataSize(1_024 * 1_024 * 1_024L))
    }

    @Test fun negativeSizesAreDisplayedAsZero() {
        assertEquals("0 B", formatDataSize(-1))
    }
}
