package com.example.flightlog.bikepark

import org.junit.Assert.assertEquals
import org.junit.Test

class ParkSamplingProfileTest {
    @Test
    fun waitingForSummitUsesLowPowerSampling() {
        assertEquals(ParkSamplingProfile.LOW_POWER, ParkDayState.WAITING_FOR_SUMMIT.samplingProfile())
    }

    @Test
    fun summitDepartureAndRunUseNormalSampling() {
        listOf(
            ParkDayState.WAITING_IN_SUMMIT,
            ParkDayState.PENDING_START,
            ParkDayState.RECORDING_RUN,
        ).forEach {
            assertEquals(ParkSamplingProfile.NORMAL, it.samplingProfile())
        }
    }
}
