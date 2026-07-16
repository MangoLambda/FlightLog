package com.example.flightlog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.SectionEffortEntity
import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.TrailEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.maps.MapStyle
import com.example.flightlog.tracking.TrailAnalysis
import com.example.flightlog.ui.theme.Amber
import com.example.flightlog.ui.theme.TrailCyan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal data class ComparisonSpeedPoint(val distanceMeters: Double, val speedMps: Double)

internal data class SplitComparison(
    val section: TrailSectionEntity,
    val effortA: SectionEffortEntity,
    val effortB: SectionEffortEntity,
)

internal fun eligibleComparisonPasses(
    fullRuns: Boolean,
    passes: List<TrailPassEntity>,
    efforts: List<SectionEffortEntity>,
    wholeSectionId: Long?,
    splitIds: Set<Long>,
): List<TrailPassEntity> {
    val passesWithData = efforts.asSequence()
        .filter { it.valid && if (fullRuns) it.sectionId == wholeSectionId else it.sectionId in splitIds }
        .mapTo(hashSetOf()) { it.passId }
    return passes.filter { it.id in passesWithData && (!fullRuns || it.fullRunEligible) }
        .sortedByDescending { it.startedAt }
}

internal fun comparableSplits(
    splits: List<TrailSectionEntity>,
    effortsA: List<SectionEffortEntity>,
    effortsB: List<SectionEffortEntity>,
): List<SplitComparison> = splits.mapNotNull { split ->
    val a = effortsA.firstOrNull { it.sectionId == split.id && it.valid }
    val b = effortsB.firstOrNull { it.sectionId == split.id && it.valid }
    if (a == null || b == null) null else SplitComparison(split, a, b)
}

internal fun pauseTransitionMillis(whole: SectionEffortEntity, splits: List<SectionEffortEntity>): Long =
    (whole.elapsedMillis - splits.sumOf { it.elapsedMillis }).coerceAtLeast(0)

internal fun normalizedSpeedSeries(
    profiles: List<SpatialProfileEntity>,
    canonical: List<SpatialProfileEntity>,
    startMeters: Double,
    endMeters: Double,
): List<ComparisonSpeedPoint> {
    val match = TrailAnalysis.match(profiles, canonical) ?: return emptyList()
    return match.normalized.asSequence()
        .filter { (_, reference) -> reference.distanceMeters in startMeters..endMeters }
        .groupBy { it.second.distanceBin }
        .map { (_, samples) ->
            ComparisonSpeedPoint(
                samples.map { it.second.distanceMeters }.average(),
                samples.map { it.first.speedMps }.average(),
            )
        }
        .sortedBy { it.distanceMeters }
}

private fun normalizedMapRoute(
    profiles: List<SpatialProfileEntity>,
    canonical: List<SpatialProfileEntity>,
    startMeters: Double,
    endMeters: Double,
) = TrailAnalysis.match(profiles, canonical)?.normalized.orEmpty()
    .filter { (_, reference) -> reference.distanceMeters in startMeters..endMeters }
    .distinctBy { it.second.distanceBin }
    .sortedBy { it.second.distanceMeters }
    .map { it.first.asTrackPoint() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrailComparisonScreen(
    resultTab: TrailResultTab,
    trail: TrailEntity,
    sections: List<TrailSectionEntity>,
    passes: List<TrailPassEntity>,
    efforts: List<SectionEffortEntity>,
    rides: List<RideEntity>,
    canonicalProfiles: List<SpatialProfileEntity>,
    profilesA: List<SpatialProfileEntity>,
    profilesB: List<SpatialProfileEntity>,
    selectedPassAId: Long?,
    selectedPassBId: Long?,
    onPassA: (Long) -> Unit,
    onPassB: (Long) -> Unit,
    imperial: Boolean,
    mapApiKey: String,
    mapStyle: MapStyle,
    onDismiss: () -> Unit,
) {
    val splitSections = remember(sections) { sections.filter { it.kind == SectionKind.SPLIT }.sortedBy { it.startMeters } }
    val wholeSection = sections.firstOrNull { it.kind == SectionKind.WHOLE_TRAIL }
    val splitIds = remember(splitSections) { splitSections.mapTo(hashSetOf()) { it.id } }
    val eligible = remember(resultTab, passes, efforts, wholeSection?.id, splitIds) {
        eligibleComparisonPasses(resultTab == TrailResultTab.FULL_RUNS, passes, efforts, wholeSection?.id, splitIds)
    }
    LaunchedEffect(eligible.map { it.id }, resultTab) {
        val ids = eligible.map { it.id }
        if (selectedPassAId !in ids) ids.firstOrNull()?.let(onPassA)
        if (selectedPassBId !in ids || selectedPassBId == selectedPassAId) ids.firstOrNull { it != selectedPassAId }?.let(onPassB)
    }
    var picker by remember { mutableStateOf<Char?>(null) }
    var selectedSplitIndex by rememberSaveable(trail.id, resultTab) { mutableStateOf(0) }
    val safeIndex = selectedSplitIndex.coerceIn(0, (splitSections.size - 1).coerceAtLeast(0))
    val selectedSplit = splitSections.getOrNull(safeIndex)
    val passA = eligible.firstOrNull { it.id == selectedPassAId }
    val passB = eligible.firstOrNull { it.id == selectedPassBId }
    val rideById = remember(rides) { rides.associateBy { it.id } }
    val effortsByPass = remember(efforts) { efforts.filter { it.valid }.groupBy { it.passId } }
    val effortsA = passA?.id?.let { effortsByPass[it] }.orEmpty()
    val effortsB = passB?.id?.let { effortsByPass[it] }.orEmpty()
    val comparable = comparableSplits(splitSections, effortsA, effortsB)
    val wholeA = effortsA.firstOrNull { it.sectionId == wholeSection?.id }
    val wholeB = effortsB.firstOrNull { it.sectionId == wholeSection?.id }
    val overallA = if (resultTab == TrailResultTab.FULL_RUNS) wholeA?.elapsedMillis else comparable.sumOf { it.effortA.elapsedMillis }.takeIf { comparable.isNotEmpty() }
    val overallB = if (resultTab == TrailResultTab.FULL_RUNS) wholeB?.elapsedMillis else comparable.sumOf { it.effortB.elapsedMillis }.takeIf { comparable.isNotEmpty() }
    val biggest = comparable.maxByOrNull { abs(it.effortB.elapsedMillis - it.effortA.elapsedMillis) }
    val splitRows = splitSections.map { split ->
        Triple(
            split,
            effortsA.firstOrNull { it.sectionId == split.id && it.valid },
            effortsB.firstOrNull { it.sectionId == split.id && it.valid },
        )
    }
    val selectedA = selectedSplit?.let { split -> effortsA.firstOrNull { it.sectionId == split.id } }
    val selectedB = selectedSplit?.let { split -> effortsB.firstOrNull { it.sectionId == split.id } }
    val routeA = remember(profilesA, canonicalProfiles, trail.startMeters, trail.endMeters) {
        normalizedMapRoute(profilesA, canonicalProfiles, trail.startMeters, trail.endMeters)
    }
    val routeB = remember(profilesB, canonicalProfiles, trail.startMeters, trail.endMeters) {
        normalizedMapRoute(profilesB, canonicalProfiles, trail.startMeters, trail.endMeters)
    }
    val speedA = remember(profilesA, canonicalProfiles, selectedSplit) {
        selectedSplit?.let { normalizedSpeedSeries(profilesA, canonicalProfiles, it.startMeters, it.endMeters) }.orEmpty()
    }
    val speedB = remember(profilesB, canonicalProfiles, selectedSplit) {
        selectedSplit?.let { normalizedSpeedSeries(profilesB, canonicalProfiles, it.startMeters, it.endMeters) }.orEmpty()
    }

    picker?.let { label ->
        RunPickerSheet(
            label = label,
            passes = eligible,
            selectedId = if (label == 'A') selectedPassAId else selectedPassBId,
            otherId = if (label == 'A') selectedPassBId else selectedPassAId,
            rides = rideById,
            effortsByPass = effortsByPass,
            splitIds = splitIds,
            wholeSectionId = wholeSection?.id,
            fullRuns = resultTab == TrailResultTab.FULL_RUNS,
            onDismiss = { picker = null },
            onSelect = {
                if (label == 'A') onPassA(it) else onPassB(it)
                picker = null
            },
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopAppBar(
                    title = { Text(if (resultTab == TrailResultTab.FULL_RUNS) "Compare full runs" else "Compare splits") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    colors = flightLogTopAppBarColors(),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RunPickerButton('A', passA, Modifier.weight(1f)) { picker = 'A' }
                            RunPickerButton('B', passB, Modifier.weight(1f)) { picker = 'B' }
                        }
                    }
                    if (routeA.isNotEmpty() || routeB.isNotEmpty()) {
                        item {
                            TrailMap(
                                points = routeA,
                                comparisonPoints = routeB,
                                jumps = emptyList(), apiKey = mapApiKey, mapStyle = mapStyle,
                                modifier = Modifier.fillMaxWidth().height(220.dp), fitRoute = true,
                            )
                        }
                        item { ComparisonLegend() }
                    }
                    if (resultTab == TrailResultTab.SPLITS && splitSections.isNotEmpty()) item {
                        SplitRouteScrubber(
                            trail.startMeters, trail.endMeters, splitSections, emptyList(), safeIndex,
                            onSelect = { selectedSplitIndex = it },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    item {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            ComparisonHeadline(
                                label = if (resultTab == TrailResultTab.FULL_RUNS) "End to end" else "Comparable splits",
                                timeA = overallA,
                                timeB = overallB,
                                biggest = biggest,
                            )
                        }
                    }
                    if (selectedSplit != null) item {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            SelectedSplitComparison(selectedSplit, selectedA, selectedB, imperial)
                        }
                    }
                    if (selectedSplit != null && (speedA.isNotEmpty() || speedB.isNotEmpty())) item {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            ComparisonSpeedChart(selectedSplit.name, speedA, speedB, imperial)
                        }
                    }
                    if (splitRows.isNotEmpty()) item {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            Card {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Split deltas", fontWeight = FontWeight.Bold)
                                    splitRows.forEach { (split, a, b) ->
                                        OutlinedButton(
                                            onClick = { selectedSplitIndex = splitSections.indexOf(split) },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(split.name, Modifier.weight(1f))
                                            Text(if (a == null || b == null) "—" else formatSignedTime(b.elapsedMillis - a.elapsedMillis), fontWeight = FontWeight.Bold)
                                            if (biggest?.section?.id == split.id) {
                                                Spacer(Modifier.width(6.dp))
                                                Text("Largest", style = MaterialTheme.typography.labelSmall, color = Amber)
                                            }
                                        }
                                    }
                                    if (resultTab == TrailResultTab.FULL_RUNS && wholeA != null && wholeB != null) {
                                        val transitionA = pauseTransitionMillis(wholeA, effortsA.filter { it.sectionId in splitIds })
                                        val transitionB = pauseTransitionMillis(wholeB, effortsB.filter { it.sectionId in splitIds })
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Through pause zones", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(formatSignedTime(transitionB - transitionA), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunPickerButton(label: Char, pass: TrailPassEntity?, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(58.dp)) {
        Column(Modifier.weight(1f)) {
            Text("RUN $label", style = MaterialTheme.typography.labelSmall)
            Text(pass?.let { formatComparisonDate(it.startedAt) } ?: "Choose ride", maxLines = 1)
        }
        Icon(Icons.Default.ExpandMore, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunPickerSheet(
    label: Char,
    passes: List<TrailPassEntity>,
    selectedId: Long?,
    otherId: Long?,
    rides: Map<Long, RideEntity>,
    effortsByPass: Map<Long, List<SectionEffortEntity>>,
    splitIds: Set<Long>,
    wholeSectionId: Long?,
    fullRuns: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Choose run $label", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(480.dp)) {
            items(passes, key = { it.id }) { pass ->
                val ride = rides[pass.rideId]
                val passEfforts = effortsByPass[pass.id].orEmpty()
                val time = if (fullRuns) passEfforts.firstOrNull { it.sectionId == wholeSectionId }?.elapsedMillis
                else passEfforts.filter { it.sectionId in splitIds }.sumOf { it.elapsedMillis }.takeIf { it > 0 }
                val estimated = passEfforts.any { it.estimated }
                OutlinedButton(
                    onClick = { onSelect(pass.id) },
                    enabled = pass.id != otherId,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(formatComparisonDateTime(pass.startedAt), fontWeight = FontWeight.Bold)
                        Text(
                            buildList {
                                time?.let { add(formatSplitTime(it)) }
                                if (pass.stopCount > 0) add("${pass.stopCount} stop${if (pass.stopCount == 1) "" else "s"}")
                                if (estimated) add("estimated GPS")
                                ride?.mountingMode?.let { add(if (it == MountingMode.BIKE_MOUNTED) "bike mounted" else "pocket") }
                            }.joinToString(" • ").ifBlank { "Matched ride" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pass.id == selectedId) Icon(Icons.Default.Check, "Selected")
                }
            }
        }
    }
}

@Composable
private fun ComparisonLegend() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("A — solid", color = TrailCyan, fontWeight = FontWeight.Bold)
        Text("B – – dashed", color = Amber, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ComparisonHeadline(
    label: String,
    timeA: Long?,
    timeB: Long?,
    biggest: SplitComparison?,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (timeA == null || timeB == null) Text("Choose two rides with comparable data.", color = Amber)
            else {
                val delta = timeB - timeA
                Text(
                    if (delta <= 0) "Run B was ${formatSplitTime(abs(delta))} faster"
                    else "Run A was ${formatSplitTime(delta)} faster",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("A ${formatSplitTime(timeA)}", color = TrailCyan, fontWeight = FontWeight.Bold)
                    Text("B ${formatSplitTime(timeB)}", color = Amber, fontWeight = FontWeight.Bold)
                }
                biggest?.let { Text("Largest difference: ${it.section.name} (${formatSignedTime(it.effortB.elapsedMillis - it.effortA.elapsedMillis)})") }
            }
        }
    }
}

@Composable
private fun SelectedSplitComparison(
    split: TrailSectionEntity,
    a: SectionEffortEntity?,
    b: SectionEffortEntity?,
    imperial: Boolean,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(split.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (a == null || b == null) Text("This split is not valid in both selected rides.", color = Amber)
            else {
                Text("A  ${formatSplitTime(a.elapsedMillis)} • ${formatSplitSpeed(a.entrySpeedMps, imperial)} entry • ${formatSplitSpeed(a.exitSpeedMps, imperial)} exit", color = TrailCyan)
                Text("B  ${formatSplitTime(b.elapsedMillis)} • ${formatSplitSpeed(b.entrySpeedMps, imperial)} entry • ${formatSplitSpeed(b.exitSpeedMps, imperial)} exit", color = Amber)
                val faster = if (a.elapsedMillis <= b.elapsedMillis) "A" else "B"
                Text(
                    if (TrailAnalysis.lineDifferenceIsReliable(a, b)) "$faster was faster and the traces are spatially distinct."
                    else "$faster was faster; GPS cannot confirm that the physical lines differed.",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ComparisonSpeedChart(
    splitName: String,
    a: List<ComparisonSpeedPoint>,
    b: List<ComparisonSpeedPoint>,
    imperial: Boolean,
) {
    val maxSpeed = (a + b).maxOfOrNull { it.speedMps }?.coerceAtLeast(1.0) ?: 1.0
    val minDistance = (a + b).minOfOrNull { it.distanceMeters } ?: 0.0
    val maxDistance = (a + b).maxOfOrNull { it.distanceMeters } ?: minDistance + 1.0
    val distanceSpan = (maxDistance - minDistance).coerceAtLeast(1.0)
    val description = "$splitName speed chart. Run A ranges ${speedRangeDescription(a, imperial)}; run B ranges ${speedRangeDescription(b, imperial)}."
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Speed through the split", fontWeight = FontWeight.Bold)
            Text("Faster ↑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = description }) {
                repeat(4) { index ->
                    val y = size.height * index / 3f
                    drawLine(Color.Gray.copy(alpha = .25f), Offset(0f, y), Offset(size.width, y), 1f)
                }
                fun drawSeries(points: List<ComparisonSpeedPoint>, color: Color, dashed: Boolean) {
                    if (points.isEmpty()) return
                    val path = Path()
                    points.forEachIndexed { index, point ->
                        val x = ((point.distanceMeters - minDistance) / distanceSpan * size.width).toFloat()
                        val y = (size.height - point.speedMps / maxSpeed * (size.height - 8f)).toFloat()
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path, color,
                        style = Stroke(
                            width = 4f,
                            cap = StrokeCap.Round,
                            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null,
                        ),
                    )
                }
                drawSeries(a, TrailCyan, false)
                drawSeries(b, Amber, true)
            }
            ComparisonLegend()
        }
    }
}

private fun speedRangeDescription(points: List<ComparisonSpeedPoint>, imperial: Boolean): String =
    if (points.isEmpty()) "unavailable"
    else "${formatSplitSpeed(points.minOf { it.speedMps }, imperial)} to ${formatSplitSpeed(points.maxOf { it.speedMps }, imperial)}"

private fun formatComparisonDate(timestamp: Long): String = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

private fun formatComparisonDateTime(timestamp: Long): String = DateTimeFormatter.ofPattern("EEE, MMM d • h:mm a", Locale.getDefault())
    .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
