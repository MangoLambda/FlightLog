package com.example.flightlog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.data.TrailPauseZoneEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.domain.PauseZoneState
import com.example.flightlog.maps.MapStyle
import java.util.Locale

internal enum class TrailResultTab { SPLITS, FULL_RUNS }

internal data class PauseZoneDraft(
    val key: Long,
    val entityId: Long?,
    val name: String,
    val startMeters: Double,
    val endMeters: Double,
    val state: PauseZoneState,
)

internal fun routeForRange(
    profiles: List<SpatialProfileEntity>,
    startMeters: Double,
    endMeters: Double,
): List<TrackPointEntity> = profiles.asSequence()
    .filter { it.distanceMeters in startMeters..endMeters }
    .sortedBy { it.distanceMeters }
    .map(SpatialProfileEntity::asTrackPoint)
    .toList()

internal fun pointAtDistance(profiles: List<SpatialProfileEntity>, distanceMeters: Double): TrackPointEntity? =
    profiles.minByOrNull { kotlin.math.abs(it.distanceMeters - distanceMeters) }?.asTrackPoint()

@Composable
internal fun SplitRouteScrubber(
    trailStartMeters: Double,
    trailEndMeters: Double,
    splits: List<TrailSectionEntity>,
    zones: List<TrailPauseZoneEntity>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = (trailEndMeters - trailStartMeters).coerceAtLeast(1.0)
    val colors = listOf(Color(0xFF42D9E8), Color(0xFFA7E34B), Color(0xFFFFB84D), Color(0xFF9C8CFF), Color(0xFF54B6FF))
    Canvas(
        modifier.height(62.dp).fillMaxWidth()
            .semantics { contentDescription = "Trail split selector; split ${selectedIndex + 1} selected" }
            .pointerInput(splits, trailStartMeters, trailEndMeters) {
                detectTapGestures { offset ->
                    val distance = trailStartMeters + offset.x / size.width * span
                    val index = splits.indexOfFirst { distance in it.startMeters..it.endMeters }.let { found ->
                        if (found >= 0) found else splits.indices.minByOrNull { i ->
                            minOf(kotlin.math.abs(distance - splits[i].startMeters), kotlin.math.abs(distance - splits[i].endMeters))
                        } ?: 0
                    }
                    onSelect(index)
                }
            },
    ) {
        val top = size.height * .22f
        val height = size.height * .56f
        drawRoundRect(Color(0xFF26332C), Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, height), CornerRadius(height / 2))
        splits.forEachIndexed { index, split ->
            val left = ((split.startMeters - trailStartMeters) / span).toFloat().coerceIn(0f, 1f) * size.width
            val right = ((split.endMeters - trailStartMeters) / span).toFloat().coerceIn(0f, 1f) * size.width
            val color = colors[index % colors.size]
            drawRoundRect(color, Offset(left, top), androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(2f), height), CornerRadius(10f))
            if (index == selectedIndex) {
                drawRoundRect(Color.White, Offset(left, top), androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(2f), height), CornerRadius(10f), style = Stroke(3f))
            }
            drawContext.canvas.nativeCanvas.drawText(
                (index + 1).toString(), (left + right) / 2f, top + height * .67f,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = android.graphics.Color.rgb(19, 32, 25)
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = height * .55f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                },
            )
        }
        zones.filter { it.state == PauseZoneState.AUTOMATIC || it.state == PauseZoneState.USER_LOCKED }.forEach { zone ->
            val left = ((zone.startMeters - trailStartMeters) / span).toFloat().coerceIn(0f, 1f) * size.width
            val right = ((zone.endMeters - trailStartMeters) / span).toFloat().coerceIn(0f, 1f) * size.width
            drawRoundRect(Color(0xFFD22A2A), Offset(left, top - 4f), androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(5f), height + 8f), CornerRadius(5f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PauseZoneEditor(
    trailId: Long,
    trailStartMeters: Double,
    trailEndMeters: Double,
    profiles: List<SpatialProfileEntity>,
    zones: List<TrailPauseZoneEntity>,
    apiKey: String,
    mapStyle: MapStyle,
    onDismiss: () -> Unit,
    onSave: (List<PauseZoneDraft>) -> Unit,
) {
    var drafts by remember(trailId, zones) {
        mutableStateOf(zones.map { zone ->
            PauseZoneDraft(zone.id, zone.id, zone.name, zone.startMeters, zone.endMeters, zone.state)
        })
    }
    var nextTemporaryKey by remember { mutableLongStateOf(-1L) }
    var selectedKey by remember(trailId, zones) { mutableLongStateOf(drafts.firstOrNull()?.key ?: Long.MIN_VALUE) }
    val selected = drafts.firstOrNull { it.key == selectedKey }
    val activeDrafts = drafts.filter { it.state == PauseZoneState.AUTOMATIC || it.state == PauseZoneState.USER_LOCKED }
    fun updateSelected(transform: (PauseZoneDraft) -> PauseZoneDraft) {
        drafts = drafts.map { if (it.key == selectedKey) transform(it) else it }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopAppBar(
                    title = { Text("Edit splits") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") } },
                    actions = { TextButton(onClick = { onSave(drafts) }) { Text("Save") } },
                    colors = flightLogTopAppBarColors(),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
                TrailMap(
                    points = profiles.sortedBy { it.distanceMeters }.map(SpatialProfileEntity::asTrackPoint),
                    highlightedPoints = selected?.let { routeForRange(profiles, it.startMeters, it.endMeters) }.orEmpty(),
                    pauseZoneRoutes = activeDrafts.map { routeForRange(profiles, it.startMeters, it.endMeters) },
                    boundaryStart = selected?.let { pointAtDistance(profiles, it.startMeters) },
                    boundaryEnd = selected?.let { pointAtDistance(profiles, it.endMeters) },
                    onBoundaryStartChange = { point ->
                        profiles.minByOrNull { kotlin.math.abs(it.recordedAt - point.recordedAt) }?.let { profile ->
                            updateSelected { it.copy(startMeters = profile.distanceMeters.coerceAtMost(it.endMeters - 5.0), state = PauseZoneState.USER_LOCKED) }
                        }
                    },
                    onBoundaryEndChange = { point ->
                        profiles.minByOrNull { kotlin.math.abs(it.recordedAt - point.recordedAt) }?.let { profile ->
                            updateSelected { it.copy(endMeters = profile.distanceMeters.coerceAtLeast(it.startMeters + 5.0), state = PauseZoneState.USER_LOCKED) }
                        }
                    },
                    jumps = emptyList(), apiKey = apiKey, mapStyle = mapStyle,
                    modifier = Modifier.fillMaxWidth().height(300.dp), fitRoute = true,
                )
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(drafts, key = { it.key }) { draft ->
                        FilterChip(
                            selected = draft.key == selectedKey,
                            onClick = { selectedKey = draft.key },
                            label = { Text(if (draft.state == PauseZoneState.DISMISSED) "${draft.name} (hidden)" else draft.name) },
                        )
                    }
                }
                selected?.let { draft ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = { value -> updateSelected { it.copy(name = value.take(80), state = PauseZoneState.USER_LOCKED) } },
                            label = { Text("Pause area name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        RangeSlider(
                            value = draft.startMeters.toFloat()..draft.endMeters.toFloat(),
                            onValueChange = { range ->
                                updateSelected {
                                    it.copy(
                                        startMeters = range.start.toDouble(), endMeters = range.endInclusive.toDouble(),
                                        state = PauseZoneState.USER_LOCKED,
                                    )
                                }
                            },
                            valueRange = trailStartMeters.toFloat()..trailEndMeters.toFloat(),
                            modifier = Modifier.semantics { contentDescription = "Drag pause area start and finish" },
                        )
                        val original = zones.firstOrNull { it.id == draft.entityId }
                        if (original != null) Text(
                            buildString {
                                append("Detected on ${original.supportCount} of ${original.eligiblePassCount} rides")
                                if (original.medianPauseMillis > 0) append(" • median pause ${formatSplitTime(original.medianPauseMillis)}")
                                append(" • ${original.confidence}% confidence")
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                updateSelected {
                                    it.copy(state = if (it.state == PauseZoneState.DISMISSED) PauseZoneState.USER_LOCKED else PauseZoneState.DISMISSED)
                                }
                            }) {
                                Icon(Icons.Default.Delete, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (draft.state == PauseZoneState.DISMISSED) "Restore" else "Hide")
                            }
                            val next = activeDrafts.firstOrNull { it.startMeters > draft.startMeters }
                            if (next != null) OutlinedButton(onClick = {
                                drafts = drafts.map {
                                    when (it.key) {
                                        draft.key -> it.copy(endMeters = next.endMeters, state = PauseZoneState.USER_LOCKED)
                                        next.key -> it.copy(state = PauseZoneState.DISMISSED)
                                        else -> it
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Merge, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Merge next")
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val occupied = activeDrafts.sortedBy { it.startMeters }
                        val boundaries = mutableListOf(trailStartMeters).apply {
                            occupied.forEach { add(it.endMeters) }
                        }
                        val ends = occupied.map { it.startMeters } + trailEndMeters
                        val largest = boundaries.zip(ends).maxByOrNull { it.second - it.first }
                        val center = largest?.let { (it.first + it.second) / 2.0 } ?: (trailStartMeters + trailEndMeters) / 2.0
                        val key = nextTemporaryKey--
                        drafts = drafts + PauseZoneDraft(
                            key, null, "Pause ${drafts.size + 1}",
                            (center - 5.0).coerceAtLeast(trailStartMeters),
                            (center + 5.0).coerceAtMost(trailEndMeters), PauseZoneState.USER_LOCKED,
                        )
                        selectedKey = key
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add pause area")
                }
            }
        }
    }
}

internal fun formatSplitTime(millis: Long): String {
    val totalSeconds = millis / 1_000.0
    val minutes = (totalSeconds / 60).toInt()
    val seconds = totalSeconds - minutes * 60
    return if (minutes > 0) String.format(Locale.US, "%d:%04.1f", minutes, seconds)
    else String.format(Locale.US, "%.1fs", seconds)
}

internal fun formatSignedTime(millis: Long): String = (if (millis >= 0) "+" else "−") + formatSplitTime(kotlin.math.abs(millis))

internal fun formatSplitSpeed(mps: Double, imperial: Boolean): String = if (imperial) {
    String.format(Locale.US, "%.1f mph", mps * 2.23694)
} else String.format(Locale.US, "%.1f km/h", mps * 3.6)
