package com.example.flightlog.ui

import com.example.flightlog.data.SectionEffortEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlin.math.roundToLong

internal data class TimedEffortSample(
    val passId: Long,
    val recordedAt: Long,
    val elapsedMillis: Long,
)

internal data class DailyEffortMedian(
    val date: LocalDate,
    val elapsedMillis: Long,
)

internal data class TrendWindow(
    val currentMedianMillis: Long?,
    val previousMedianMillis: Long?,
    val currentRidingDays: Int,
    val previousRidingDays: Int,
) {
    val hasEnoughEvidence: Boolean get() = currentRidingDays >= 2 && previousRidingDays >= 2
    val deltaMillis: Long? get() = if (hasEnoughEvidence) currentMedianMillis!! - previousMedianMillis!! else null
}

internal enum class TrendRange { THREE_MONTHS, ONE_YEAR, ALL_TIME }

internal data class TrailSeason(
    val startedOn: LocalDate,
    val days: List<DailyEffortMedian>,
)

internal data class SeasonComparison(
    val current: TrailSeason?,
    val previous: TrailSeason?,
    val seasonToDateDeltaMillis: Long?,
    val openingRegressionMillis: Long?,
)

internal data class IdealRunContributor(
    val sectionId: Long,
    val effort: SectionEffortEntity,
    val recordedAt: Long,
)

internal data class IdealRunResult(
    val contributors: List<IdealRunContributor>,
    val missingSectionIds: List<Long>,
) {
    val complete: Boolean get() = missingSectionIds.isEmpty() && contributors.isNotEmpty()
    val totalMillis: Long? get() = contributors.takeIf { complete }?.sumOf { it.effort.elapsedMillis }
}

internal object TrailProgressCalculator {
    private const val SEASON_GAP_DAYS = 90L

    fun dailyMedians(samples: List<TimedEffortSample>, zoneId: ZoneId): List<DailyEffortMedian> =
        samples.groupBy { Instant.ofEpochMilli(it.recordedAt).atZone(zoneId).toLocalDate() }
            .map { (date, values) -> DailyEffortMedian(date, median(values.map { it.elapsedMillis })!!) }
            .sortedBy { it.date }

    fun thirtyDayWindow(daily: List<DailyEffortMedian>, today: LocalDate): TrendWindow {
        val currentStart = today.minusDays(29)
        val previousStart = today.minusDays(59)
        val previousEnd = today.minusDays(30)
        val current = daily.filter { it.date in currentStart..today }
        val previous = daily.filter { it.date in previousStart..previousEnd }
        return TrendWindow(
            currentMedianMillis = median(current.map { it.elapsedMillis }),
            previousMedianMillis = median(previous.map { it.elapsedMillis }),
            currentRidingDays = current.size,
            previousRidingDays = previous.size,
        )
    }

    fun chartPoints(
        daily: List<DailyEffortMedian>,
        range: TrendRange,
        today: LocalDate,
    ): List<DailyEffortMedian> {
        val visible = when (range) {
            TrendRange.THREE_MONTHS -> daily.filter { it.date >= today.minusDays(89) }
            TrendRange.ONE_YEAR -> daily.filter { it.date >= today.minusDays(364) }
            TrendRange.ALL_TIME -> daily
        }
        return when (range) {
            TrendRange.THREE_MONTHS -> visible
            TrendRange.ONE_YEAR -> aggregate(visible) { it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
            TrendRange.ALL_TIME -> aggregate(visible) { YearMonth.from(it).atDay(1) }
        }
    }

    fun seasons(daily: List<DailyEffortMedian>): List<TrailSeason> {
        val sorted = daily.sortedBy { it.date }
        if (sorted.isEmpty()) return emptyList()
        val groups = mutableListOf(mutableListOf(sorted.first()))
        sorted.drop(1).forEach { day ->
            val gap = ChronoUnit.DAYS.between(groups.last().last().date, day.date)
            if (gap >= SEASON_GAP_DAYS) groups += mutableListOf(day) else groups.last() += day
        }
        return groups.map { TrailSeason(it.first().date, it.toList()) }
    }

    fun seasonComparison(daily: List<DailyEffortMedian>): SeasonComparison {
        val seasons = seasons(daily)
        val current = seasons.lastOrNull()
        val previous = seasons.dropLast(1).lastOrNull()
        if (current == null || previous == null) return SeasonComparison(current, previous, null, null)

        val alignedCount = current.days.size
        val seasonDelta = if (alignedCount >= 3 && previous.days.size >= alignedCount) {
            median(current.days.map { it.elapsedMillis })!! -
                median(previous.days.take(alignedCount).map { it.elapsedMillis })!!
        } else null
        val openingRegression = if (current.days.size >= 3 && previous.days.size >= 3) {
            median(current.days.take(3).map { it.elapsedMillis })!! -
                median(previous.days.takeLast(3).map { it.elapsedMillis })!!
        } else null
        return SeasonComparison(current, previous, seasonDelta, openingRegression)
    }

    fun idealRun(
        sectionIds: List<Long>,
        efforts: List<SectionEffortEntity>,
        passStartedAt: Map<Long, Long>,
    ): IdealRunResult {
        val contributors = mutableListOf<IdealRunContributor>()
        val missing = mutableListOf<Long>()
        val qualifyingBySection = efforts.asSequence()
            .filter { it.valid && it.reachedWithoutPriorStop && it.passId in passStartedAt }
            .groupBy { it.sectionId }
        sectionIds.forEach { sectionId ->
            val best = qualifyingBySection[sectionId]?.minByOrNull { it.elapsedMillis }
            if (best == null) missing += sectionId
            else contributors += IdealRunContributor(sectionId, best, passStartedAt.getValue(best.passId))
        }
        return IdealRunResult(contributors, missing)
    }

    private fun aggregate(
        daily: List<DailyEffortMedian>,
        bucket: (LocalDate) -> LocalDate,
    ): List<DailyEffortMedian> = daily.groupBy { bucket(it.date) }
        .map { (date, values) -> DailyEffortMedian(date, median(values.map { it.elapsedMillis })!!) }
        .sortedBy { it.date }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val center = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[center]
        else ((sorted[center - 1].toDouble() + sorted[center].toDouble()) / 2.0).roundToLong()
    }
}
