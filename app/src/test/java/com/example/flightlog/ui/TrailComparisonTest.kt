package com.example.flightlog.ui

import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.SectionEffortEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.SectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailComparisonTest {
    @Test
    fun `speed series aligns candidate speed to canonical trail distance`() {
        val canonical = profiles(rideId = 1, speedOffset = 0.0, latitudeOffset = 0.0)
        val candidate = profiles(rideId = 2, speedOffset = 2.0, latitudeOffset = 0.00001)

        val series = normalizedSpeedSeries(candidate, canonical, 20.0, 80.0)

        assertTrue(series.size >= 10)
        assertTrue(series.all { it.distanceMeters in 20.0..80.0 })
        assertEquals(8.0, series.first().speedMps, 0.001)
    }

    @Test
    fun `speed series rejects an opposite direction pass`() {
        val canonical = profiles(rideId = 1, speedOffset = 0.0, latitudeOffset = 0.0)
        val candidate = profiles(rideId = 2, speedOffset = 0.0, latitudeOffset = 0.00001).reversed()
            .mapIndexed { index, profile -> profile.copy(distanceBin = index, distanceMeters = index * 5.0) }

        assertTrue(normalizedSpeedSeries(candidate, canonical, 0.0, 120.0).isEmpty())
    }

    @Test
    fun `full run picker includes only qualified passes with a valid whole effort`() {
        val passes = listOf(pass(1, qualified = true), pass(2, qualified = false), pass(3, qualified = true))
        val efforts = listOf(effort(1, 99, 60_000), effort(2, 99, 59_000), effort(3, 10, 20_000))

        val eligible = eligibleComparisonPasses(true, passes, efforts, 99, setOf(10))

        assertEquals(listOf(1L), eligible.map { it.id })
    }

    @Test
    fun `comparison keeps only splits valid in both passes and reconciles transition time`() {
        val splits = listOf(section(10, 0.0), section(20, 50.0))
        val a = listOf(effort(1, 10, 20_000), effort(1, 20, 30_000))
        val b = listOf(effort(2, 10, 21_000), effort(2, 20, 29_000, valid = false))

        val comparable = comparableSplits(splits, a, b)

        assertEquals(listOf(10L), comparable.map { it.section.id })
        assertEquals(10_000L, pauseTransitionMillis(effort(1, 99, 60_000), a))
    }

    private fun profiles(rideId: Long, speedOffset: Double, latitudeOffset: Double) = (0..30).map { index ->
        SpatialProfileEntity(
            rideId = rideId,
            distanceBin = index,
            distanceMeters = index * 5.0,
            recordedAt = index * 1_000L,
            latitude = 45.5 + latitudeOffset,
            longitude = -73.6 + index * 0.00005,
            speedMps = 6.0 + speedOffset,
            accuracyMeters = 3f,
        )
    }

    private fun pass(id: Long, qualified: Boolean) = TrailPassEntity(
        id = id,
        trailId = 1,
        rideId = id,
        startedAt = id * 1_000,
        endedAt = id * 1_000 + 60_000,
        startMeters = 0.0,
        endMeters = 100.0,
        matchConfidence = 90,
        interrupted = false,
        fullRunEligible = qualified,
    )

    private fun section(id: Long, start: Double) = TrailSectionEntity(
        id = id,
        trailId = 1,
        name = "Split $id",
        kind = SectionKind.SPLIT,
        state = SectionState.CONFIRMED,
        startMeters = start,
        endMeters = start + 50.0,
    )

    private fun effort(passId: Long, sectionId: Long, elapsed: Long, valid: Boolean = true) = SectionEffortEntity(
        passId = passId,
        sectionId = sectionId,
        elapsedMillis = elapsed,
        entrySpeedMps = 5.0,
        minimumSpeedMps = 4.0,
        averageSpeedMps = 6.0,
        exitSpeedMps = 7.0,
        maximumSpeedMps = 8.0,
        sampleQuality = 90,
        lateralOffsetMeters = 0.0,
        lateralUncertaintyMeters = 2.0,
        valid = valid,
    )
}
