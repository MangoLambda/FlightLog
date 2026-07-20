package com.example.flightlog.tracking

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.FlightKind
import com.example.flightlog.domain.SensorQuality
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideMathTest {
    @Test fun rejectsInaccurateAndImplausibleLocations() {
        val good = LocationSample(1_000, 45.5, -73.5, 5f, 5.0)
        assertTrue(RideMath.isUsableLocation(null, good))
        assertFalse(RideMath.isUsableLocation(null, good.copy(accuracyMeters = 60f)))
        assertFalse(RideMath.isUsableLocation(good, good.copy(timestamp = 2_000, latitude = 46.5)))
    }

    @Test fun computesKnownDistanceAndMedianSpeed() {
        val a = LocationSample(0, 0.0, 0.0, 3f, 0.0)
        val b = LocationSample(1_000, 0.0, 0.001, 3f, 0.0)
        assertEquals(111.2, RideMath.distanceMeters(a, b), 0.3)
        assertEquals(4.0, RideMath.smoothedSpeedMetersPerSecond(listOf(100.0, 4.0, 3.0)), 0.0)
    }

    @Test fun derivesSpeedWhenLocationDoesNotReportIt() {
        val previous = LocationSample(0, 45.5, -73.5, 3f, 0.0)
        val next = LocationSample(2_000, 45.5001, -73.5, 3f, 0.0)
        assertEquals(5.56, RideMath.effectiveSpeedMetersPerSecond(null, previous, next), 0.1)
        assertEquals(3.2, RideMath.effectiveSpeedMetersPerSecond(3.2, previous, next), 0.0)
    }

    @Test fun ballisticEstimateUsesAirtimeAndTakeoffSpeed() {
        val estimate = RideMath.jumpEstimate(1.0, 10.0, 82)
        assertEquals(1.2258, estimate.heightMeters, 0.001)
        assertEquals(10.0, estimate.distanceMeters, 0.0)
        assertEquals(82, estimate.confidence)
    }

    @Test fun totalsIncludeOnlyConfirmedJumpMeasurements() {
        val rides = listOf(RideEntity(id = 7, startedAt = 1_000, distanceMeters = 1_500.0, movingTimeMillis = 60_000))
        val base = JumpEventEntity(
            id = 1, rideId = 7, takeoffAt = 2_000, landingAt = 2_500,
            estimatedFlightSeconds = .5, estimatedHeightMeters = .3, estimatedDistanceMeters = 4.0,
            confidence = 80, sensorQuality = SensorQuality.FULL,
        )
        val totals = RideMath.aggregate(rides, listOf(
            base.copy(status = JumpStatus.CONFIRMED, estimatedFlightKind = FlightKind.JUMP),
            base.copy(id = 2, status = JumpStatus.PENDING, estimatedFlightKind = FlightKind.DROP),
            base.copy(id = 3, status = JumpStatus.REJECTED),
            base.copy(id = 4, status = JumpStatus.CONFIRMED, estimatedFlightKind = FlightKind.JUMP, correctedFlightKind = FlightKind.DROP),
        ))
        assertEquals(1, totals.confirmedJumps)
        assertEquals(1, totals.confirmedDrops)
        assertEquals(1, totals.pendingJumps)
        assertEquals(1, totals.rejectedJumps)
        assertEquals(1.0, totals.flightTimeSeconds, 0.0)
        assertEquals(8.0, totals.jumpedDistanceMeters, 0.0)
    }

    @Test fun calendarSeasonUsesRequestedTimezone() {
        val instant = java.time.Instant.parse("2026-07-14T12:00:00Z").toEpochMilli()
        val bounds = RideMath.calendarYearBounds(instant, ZoneId.of("America/Montreal"))
        assertTrue(instant in bounds)
        assertEquals(java.time.LocalDate.of(2026, 1, 1), java.time.Instant.ofEpochMilli(bounds.first).atZone(ZoneId.of("America/Montreal")).toLocalDate())
    }

    @Test fun correctingFlightTypeAlsoChangesItsDisplayedHeightModel() {
        val flight = .85
        val event = JumpEventEntity(
            rideId = 1, takeoffAt = 1_000, landingAt = 1_850,
            estimatedFlightSeconds = flight, estimatedHeightMeters = 9.80665 * flight * flight / 8.0,
            estimatedDistanceMeters = 3.0, confidence = 80, sensorQuality = SensorQuality.FULL,
            estimatedFlightKind = FlightKind.JUMP, correctedFlightKind = FlightKind.DROP,
        )

        assertEquals(3.54, event.displayHeightMeters, .02)
        assertEquals(.89, event.copy(correctedFlightKind = null).displayHeightMeters, .02)
    }
}
