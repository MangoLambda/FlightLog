package com.example.flightlog.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RideStorageCalculationTest {
    @Test fun emptyRideIncludesItsMetadataRow() {
        assertEquals(128L, RideStorageEstimate.bytes(components()))
    }

    @Test fun combinesExactPayloadLengthsWithEstimatedRowSizes() {
        val components = components().copy(
            trackPointCount = 1,
            telemetryChunkCount = 1,
            telemetryPayloadBytes = 3,
            jumpCount = 1,
            jumpTraceCount = 1,
            jumpTracePayloadBytes = 2,
            spatialProfileCount = 1,
            stopEventCount = 1,
            trailPassCount = 1,
            sectionEffortCount = 1,
            trailStopObservationCount = 1,
        )

        assertEquals(1_149L, RideStorageEstimate.bytes(components))
    }

    private fun components() = RideStorageComponents(
        rideId = 1,
        trackPointCount = 0,
        telemetryChunkCount = 0,
        telemetryPayloadBytes = 0,
        jumpCount = 0,
        jumpTraceCount = 0,
        jumpTracePayloadBytes = 0,
        spatialProfileCount = 0,
        stopEventCount = 0,
        trailPassCount = 0,
        sectionEffortCount = 0,
        trailStopObservationCount = 0,
    )
}
