package com.example.flightlog

import com.example.flightlog.data.SectionEffortEntity
import com.example.flightlog.domain.EffortInvalidReason
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionComparisonMessageTest {
    @Test fun gpsGapMessageDoesNotClaimTheRiderStopped() {
        val message = invalidEffortMessage(listOf(effort(EffortInvalidReason.GPS_GAP)))

        assertTrue(message.contains("GPS samples were missing"))
        assertTrue(!message.contains("stop", ignoreCase = true))
    }

    @Test fun stopMessageExplainsWhyThePassWasExcluded() {
        val message = invalidEffortMessage(listOf(effort(EffortInvalidReason.STOP)))

        assertTrue(message.contains("stop", ignoreCase = true))
        assertTrue(message.contains("excluded"))
    }

    private fun effort(reason: EffortInvalidReason) = SectionEffortEntity(
        passId = 1, sectionId = 1, elapsedMillis = 1_000,
        entrySpeedMps = 1.0, minimumSpeedMps = 1.0, averageSpeedMps = 1.0,
        exitSpeedMps = 1.0, maximumSpeedMps = 1.0, sampleQuality = 90,
        lateralOffsetMeters = 0.0, lateralUncertaintyMeters = 3.0,
        valid = false, invalidReason = reason,
    )
}
