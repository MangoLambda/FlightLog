package com.example.flightlog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.flightlog.data.SectionEffortEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.domain.RoughnessKind
import com.example.flightlog.invalidEffortMessage
import com.example.flightlog.ui.theme.Amber
import com.example.flightlog.ui.theme.TrailCyan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun SplitOverviewCard(
    section: TrailSectionEntity,
    passes: List<TrailPassEntity>,
    allEfforts: List<SectionEffortEntity>,
    features: List<TrailSectionEntity>,
    imperial: Boolean,
    noEarlierStops: Boolean,
    onNoEarlierStops: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val passById = remember(passes) { passes.associateBy { it.id } }
    val recorded = remember(section.id, allEfforts, passById) {
        allEfforts.filter { it.sectionId == section.id && it.passId in passById }
            .sortedByDescending { passById[it.passId]?.startedAt }
    }
    val valid = recorded.filter { it.valid }
    val canFilter = valid.any { !it.reachedWithoutPriorStop }
    val displayed = valid.filter { !noEarlierStops || it.reachedWithoutPriorStop }
    val latest = displayed.firstOrNull()
    val best = displayed.minByOrNull { it.elapsedMillis }
    val samples = displayed.mapNotNull { effort ->
        passById[effort.passId]?.let { TimedEffortSample(effort.passId, it.startedAt, effort.elapsedMillis) }
    }
    val trend = remember(samples) {
        TrailProgressCalculator.thirtyDayWindow(
            TrailProgressCalculator.dailyMedians(samples, ZoneId.systemDefault()),
            LocalDate.now(),
        )
    }
    var detailsExpanded by rememberSaveable(section.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(section.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Split • ${(section.endMeters - section.startMeters).toInt()} m",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Rename")
                }
            }
            if (canFilter) {
                FilterChip(
                    selected = noEarlierStops,
                    onClick = { onNoEarlierStops(!noEarlierStops) },
                    label = { Text("No earlier stops") },
                )
            }
            if (latest == null) {
                Text(
                    if (noEarlierStops && valid.isNotEmpty()) "No effort reached this split without an earlier stop."
                    else if (recorded.isEmpty()) "No ride has covered this entire split yet."
                    else invalidEffortMessage(recorded),
                    color = Amber,
                )
            } else {
                Surface(color = TrailCyan.copy(alpha = .10f), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("LATEST", color = TrailCyan, style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            ProgressMetric("TIME", formatSplitTime(latest.elapsedMillis))
                            ProgressMetric("PERSONAL BEST", formatSplitTime(best!!.elapsedMillis))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            ProgressMetric("ENTRY", formatSplitSpeed(latest.entrySpeedMps, imperial))
                            ProgressMetric("EXIT", formatSplitSpeed(latest.exitSpeedMps, imperial))
                        }
                        Text("Minimum ${formatSplitSpeed(latest.minimumSpeedMps, imperial)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TrendHeadline(trend)
                TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Icon(if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    Text(if (detailsExpanded) "Hide details" else "More details")
                }
                AnimatedVisibility(detailsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "Average ${formatSplitSpeed(latest.averageSpeedMps, imperial)} • maximum ${formatSplitSpeed(latest.maximumSpeedMps, imperial)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "GPS signal quality ${latest.sampleQuality}%${if (latest.estimated) " • estimated across ${formatSplitTime(latest.bridgedGapMillis)}" else ""}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        latest.roughnessScore?.let { score ->
                            val label = if (latest.roughnessKind == RoughnessKind.BIKE_ROUGHNESS) "Bike roughness" else "Pocket disturbance"
                            Text("$label ${String.format(Locale.US, "%.2f", score)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (features.isNotEmpty()) {
                            Text("Features: ${features.joinToString { it.name }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (samples.isNotEmpty()) LongTermProgressCard(samples)
    }
}

@Composable
internal fun FullRunsOverview(
    passes: List<TrailPassEntity>,
    efforts: List<SectionEffortEntity>,
    wholeTrailSection: TrailSectionEntity?,
    splitSections: List<TrailSectionEntity>,
    imperial: Boolean,
    onCompare: () -> Unit,
) {
    val qualified = remember(passes) { passes.filter { it.fullRunEligible }.sortedByDescending { it.startedAt } }
    val qualifiedIds = remember(qualified) { qualified.mapTo(hashSetOf()) { it.id } }
    val wholeEfforts = remember(efforts, wholeTrailSection?.id, qualifiedIds) {
        efforts.filter { it.sectionId == wholeTrailSection?.id && it.passId in qualifiedIds && it.valid }.associateBy { it.passId }
    }
    val latestPass = qualified.firstOrNull { it.id in wholeEfforts }
    val latest = latestPass?.let { wholeEfforts[it.id] }
    val best = wholeEfforts.values.minByOrNull { it.elapsedMillis }
    val samples = qualified.mapNotNull { pass ->
        wholeEfforts[pass.id]?.let { TimedEffortSample(pass.id, pass.startedAt, it.elapsedMillis) }
    }
    val trend = remember(samples) {
        TrailProgressCalculator.thirtyDayWindow(
            TrailProgressCalculator.dailyMedians(samples, ZoneId.systemDefault()), LocalDate.now(),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Full runs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${qualified.size} complete nonstop ${if (qualified.size == 1) "run" else "runs"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (latest == null || best == null) {
                Text("No full run yet. Cover the entire trail without a 10-second stop.", color = Amber)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ProgressMetric("LATEST", formatSplitTime(latest.elapsedMillis))
                    ProgressMetric("PERSONAL BEST", formatSplitTime(best.elapsedMillis))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ProgressMetric("ENTRY", formatSplitSpeed(latest.entrySpeedMps, imperial))
                    ProgressMetric("EXIT", formatSplitSpeed(latest.exitSpeedMps, imperial))
                }
                if (latest.estimated) Text("Estimated across ${formatSplitTime(latest.bridgedGapMillis)} of bridged GPS", color = Amber)
                TrendHeadline(trend)
            }
            Button(onClick = onCompare, enabled = samples.size >= 2, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.CompareArrows, null)
                Spacer(Modifier.width(8.dp))
                Text("Compare two rides")
            }
        }
    }
    if (samples.isNotEmpty()) LongTermProgressCard(samples)
    if (latestPass != null && latest != null) {
        val splitIds = splitSections.mapTo(hashSetOf()) { it.id }
        val breakdown = efforts.filter { it.passId == latestPass.id && it.sectionId in splitIds && it.valid }
        val transitionMillis = (latest.elapsedMillis - breakdown.sumOf { it.elapsedMillis }).coerceAtLeast(0)
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Latest full-run breakdown", fontWeight = FontWeight.Bold)
                splitSections.forEach { split ->
                    val effort = breakdown.firstOrNull { it.sectionId == split.id }
                    BreakdownRow(split.name, effort?.let { formatSplitTime(it.elapsedMillis) } ?: "—")
                }
                BreakdownRow("Through pause zones", formatSplitTime(transitionMillis), secondary = true)
                BreakdownRow("End to end", formatSplitTime(latest.elapsedMillis), emphasized = true)
            }
        }
    }
    }
}

@Composable
internal fun IdealRunCard(
    splitSections: List<TrailSectionEntity>,
    passes: List<TrailPassEntity>,
    efforts: List<SectionEffortEntity>,
    wholeTrailSection: TrailSectionEntity?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val passStartedAt = remember(passes) { passes.associate { it.id to it.startedAt } }
    val result = remember(splitSections, efforts, passStartedAt) {
        TrailProgressCalculator.idealRun(splitSections.map { it.id }, efforts, passStartedAt)
    }
    val qualifiedIds = passes.filter { it.fullRunEligible }.mapTo(hashSetOf()) { it.id }
    val actualBest = efforts.filter { it.sectionId == wholeTrailSection?.id && it.passId in qualifiedIds && it.valid }
        .minOfOrNull { it.elapsedMillis }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Ideal run", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Fastest qualifying split from multiple rides", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    Text(if (expanded) "Hide" else "View")
                }
            }
            if (result.complete) {
                Text(formatSplitTime(result.totalMillis!!), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text(
                    if (actualBest == null) "No recorded full run to compare yet."
                    else "${formatSignedTime(actualBest - result.totalMillis!!)} between your full-run PB and this ideal.",
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                Text("Needs a no-earlier-stop effort for ${result.missingSectionIds.size} ${if (result.missingSectionIds.size == 1) "split" else "splits"}.", color = Amber)
            }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("This is theoretical and was not recorded as one ride.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result.contributors.forEach { contributor ->
                        val split = splitSections.firstOrNull { it.id == contributor.sectionId }
                        BreakdownRow(
                            split?.name ?: "Split",
                            "${formatSplitTime(contributor.effort.elapsedMillis)} • ${formatProgressDate(contributor.recordedAt)}",
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LongTermProgressCard(samples: List<TimedEffortSample>) {
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val daily = remember(samples, zone) { TrailProgressCalculator.dailyMedians(samples, zone) }
    val window = remember(daily, today) { TrailProgressCalculator.thirtyDayWindow(daily, today) }
    val season = remember(daily) { TrailProgressCalculator.seasonComparison(daily) }
    var range by rememberSaveable { mutableStateOf(TrendRange.THREE_MONTHS) }
    val points = remember(daily, range, today) { TrailProgressCalculator.chartPoints(daily, range, today) }
    val best = samples.minOfOrNull { it.elapsedMillis }

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Long-term progress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TrendHeadline(window)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TrendRange.entries.forEach { value ->
                    FilterChip(
                        selected = range == value,
                        onClick = { range = value },
                        label = { Text(when (value) {
                            TrendRange.THREE_MONTHS -> "3 months"
                            TrendRange.ONE_YEAR -> "1 year"
                            TrendRange.ALL_TIME -> "All time"
                        }) },
                    )
                }
            }
            TrendChart(points, best, range)
            SeasonSummary(season)
        }
    }
}

@Composable
private fun TrendHeadline(window: TrendWindow) {
    val delta = window.deltaMillis
    Text(
        when {
            delta == null -> "30-day trend needs at least 2 riding days in both periods (${window.currentRidingDays}/2 recent, ${window.previousRidingDays}/2 previous)."
            delta < 0 -> "30-day trend: ${formatSplitTime(abs(delta))} faster than the previous 30 days."
            delta > 0 -> "30-day trend: ${formatSplitTime(delta)} slower than the previous 30 days."
            else -> "30-day trend: unchanged from the previous 30 days."
        },
        color = if (delta != null && delta <= 0) TrailCyan else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (delta != null) FontWeight.Bold else FontWeight.Normal,
    )
}

@Composable
private fun TrendChart(points: List<DailyEffortMedian>, personalBest: Long?, range: TrendRange) {
    val lineColor = TrailCyan
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val description = if (points.isEmpty()) "No trend data for this range" else {
        val first = points.first()
        val last = points.last()
        "Trend chart with ${points.size} points from ${first.date} at ${formatSplitTime(first.elapsedMillis)} to ${last.date} at ${formatSplitTime(last.elapsedMillis)}; lower times are faster"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Faster ↑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            personalBest?.let { Text("PB ${formatSplitTime(it)}", style = MaterialTheme.typography.labelSmall, color = TrailCyan) }
        }
        Canvas(
            Modifier.fillMaxWidth().height(130.dp).semantics { contentDescription = description },
        ) {
            repeat(4) { index ->
                val y = size.height * index / 3f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            if (points.isEmpty()) return@Canvas
            val min = minOf(points.minOf { it.elapsedMillis }, personalBest ?: Long.MAX_VALUE).toFloat()
            val max = points.maxOf { it.elapsedMillis }.toFloat()
            val span = (max - min).coerceAtLeast(1f)
            val firstDay = points.minOf { it.date.toEpochDay() }
            val daySpan = (points.maxOf { it.date.toEpochDay() } - firstDay).coerceAtLeast(1L)
            personalBest?.let { best ->
                val y = 8f + (best - min) / span * (size.height - 16f)
                drawLine(
                    lineColor.copy(alpha = .55f), Offset(0f, y), Offset(size.width, y),
                    strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
            val path = Path()
            var firstPoint = true
            points.forEach { point ->
                val x = if (points.size == 1) size.width / 2f
                else size.width * (point.date.toEpochDay() - firstDay) / daySpan.toFloat()
                val y = 8f + (point.elapsedMillis - min) / span * (size.height - 16f)
                if (firstPoint) {
                    path.moveTo(x, y)
                    firstPoint = false
                } else path.lineTo(x, y)
                drawCircle(lineColor, radius = 4f, center = Offset(x, y))
            }
            drawPath(path, lineColor, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
        Text(
            when (range) {
                TrendRange.THREE_MONTHS -> "One point per riding day"
                TrendRange.ONE_YEAR -> "Weekly medians; every riding day counts once"
                TrendRange.ALL_TIME -> "Monthly medians; every riding day counts once"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SeasonSummary(comparison: SeasonComparison) {
    val current = comparison.current
    val previous = comparison.previous
    if (current == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Season progress", fontWeight = FontWeight.Bold)
        if (previous == null) {
            Text("This is the first recorded season for this trail.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val delta = comparison.seasonToDateDeltaMillis
        Text(
            if (delta == null) "Year-over-year needs at least 3 current-season riding days and the same number last season."
            else if (delta <= 0) "Year over year: ${formatSplitTime(abs(delta))} faster at the same stage of the season."
            else "Year over year: ${formatSplitTime(delta)} slower at the same stage of the season.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val opening = comparison.openingRegressionMillis
        Text(
            if (opening == null) "Opening regression appears after 3 riding days in both seasons."
            else if (opening >= 0) "Opening regression: ${formatSplitTime(opening)} slower than the end of last season."
            else "Season opening: ${formatSplitTime(abs(opening))} faster than the end of last season.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SeasonAlignmentChart(current, previous)
    }
}

@Composable
private fun SeasonAlignmentChart(current: TrailSeason, previous: TrailSeason) {
    val currentColor = TrailCyan
    val previousColor = Amber
    val maxCount = maxOf(current.days.size, previous.days.size)
    val values = current.days.map { it.elapsedMillis } + previous.days.map { it.elapsedMillis }
    val min = values.minOrNull()?.toFloat() ?: return
    val max = values.maxOrNull()?.toFloat() ?: return
    val span = (max - min).coerceAtLeast(1f)
    fun description() = "Season chart: current season ${current.days.size} riding days, previous season ${previous.days.size} riding days; aligned by riding-day number"
    Canvas(Modifier.fillMaxWidth().height(92.dp).semantics { contentDescription = description() }) {
        fun drawSeries(days: List<DailyEffortMedian>, color: Color, dashed: Boolean) {
            if (days.isEmpty()) return
            val path = Path()
            days.forEachIndexed { index, day ->
                val x = if (maxCount <= 1) size.width / 2f else size.width * index / (maxCount - 1f)
                val y = 6f + (day.elapsedMillis - min) / span * (size.height - 12f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = if (dashed) 2f else 4f, cap = StrokeCap.Round))
        }
        drawSeries(previous.days, previousColor, true)
        drawSeries(current.days, currentColor, false)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("— Current season", color = TrailCyan, style = MaterialTheme.typography.labelSmall)
        Text("– Previous season", color = Amber, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ProgressMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BreakdownRow(name: String, value: String, secondary: Boolean = false, emphasized: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal)
        Text(value, color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified, fontWeight = FontWeight.Bold)
    }
}

internal fun formatProgressDate(timestamp: Long): String = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
