package com.example.flightlog.ui

import com.example.flightlog.data.SectionEffortEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailProgressTest {
    private val montreal = ZoneId.of("America/Montreal")

    @Test
    fun `many laps on one day count as one daily median`() {
        val samples = buildList {
            repeat(20) { add(sample(1 + it.toLong(), "2026-07-01T12:00", 40_000 + it * 100L)) }
            add(sample(30, "2026-07-02T12:00", 60_000))
        }

        val daily = TrailProgressCalculator.dailyMedians(samples, montreal)

        assertEquals(2, daily.size)
        assertEquals(40_950L, daily[0].elapsedMillis)
        assertEquals(60_000L, daily[1].elapsedMillis)
    }

    @Test
    fun `local dates remain correct across daylight saving change`() {
        val samples = listOf(
            sample(1, "2026-03-07T23:30", 50_000),
            sample(2, "2026-03-08T00:30", 48_000),
        )

        val daily = TrailProgressCalculator.dailyMedians(samples, montreal)

        assertEquals(listOf(LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8)), daily.map { it.date })
    }

    @Test
    fun `thirty day comparison requires two riding days in each window`() {
        val today = LocalDate.of(2026, 7, 30)
        val insufficient = listOf(
            DailyEffortMedian(today.minusDays(2), 40_000),
            DailyEffortMedian(today.minusDays(32), 45_000),
            DailyEffortMedian(today.minusDays(35), 47_000),
        )
        assertFalse(TrailProgressCalculator.thirtyDayWindow(insufficient, today).hasEnoughEvidence)

        val sufficient = insufficient + DailyEffortMedian(today.minusDays(5), 42_000)
        val trend = TrailProgressCalculator.thirtyDayWindow(sufficient, today)
        assertTrue(trend.hasEnoughEvidence)
        assertEquals(-5_000L, trend.deltaMillis)
    }

    @Test
    fun `chart ranges preserve days then aggregate weeks and months`() {
        val today = LocalDate.of(2026, 12, 31)
        val daily = listOf(
            DailyEffortMedian(LocalDate.of(2026, 1, 2), 60_000),
            DailyEffortMedian(LocalDate.of(2026, 1, 3), 40_000),
            DailyEffortMedian(LocalDate.of(2026, 12, 28), 30_000),
            DailyEffortMedian(LocalDate.of(2026, 12, 29), 50_000),
        )

        assertEquals(2, TrailProgressCalculator.chartPoints(daily, TrendRange.THREE_MONTHS, today).size)
        assertEquals(2, TrailProgressCalculator.chartPoints(daily, TrendRange.ONE_YEAR, today).size)
        assertEquals(2, TrailProgressCalculator.chartPoints(daily, TrendRange.ALL_TIME, today).size)
        assertEquals(50_000L, TrailProgressCalculator.chartPoints(daily, TrendRange.ALL_TIME, today).first().elapsedMillis)
    }

    @Test
    fun `ninety day gap starts a new season and calculates opening regression`() {
        val previous = listOf(1, 2, 3, 4).map {
            DailyEffortMedian(LocalDate.of(2025, 7, it), (50 - it) * 1_000L)
        }
        val current = listOf(1, 2, 3).map {
            DailyEffortMedian(LocalDate.of(2026, 6, it), (55 - it) * 1_000L)
        }

        val comparison = TrailProgressCalculator.seasonComparison(previous + current)

        assertEquals(2, TrailProgressCalculator.seasons(previous + current).size)
        assertEquals(5_000L, comparison.seasonToDateDeltaMillis)
        assertEquals(6_000L, comparison.openingRegressionMillis)
    }

    @Test
    fun `season boundary is exactly ninety days`() {
        val start = LocalDate.of(2026, 1, 1)
        val withinSeason = listOf(
            DailyEffortMedian(start, 40_000),
            DailyEffortMedian(start.plusDays(89), 39_000),
        )
        val newSeason = withinSeason + DailyEffortMedian(start.plusDays(179), 38_000)

        assertEquals(1, TrailProgressCalculator.seasons(withinSeason).size)
        assertEquals(2, TrailProgressCalculator.seasons(newSeason).size)
    }

    @Test
    fun `season claims wait for three riding days`() {
        val daily = listOf(
            DailyEffortMedian(LocalDate.of(2025, 6, 1), 40_000),
            DailyEffortMedian(LocalDate.of(2025, 6, 2), 39_000),
            DailyEffortMedian(LocalDate.of(2025, 6, 3), 38_000),
            DailyEffortMedian(LocalDate.of(2026, 6, 1), 45_000),
            DailyEffortMedian(LocalDate.of(2026, 6, 2), 44_000),
        )

        val comparison = TrailProgressCalculator.seasonComparison(daily)

        assertNull(comparison.seasonToDateDeltaMillis)
        assertNull(comparison.openingRegressionMillis)
    }

    @Test
    fun `ideal run uses only valid efforts reached without an earlier stop`() {
        val efforts = listOf(
            effort(passId = 1, sectionId = 10, time = 30_000, nonstop = false),
            effort(passId = 2, sectionId = 10, time = 32_000, nonstop = true),
            effort(passId = 2, sectionId = 20, time = 40_000, nonstop = true),
        )

        val result = TrailProgressCalculator.idealRun(listOf(10, 20), efforts, mapOf(1L to 100L, 2L to 200L))

        assertTrue(result.complete)
        assertEquals(72_000L, result.totalMillis)
        assertEquals(setOf(2L), result.contributors.map { it.effort.passId }.toSet())
    }

    @Test
    fun `ideal run is incomplete when a split has no qualifying effort`() {
        val result = TrailProgressCalculator.idealRun(
            listOf(10, 20),
            listOf(effort(1, 10, 30_000, nonstop = true)),
            mapOf(1L to 100L),
        )

        assertFalse(result.complete)
        assertNull(result.totalMillis)
        assertEquals(listOf(20L), result.missingSectionIds)
    }

    private fun sample(passId: Long, local: String, elapsed: Long) = TimedEffortSample(
        passId,
        LocalDateTime.parse(local).atZone(montreal).toInstant().toEpochMilli(),
        elapsed,
    )

    private fun effort(passId: Long, sectionId: Long, time: Long, nonstop: Boolean) = SectionEffortEntity(
        passId = passId,
        sectionId = sectionId,
        elapsedMillis = time,
        entrySpeedMps = 5.0,
        minimumSpeedMps = 4.0,
        averageSpeedMps = 6.0,
        exitSpeedMps = 7.0,
        maximumSpeedMps = 8.0,
        sampleQuality = 90,
        lateralOffsetMeters = 0.0,
        lateralUncertaintyMeters = 2.0,
        valid = true,
        reachedWithoutPriorStop = nonstop,
    )
}
