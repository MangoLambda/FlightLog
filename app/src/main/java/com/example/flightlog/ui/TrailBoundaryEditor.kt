package com.example.flightlog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.TrailDefinitionDraft
import com.example.flightlog.data.TrailEditImpact
import com.example.flightlog.data.TrailCustomItemKind
import com.example.flightlog.maps.MapStyle
import com.example.flightlog.ui.theme.Amber
import com.example.flightlog.ui.theme.Lime
import com.example.flightlog.ui.theme.TrailCyan
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlinx.coroutines.launch

private const val MINIMUM_TRAIL_LENGTH_METERS = 10.0
private const val STOP_MINIMUM_DURATION_MILLIS = 10_000L
private const val STOP_MAXIMUM_ACCURACY_METERS = 25f
private const val STOP_MINIMUM_CORRIDOR_METERS = 15.0
private const val STOP_DEDUPLICATION_METERS = 15.0

internal data class TrailBounds(val startMeters: Double, val endMeters: Double) {
    val lengthMeters: Double get() = endMeters - startMeters
}

@Composable
private fun TrailImpactDialog(
    impact: TrailEditImpact,
    working: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(if (impact.draft.trailId == null) "Create this trail?" else "Save trail changes?") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (impact.draft.trailId != null) {
                    Text(
                        "Matched rides: ${impact.previousMatchedRideCount} → ${impact.matchedRideCount}\n" +
                            "Complete rides: ${impact.previousCompleteRideCount} → ${impact.completeRideCount}",
                    )
                } else {
                    Text("${impact.matchedRideCount} matched · ${impact.completeRideCount} complete")
                }
                if (impact.preservedItems.isNotEmpty()) {
                    Text("Kept", fontWeight = FontWeight.Bold)
                    impact.preservedItems.forEach { item ->
                        Text("• ${item.name} (${customItemLabel(item.kind)})", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (impact.removedItems.isNotEmpty()) {
                    Text("Will be removed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    impact.removedItems.forEach { item ->
                        Text("• ${item.name} (${customItemLabel(item.kind)})", color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "These items cannot be mapped inside the new trail boundaries.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Automatic matches, splits, efforts, and pause suggestions will be rebuilt.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !working) {
                if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (impact.draft.trailId == null) "Create trail" else "Save changes")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } },
    )
}

private fun customItemLabel(kind: TrailCustomItemKind): String = when (kind) {
    TrailCustomItemKind.SECTION -> "manual section"
    TrailCustomItemKind.PAUSE_ZONE -> "pause zone"
}

private val TrailRideDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")

private fun rideLabel(ride: RideEntity?, fallbackId: Long, imperial: Boolean): String = ride?.let {
    "${TrailRideDateFormatter.format(Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()))} · " +
        formatBoundaryDistance(it.distanceMeters, imperial)
} ?: "Ride $fallbackId"

/** Snaps both handles to sorted permanent spatial-analysis bins and keeps a usable range. */
internal fun snapTrailBounds(
    proposedStartMeters: Double,
    proposedEndMeters: Double,
    sortedDistances: List<Double>,
    minimumLengthMeters: Double = MINIMUM_TRAIL_LENGTH_METERS,
): TrailBounds? {
    if (sortedDistances.size < 2) return null
    var start = nearestDistance(sortedDistances, proposedStartMeters)
    var end = nearestDistance(sortedDistances, proposedEndMeters)
    if (start > end) {
        val previousStart = start
        start = end
        end = previousStart
    }
    if (end - start >= minimumLengthMeters) return TrailBounds(start, end)

    sortedDistances.firstOrNull { it >= start + minimumLengthMeters }?.let { return TrailBounds(start, it) }
    sortedDistances.lastOrNull { it <= end - minimumLengthMeters }?.let { return TrailBounds(it, end) }
    return null
}

internal fun profilesWithinBounds(
    profiles: List<SpatialProfileEntity>,
    bounds: TrailBounds,
): List<SpatialProfileEntity> = profiles.filter { it.distanceMeters in bounds.startMeters..bounds.endMeters }

internal data class TrailMapRoute(
    val fullRoute: List<TrackPointEntity>,
    val highlightedRoute: List<TrackPointEntity>,
)

internal fun buildTrailMapRoute(
    profiles: List<SpatialProfileEntity>,
    startMeters: Double,
    endMeters: Double,
): TrailMapRoute {
    val sorted = profiles.sortedBy { it.distanceMeters }
    return TrailMapRoute(
        fullRoute = sorted.map(SpatialProfileEntity::asTrackPoint),
        highlightedRoute = sorted.filter { it.distanceMeters in startMeters..endMeters }
            .map(SpatialProfileEntity::asTrackPoint),
    )
}

/** Builds lightweight stop markers from permanent profiles without loading archived telemetry. */
internal fun buildTrailStopPoints(
    profiles: List<SpatialProfileEntity>,
    confirmedRoute: List<TrackPointEntity>,
): List<TrackPointEntity> {
    if (confirmedRoute.isEmpty()) return emptyList()
    val candidates = profiles.asSequence()
        .filter {
            it.observedSpanMillis >= STOP_MINIMUM_DURATION_MILLIS &&
                it.latitude.isFinite() && it.longitude.isFinite() &&
                it.accuracyMeters.isFinite() && it.accuracyMeters <= STOP_MAXIMUM_ACCURACY_METERS
        }
        .filter { profile ->
            val routePoint = nearestRoutePoint(confirmedRoute, profile.latitude, profile.longitude)
                ?: return@filter false
            coordinateDistanceMeters(profile.latitude, profile.longitude, routePoint.latitude, routePoint.longitude) <=
                maxOf(STOP_MINIMUM_CORRIDOR_METERS, profile.accuracyMeters * 2.0)
        }
        .sortedWith(compareByDescending<SpatialProfileEntity> { it.observedSpanMillis }.thenBy { it.recordedAt })

    val retained = mutableListOf<SpatialProfileEntity>()
    candidates.forEach { candidate ->
        val duplicatesExistingStop = retained.any { existing ->
            coordinateDistanceMeters(
                candidate.latitude, candidate.longitude, existing.latitude, existing.longitude,
            ) <= STOP_DEDUPLICATION_METERS
        }
        if (!duplicatesExistingStop) retained += candidate
    }
    return retained.sortedBy { it.recordedAt }.map(SpatialProfileEntity::asTrackPoint)
}

private fun coordinateDistanceMeters(
    latitudeA: Double,
    longitudeA: Double,
    latitudeB: Double,
    longitudeB: Double,
): Double {
    val meanLatitude = Math.toRadians((latitudeA + latitudeB) / 2.0)
    val x = Math.toRadians(longitudeB - longitudeA) * cos(meanLatitude)
    val y = Math.toRadians(latitudeB - latitudeA)
    return sqrt(x * x + y * y) * 6_371_000.0
}

internal fun moveTrailStart(
    current: TrailBounds,
    proposedStartMeters: Double,
    sortedDistances: List<Double>,
    minimumLengthMeters: Double = MINIMUM_TRAIL_LENGTH_METERS,
): TrailBounds {
    if (sortedDistances.isEmpty()) return current
    val maximumStart = current.endMeters - minimumLengthMeters
    val snapped = nearestDistance(sortedDistances, proposedStartMeters)
    val validStart = if (snapped <= maximumStart) snapped else {
        sortedDistances.lastOrNull { it <= maximumStart } ?: return current
    }
    return TrailBounds(validStart, current.endMeters)
}

internal fun moveTrailEnd(
    current: TrailBounds,
    proposedEndMeters: Double,
    sortedDistances: List<Double>,
    minimumLengthMeters: Double = MINIMUM_TRAIL_LENGTH_METERS,
): TrailBounds {
    if (sortedDistances.isEmpty()) return current
    val minimumEnd = current.startMeters + minimumLengthMeters
    val snapped = nearestDistance(sortedDistances, proposedEndMeters)
    val validEnd = if (snapped >= minimumEnd) snapped else {
        sortedDistances.firstOrNull { it >= minimumEnd } ?: return current
    }
    return TrailBounds(current.startMeters, validEnd)
}

internal fun remapTrailBounds(
    bounds: TrailBounds,
    previousProfiles: List<SpatialProfileEntity>,
    newProfiles: List<SpatialProfileEntity>,
): TrailBounds? {
    fun map(distanceMeters: Double): Double? {
        val source = previousProfiles.minByOrNull { abs(it.distanceMeters - distanceMeters) } ?: return null
        val target = newProfiles.minByOrNull {
            coordinateDistanceMeters(source.latitude, source.longitude, it.latitude, it.longitude)
        } ?: return null
        val separation = coordinateDistanceMeters(source.latitude, source.longitude, target.latitude, target.longitude)
        val corridor = maxOf(15.0, source.accuracyMeters * 2.0, target.accuracyMeters * 2.0)
        return target.distanceMeters.takeIf { separation <= corridor }
    }
    val start = map(bounds.startMeters) ?: return null
    val end = map(bounds.endMeters) ?: return null
    return snapTrailBounds(start, end, newProfiles.map { it.distanceMeters }.sorted())
}

internal fun nearestRoutePoint(
    points: List<TrackPointEntity>,
    latitude: Double,
    longitude: Double,
): TrackPointEntity? {
    if (points.isEmpty()) return null
    val longitudeScale = cos(Math.toRadians(latitude)).coerceAtLeast(.01)
    return points.minByOrNull { point ->
        val latitudeDelta = point.latitude - latitude
        val longitudeDelta = (point.longitude - longitude) * longitudeScale
        latitudeDelta * latitudeDelta + longitudeDelta * longitudeDelta
    }
}

private fun nearestDistance(sortedDistances: List<Double>, target: Double): Double {
    val index = sortedDistances.binarySearch(target)
    if (index >= 0) return sortedDistances[index]
    val insertion = -index - 1
    if (insertion == 0) return sortedDistances.first()
    if (insertion == sortedDistances.size) return sortedDistances.last()
    val before = sortedDistances[insertion - 1]
    val after = sortedDistances[insertion]
    return if (abs(target - before) <= abs(after - target)) before else after
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailBoundaryEditor(
    trailId: Long?,
    trailName: String,
    referenceRides: List<RideEntity>,
    initialReferenceRideId: Long,
    initialProfiles: List<SpatialProfileEntity>,
    initialStartMeters: Double,
    initialEndMeters: Double,
    imperial: Boolean,
    apiKey: String,
    mapStyle: MapStyle,
    onDismiss: () -> Unit,
    onLoadProfiles: suspend (Long) -> List<SpatialProfileEntity>,
    onPreview: suspend (TrailDefinitionDraft) -> TrailEditImpact,
    onSave: suspend (TrailDefinitionDraft, Set<String>) -> Long,
    onApplied: (Long) -> Unit,
) {
    val editorKey = trailId ?: -initialReferenceRideId
    val scope = rememberCoroutineScope()
    var referenceRideId by rememberSaveable(editorKey) { mutableLongStateOf(initialReferenceRideId) }
    var sortedProfiles by remember(editorKey) { mutableStateOf(initialProfiles.sortedBy { it.distanceMeters }) }
    var loadedReferenceRideId by remember(editorKey) {
        mutableStateOf(initialReferenceRideId.takeIf { initialProfiles.isNotEmpty() })
    }
    var loadingProfiles by remember(editorKey) { mutableStateOf(initialProfiles.isEmpty()) }
    var working by remember(editorKey) { mutableStateOf(false) }
    var errorMessage by remember(editorKey) { mutableStateOf<String?>(null) }
    var impact by remember(editorKey) { mutableStateOf<TrailEditImpact?>(null) }
    var referenceMenuExpanded by remember { mutableStateOf(false) }
    val distances = remember(sortedProfiles) { sortedProfiles.map { it.distanceMeters } }
    val startingBounds = remember(editorKey) {
        snapTrailBounds(
            initialStartMeters,
            initialEndMeters,
            initialProfiles.map { it.distanceMeters }.sorted(),
        )
    }
    var name by rememberSaveable(editorKey) { mutableStateOf(trailName) }
    var bounds by rememberSaveable(editorKey, stateSaver = TrailBoundsSaver) {
        mutableStateOf(startingBounds ?: TrailBounds(initialStartMeters, initialEndMeters))
    }
    var resetBounds by remember(editorKey) { mutableStateOf(startingBounds) }

    LaunchedEffect(referenceRideId) {
        if (referenceRideId == loadedReferenceRideId && sortedProfiles.isNotEmpty()) {
            loadingProfiles = false
            return@LaunchedEffect
        }
        loadingProfiles = true
        errorMessage = null
        val previousProfiles = sortedProfiles
        val previousBounds = bounds
        runCatching { onLoadProfiles(referenceRideId).sortedBy { it.distanceMeters } }
            .onSuccess { loaded ->
                if (loaded.size < 2) {
                    errorMessage = "This ride has not finished processing its GPS route."
                } else {
                    sortedProfiles = loaded
                    loadedReferenceRideId = referenceRideId
                    val loadedBounds = remapTrailBounds(previousBounds, previousProfiles, loaded)
                        ?: TrailBounds(loaded.first().distanceMeters, loaded.last().distanceMeters)
                    bounds = loadedBounds
                    resetBounds = loadedBounds
                }
            }
            .onFailure { errorMessage = it.message ?: "Unable to load this route." }
        loadingProfiles = false
    }

    val allMapPoints = remember(sortedProfiles) { sortedProfiles.map(SpatialProfileEntity::asTrackPoint) }
    val selectedProfiles = remember(sortedProfiles, bounds) { profilesWithinBounds(sortedProfiles, bounds) }
    val selectedMapPoints = remember(selectedProfiles) { selectedProfiles.map(SpatialProfileEntity::asTrackPoint) }
    val canSave = sortedProfiles.size >= 2 && bounds.lengthMeters >= MINIMUM_TRAIL_LENGTH_METERS &&
        name.isNotBlank() && referenceRideId == loadedReferenceRideId && !loadingProfiles && !working

    Dialog(
        onDismissRequest = { if (!working) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopAppBar(
                    title = { Text("Fine-tune trail") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !working) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close trail editor")
                        }
                    },
                    colors = flightLogTopAppBarColors(),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )

                if (allMapPoints.size >= 2) {
                    TrailMap(
                        points = allMapPoints,
                        highlightedPoints = selectedMapPoints,
                        boundaryStart = selectedMapPoints.firstOrNull(),
                        boundaryEnd = selectedMapPoints.lastOrNull(),
                        onBoundaryStartChange = { candidate ->
                            val index = allMapPoints.indexOf(candidate)
                            sortedProfiles.getOrNull(index)?.let { profile ->
                                bounds = moveTrailStart(bounds, profile.distanceMeters, distances)
                            }
                        },
                        onBoundaryEndChange = { candidate ->
                            val index = allMapPoints.indexOf(candidate)
                            sortedProfiles.getOrNull(index)?.let { profile ->
                                bounds = moveTrailEnd(bounds, profile.distanceMeters, distances)
                            }
                        },
                        jumps = emptyList(),
                        apiKey = apiKey,
                        mapStyle = mapStyle,
                        fitRoute = true,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    Box(
                        Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    shadowElevation = 10.dp,
                ) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(80) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Trail name") },
                            singleLine = true,
                        )

                        Box(Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { referenceMenuExpanded = true },
                                enabled = !working,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Route, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Reference: ${rideLabel(referenceRides.firstOrNull { it.id == referenceRideId }, referenceRideId, imperial)}")
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(
                                expanded = referenceMenuExpanded,
                                onDismissRequest = { referenceMenuExpanded = false },
                            ) {
                                referenceRides.distinctBy { it.id }.forEach { ride ->
                                    DropdownMenuItem(
                                        text = { Text(rideLabel(ride, ride.id, imperial)) },
                                        onClick = {
                                            referenceMenuExpanded = false
                                            if (ride.id != referenceRideId) referenceRideId = ride.id
                                        },
                                    )
                                }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("Start to finish", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(
                                    formatBoundaryDistance(bounds.lengthMeters, imperial),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Default.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        RouteRangeScrubber(
                            profiles = sortedProfiles,
                            bounds = bounds,
                            onBoundsChange = { proposedStart, proposedEnd ->
                                snapTrailBounds(proposedStart, proposedEnd, distances)?.let { bounds = it }
                            },
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            BoundaryLabel(
                                "START", Lime,
                                formatBoundaryAltitude(selectedProfiles.firstOrNull()?.altitudeMeters, imperial),
                                Alignment.Start,
                            )
                            Text("Drag on map or slide", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            BoundaryLabel(
                                "FINISH", Amber,
                                formatBoundaryAltitude(selectedProfiles.lastOrNull()?.altitudeMeters, imperial),
                                Alignment.End,
                            )
                        }

                        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                        Button(
                            onClick = {
                                scope.launch {
                                    working = true
                                    errorMessage = null
                                    val draft = TrailDefinitionDraft(
                                        trailId = trailId,
                                        name = name,
                                        referenceRideId = referenceRideId,
                                        startMeters = bounds.startMeters,
                                        endMeters = bounds.endMeters,
                                    )
                                    runCatching { onPreview(draft) }
                                        .onSuccess { impact = it }
                                        .onFailure { errorMessage = it.message ?: "Unable to preview this change." }
                                    working = false
                                }
                            },
                            enabled = canSave,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            if (working) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else Text("Review changes", fontWeight = FontWeight.Bold)
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(
                                onClick = { resetBounds?.let { bounds = it } },
                                enabled = resetBounds != null && bounds != resetBounds,
                            ) { Text("Reset") }
                        }
                    }
                }
            }
        }
    }
    impact?.let { preview ->
        TrailImpactDialog(
            impact = preview,
            working = working,
            errorMessage = errorMessage,
            onDismiss = { if (!working) impact = null },
            onConfirm = {
                scope.launch {
                    working = true
                    errorMessage = null
                    runCatching { onSave(preview.draft, preview.removedItems.mapTo(hashSetOf()) { it.key }) }
                        .onSuccess { id -> impact = null; onApplied(id) }
                        .onFailure { errorMessage = it.message ?: "Unable to save this trail." }
                    working = false
                }
            },
        )
    }
}

private val TrailBoundsSaver = androidx.compose.runtime.saveable.Saver<TrailBounds, List<Double>>(
    save = { listOf(it.startMeters, it.endMeters) },
    restore = { TrailBounds(it[0], it[1]) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteRangeScrubber(
    profiles: List<SpatialProfileEntity>,
    bounds: TrailBounds,
    onBoundsChange: (Double, Double) -> Unit,
) {
    val firstDistance = profiles.firstOrNull()?.distanceMeters ?: return
    val lastDistance = profiles.lastOrNull()?.distanceMeters ?: return
    if (lastDistance <= firstDistance) return
    val preview = remember(profiles) { elevationPreview(profiles) }
    val selectedStart = ((bounds.startMeters - firstDistance) / (lastDistance - firstDistance)).toFloat().coerceIn(0f, 1f)
    val selectedEnd = ((bounds.endMeters - firstDistance) / (lastDistance - firstDistance)).toFloat().coerceIn(0f, 1f)

    Box(
        Modifier.fillMaxWidth().height(76.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
        Canvas(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp)) {
            if (preview.size < 2) return@Canvas
            val fullPath = Path()
            val selectedPath = Path()
            var selectedStarted = false
            preview.forEachIndexed { index, point ->
                val offset = Offset(point.progress * size.width, size.height * elevationYFraction(point.elevationFraction))
                if (index == 0) fullPath.moveTo(offset.x, offset.y) else fullPath.lineTo(offset.x, offset.y)
                if (point.progress in selectedStart..selectedEnd) {
                    if (!selectedStarted) {
                        selectedPath.moveTo(offset.x, offset.y)
                        selectedStarted = true
                    } else selectedPath.lineTo(offset.x, offset.y)
                }
            }
            drawPath(fullPath, unselectedColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            if (selectedStarted) drawPath(selectedPath, TrailCyan, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
        }
        Text(
            if (preview.size >= 2) "ELEVATION" else "Elevation unavailable",
            modifier = Modifier.align(if (preview.size >= 2) Alignment.TopCenter else Alignment.Center),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        RangeSlider(
            value = bounds.startMeters.toFloat()..bounds.endMeters.toFloat(),
            onValueChange = { onBoundsChange(it.start.toDouble(), it.endInclusive.toDouble()) },
            valueRange = firstDistance.toFloat()..lastDistance.toFloat(),
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp).semantics {
                contentDescription = "Trail start and finish handles"
            },
            colors = SliderDefaults.colors(
                thumbColor = TrailCyan,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
            startThumb = { BoundaryThumb(Lime) },
            endThumb = { BoundaryThumb(Amber) },
        )
    }
}

@Composable
private fun BoundaryThumb(color: Color) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = color,
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.surface),
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = null,
                tint = Color(0xFF172000),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun BoundaryLabel(
    text: String,
    color: Color,
    altitude: String?,
    horizontalAlignment: Alignment.Horizontal,
) {
    Column(horizontalAlignment = horizontalAlignment) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        altitude?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal data class ElevationPreviewPoint(val progress: Float, val elevationFraction: Float)

internal fun elevationPreview(profiles: List<SpatialProfileEntity>): List<ElevationPreviewPoint> {
    if (profiles.size < 2) return emptyList()
    val first = profiles.first()
    val last = profiles.last()
    val distanceSpan = (last.distanceMeters - first.distanceMeters).coerceAtLeast(1.0)
    val samples = profiles.mapNotNull { profile ->
        profile.altitudeMeters?.takeIf { it.isFinite() }?.let { altitude ->
            ((profile.distanceMeters - first.distanceMeters) / distanceSpan).toFloat().coerceIn(0f, 1f) to altitude
        }
    }
    if (samples.size < 2) return emptyList()
    val smoothingRadius = when {
        samples.size >= 9 -> 2
        samples.size >= 5 -> 1
        else -> 0
    }
    val smoothedAltitudes = samples.indices.map { index ->
        val window = (index - smoothingRadius).coerceAtLeast(0)..
            (index + smoothingRadius).coerceAtMost(samples.lastIndex)
        window.map { samples[it].second }.average()
    }
    val minimum = smoothedAltitudes.min()
    val span = (smoothedAltitudes.max() - minimum).coerceAtLeast(1.0)
    return samples.mapIndexed { index, sample ->
        ElevationPreviewPoint(
            progress = sample.first,
            elevationFraction = ((smoothedAltitudes[index] - minimum) / span).toFloat().coerceIn(0f, 1f),
        )
    }
}

internal fun elevationYFraction(elevationFraction: Float): Float =
    .82f - elevationFraction.coerceIn(0f, 1f) * .64f

internal fun SpatialProfileEntity.asTrackPoint() = TrackPointEntity(
    rideId = rideId,
    recordedAt = recordedAt,
    latitude = latitude,
    longitude = longitude,
    altitudeMeters = altitudeMeters,
    speedMps = speedMps,
    bearingDegrees = null,
    accuracyMeters = accuracyMeters,
)

private fun formatBoundaryDistance(meters: Double, imperial: Boolean): String = when {
    imperial && meters >= 1_609.344 -> String.format(Locale.US, "%.2f mi", meters / 1_609.344)
    imperial -> "${(meters * 3.28084).toInt()} ft"
    meters >= 1_000.0 -> String.format(Locale.US, "%.2f km", meters / 1_000.0)
    else -> "${meters.toInt()} m"
}

private fun formatBoundaryAltitude(meters: Double?, imperial: Boolean): String? = meters?.let {
    if (imperial) "≈ ${(it * 3.28084).toInt()} ft" else "≈ ${it.toInt()} m"
}
