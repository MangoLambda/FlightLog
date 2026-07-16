@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.flightlog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.TrailEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.data.SectionEffortEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.data.StopEventEntity
import com.example.flightlog.data.TrailPauseZoneEntity
import com.example.flightlog.domain.AggregatePeriod
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.ComparisonMode
import com.example.flightlog.domain.EffortInvalidReason
import com.example.flightlog.domain.SectionState
import com.example.flightlog.domain.TrailState
import com.example.flightlog.domain.RideState
import com.example.flightlog.maps.MapApiKeyStore
import com.example.flightlog.maps.MapTileCache
import com.example.flightlog.maps.TileCacheState
import com.example.flightlog.maps.TileCacheStatus
import com.example.flightlog.tracking.LiveRideState
import com.example.flightlog.tracking.GpsStatus
import com.example.flightlog.tracking.RideMath
import com.example.flightlog.tracking.RideTrackingService
import com.example.flightlog.tracking.RecordingSettings
import com.example.flightlog.ui.AppScreen
import com.example.flightlog.ui.FlightLogViewModel
import com.example.flightlog.ui.TrailBoundaryEditor
import com.example.flightlog.ui.buildTrailMapRoute
import com.example.flightlog.ui.buildTrailStopPoints
import com.example.flightlog.ui.BackupUiState
import com.example.flightlog.export.FlightLogBackup
import com.example.flightlog.ui.TrailMap
import com.example.flightlog.ui.TrailResultTab
import com.example.flightlog.ui.SplitEffortContext
import com.example.flightlog.ui.SplitRouteScrubber
import com.example.flightlog.ui.PauseZoneEvidence
import com.example.flightlog.ui.PauseZoneEditor
import com.example.flightlog.ui.FullRunsPanel
import com.example.flightlog.ui.routeForRange
import com.example.flightlog.ui.pointAtDistance
import com.example.flightlog.maps.MapStyle
import com.example.flightlog.ui.theme.Amber
import com.example.flightlog.ui.theme.FlightLogTheme
import com.example.flightlog.ui.theme.Lime
import com.example.flightlog.ui.theme.TrailCyan
import com.google.android.gms.location.LocationServices
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FlightLogTheme { FlightLogApp() } }
    }
}

@Composable
private fun FlightLogApp(vm: FlightLogViewModel = viewModel()) {
    val context = LocalContext.current
    val rides by vm.rides.collectAsStateWithLifecycle()
    val jumps by vm.jumps.collectAsStateWithLifecycle()
    val trails by vm.trails.collectAsStateWithLifecycle()
    val sections by vm.sections.collectAsStateWithLifecycle()
    val passes by vm.passes.collectAsStateWithLifecycle()
    val efforts by vm.efforts.collectAsStateWithLifecycle()
    val pauseZones by vm.pauseZones.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val selectedRideId by vm.selectedRideId.collectAsStateWithLifecycle()
    val selectedJumpId by vm.selectedJumpId.collectAsStateWithLifecycle()
    val selectedTrailId by vm.selectedTrailId.collectAsStateWithLifecycle()
    val selectedPassAId by vm.selectedPassAId.collectAsStateWithLifecycle()
    val selectedPassBId by vm.selectedPassBId.collectAsStateWithLifecycle()
    val comparisonMode by vm.comparisonMode.collectAsStateWithLifecycle()
    val comparisonPointsA by vm.comparisonPointsA.collectAsStateWithLifecycle()
    val comparisonPointsB by vm.comparisonPointsB.collectAsStateWithLifecycle()
    val selectedTrailProfiles by vm.selectedTrailProfiles.collectAsStateWithLifecycle()
    val telemetryBytes by vm.telemetryBytes.collectAsStateWithLifecycle()
    val motionBytes by vm.motionBytes.collectAsStateWithLifecycle()
    val nextMotionExpiry by vm.nextMotionExpiry.collectAsStateWithLifecycle()
    val estimatedProfileBytes by vm.estimatedProfileBytes.collectAsStateWithLifecycle()
    val backupState by vm.backupState.collectAsStateWithLifecycle()
    val points by vm.selectedPoints.collectAsStateWithLifecycle()
    val selectedJumps by vm.selectedRideJumps.collectAsStateWithLifecycle()
    val selectedStops by vm.selectedRideStops.collectAsStateWithLifecycle()
    val imperial by vm.imperial.collectAsStateWithLifecycle()
    val recordingSettings by vm.recordingSettings.collectAsStateWithLifecycle()
    val userMapApiKey by vm.userMapApiKey.collectAsStateWithLifecycle()
    val effectiveMapApiKey by vm.effectiveMapApiKey.collectAsStateWithLifecycle()
    val mapStyle by vm.mapStyle.collectAsStateWithLifecycle()
    val tileCacheState by vm.tileCacheState.collectAsStateWithLifecycle()
    var pendingRideStart by remember { mutableStateOf(false) }
    var focusMapProvider by remember { mutableStateOf(false) }
    var showActiveMapSettings by remember { mutableStateOf(false) }
    val preferences = remember { context.getSharedPreferences("settings", 0) }
    var showWelcome by remember { mutableStateOf(!preferences.getBoolean("welcomed", false)) }

    val permissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (pendingRideStart && result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startRideService(context)
        pendingRideStart = false
    }
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(FlightLogBackup.MIME_TYPE),
    ) { uri -> uri?.let(vm::exportBackup) }
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importBackup) }
    val startRide = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startRideService(context)
        } else {
            pendingRideStart = true
            permissionLauncher.launch(permissions)
        }
    }

    LaunchedEffect(rides.firstOrNull()?.id, selectedRideId) {
        if (selectedRideId == null) vm.selectedRideId.value = rides.firstOrNull()?.id
    }
    LaunchedEffect(live.rideId) {
        live.rideId?.let { activeRideId ->
            if (vm.selectedRideId.value != activeRideId) vm.selectedRideId.value = activeRideId
        }
    }

    if (showWelcome) {
        WelcomeDialog(
            onDismiss = {
                preferences.edit().putBoolean("welcomed", true).apply()
                showWelcome = false
            },
        )
    }

    val openMapSettings = {
        focusMapProvider = true
        vm.screen.value = AppScreen.SETTINGS
    }

    if (live.state != null && showActiveMapSettings) {
        BackHandler { showActiveMapSettings = false }
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text("Map settings") },
                    navigationIcon = {
                        IconButton(onClick = { showActiveMapSettings = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to active ride")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                SettingsScreen(
                    imperial = imperial,
                    recordingSettings = recordingSettings,
                    onImperial = vm::setImperial,
                    onMountingMode = vm::setMountingMode,
                    onMinimumJumpHeight = vm::setMinimumJumpHeight,
                    savedMapApiKey = userMapApiKey,
                    hasBundledMapApiKey = vm.hasBundledMapApiKey,
                    onSaveMapApiKey = vm::saveThunderforestApiKey,
                    onClearMapApiKey = vm::clearThunderforestApiKey,
                    mapStyle = mapStyle,
                    onMapStyle = vm::setMapStyle,
                    tileCacheState = tileCacheState,
                    onClearTileCache = vm::clearMapTileCache,
                    onSetTileCacheLimit = vm::setMapTileCacheLimit,
                    onRefreshTileCache = vm::refreshMapTileCache,
                    onGuidance = { showWelcome = true },
                    focusMapProvider = true,
                    onMapProviderFocused = {},
                    showHeading = false,
                    telemetryBytes = telemetryBytes,
                    motionBytes = motionBytes,
                    estimatedProfileBytes = estimatedProfileBytes,
                    nextMotionExpiry = nextMotionExpiry,
                    backupState = backupState,
                    onExportBackup = { backupExportLauncher.launch("flightlog-backup.flightlog.zip") },
                    onImportBackup = { backupImportLauncher.launch(arrayOf(FlightLogBackup.MIME_TYPE, "application/octet-stream")) },
                )
            }
        }
        return
    }

    if (live.state != null) {
        ActiveRideScreen(
            live = live,
            points = points,
            jumps = selectedJumps,
            mapApiKey = effectiveMapApiKey,
            mapStyle = mapStyle,
            imperial = imperial,
            onPauseResume = {
                sendRideAction(context, if (live.state == RideState.PAUSED) RideTrackingService.ACTION_RESUME else RideTrackingService.ACTION_PAUSE)
            },
            onFinish = {
                live.rideId?.let(vm::openRide)
                sendRideAction(context, RideTrackingService.ACTION_STOP)
            },
            onConfigureMap = { showActiveMapSettings = true },
        )
        return
    }

    BackHandler(enabled = screen == AppScreen.REVIEW || screen == AppScreen.JUMP_DETAIL || screen == AppScreen.TRAIL_DETAIL) {
        vm.screen.value = when (screen) {
            AppScreen.JUMP_DETAIL -> AppScreen.REVIEW
            AppScreen.TRAIL_DETAIL -> AppScreen.TRAILS
            else -> AppScreen.HISTORY
        }
    }

    val screenStateHolder = rememberSaveableStateHolder()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (screen in setOf(AppScreen.RIDE, AppScreen.HISTORY, AppScreen.TRAILS, AppScreen.STATS, AppScreen.SETTINGS)) {
                FlightLogNavigation(screen) { vm.screen.value = it }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val screenStateKey = when (screen) {
                AppScreen.REVIEW -> "${screen.name}:$selectedRideId"
                AppScreen.JUMP_DETAIL -> "${screen.name}:$selectedJumpId"
                AppScreen.TRAIL_DETAIL -> "${screen.name}:$selectedTrailId"
                else -> screen.name
            }
            screenStateHolder.SaveableStateProvider(screenStateKey) {
                when (screen) {
                    AppScreen.RIDE -> HomeScreen(rides, points, selectedJumps, imperial, recordingSettings, effectiveMapApiKey, mapStyle, startRide, openMapSettings)
                    AppScreen.HISTORY -> HistoryScreen(rides, imperial, vm::openRide)
                    AppScreen.TRAILS -> TrailsScreen(trails, sections, passes, efforts, imperial, vm::openTrail)
                    AppScreen.STATS -> StatsScreen(rides, jumps, imperial)
                    AppScreen.SETTINGS -> SettingsScreen(
                        imperial = imperial,
                        recordingSettings = recordingSettings,
                        onImperial = vm::setImperial,
                        onMountingMode = vm::setMountingMode,
                        onMinimumJumpHeight = vm::setMinimumJumpHeight,
                        savedMapApiKey = userMapApiKey,
                        hasBundledMapApiKey = vm.hasBundledMapApiKey,
                        onSaveMapApiKey = vm::saveThunderforestApiKey,
                        onClearMapApiKey = vm::clearThunderforestApiKey,
                        mapStyle = mapStyle,
                        onMapStyle = vm::setMapStyle,
                        tileCacheState = tileCacheState,
                        onClearTileCache = vm::clearMapTileCache,
                        onSetTileCacheLimit = vm::setMapTileCacheLimit,
                        onRefreshTileCache = vm::refreshMapTileCache,
                        onGuidance = { showWelcome = true },
                        focusMapProvider = focusMapProvider,
                        onMapProviderFocused = { focusMapProvider = false },
                        telemetryBytes = telemetryBytes,
                        motionBytes = motionBytes,
                        estimatedProfileBytes = estimatedProfileBytes,
                        nextMotionExpiry = nextMotionExpiry,
                        backupState = backupState,
                        onExportBackup = { backupExportLauncher.launch("flightlog-backup.flightlog.zip") },
                        onImportBackup = { backupImportLauncher.launch(arrayOf(FlightLogBackup.MIME_TYPE, "application/octet-stream")) },
                    )
                    AppScreen.REVIEW -> ReviewScreen(
                        ride = rides.firstOrNull { it.id == selectedRideId },
                        points = points,
                        jumps = selectedJumps,
                        stops = selectedStops,
                        mapApiKey = effectiveMapApiKey,
                        mapStyle = mapStyle,
                        imperial = imperial,
                        onBack = { vm.screen.value = AppScreen.HISTORY },
                        onOpenJump = vm::openJump,
                        onStatus = vm::setJumpStatus,
                        onShare = { selectedRideId?.let(vm::shareRide) },
                        onDelete = { selectedRideId?.let(vm::deleteRide) },
                        deletesReferencedTrail = trails.any { it.canonicalRideId == selectedRideId },
                        onConfigureMap = openMapSettings,
                    )
                    AppScreen.JUMP_DETAIL -> JumpDetailScreen(
                        jump = jumps.firstOrNull { it.id == selectedJumpId },
                        imperial = imperial,
                        onBack = { vm.screen.value = AppScreen.REVIEW },
                        onSave = vm::saveJump,
                    )
                    AppScreen.TRAIL_DETAIL -> TrailDetailScreen(
                        trail = trails.firstOrNull { it.id == selectedTrailId },
                        sections = sections.filter { it.trailId == selectedTrailId },
                        passes = passes.filter { it.trailId == selectedTrailId },
                        efforts = efforts,
                        rides = rides,
                        mode = comparisonMode,
                        selectedPassAId = selectedPassAId,
                        selectedPassBId = selectedPassBId,
                        pointsA = comparisonPointsA,
                        pointsB = comparisonPointsB,
                        canonicalProfiles = selectedTrailProfiles,
                        pauseZones = pauseZones.filter { it.trailId == selectedTrailId },
                        imperial = imperial,
                        mapApiKey = effectiveMapApiKey,
                        mapStyle = mapStyle,
                        onBack = { vm.screen.value = AppScreen.TRAILS },
                        onMode = { vm.comparisonMode.value = it },
                        onPassA = { vm.selectedPassAId.value = it },
                        onPassB = { vm.selectedPassBId.value = it },
                        onConfirmSection = vm::confirmSection,
                        onConfirmTrail = vm::confirmTrail,
                        onAddSection = vm::addManualSection,
                        onUpdateSection = vm::updateSection,
                        onSavePauseZones = vm::savePauseZones,
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Default.Terrain, null, tint = Lime) },
        title = { Text("Ride ready, data honest") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose Pocket mode for a snug front or zippered pocket, or Bike mounted for a firmly secured phone. Avoid loose cargo pockets and backpacks.")
                Text("Flight time, height, and distance are sensor estimates—not surveyed measurements. Review every detected jump after your ride.")
                Text("Recording uses precise location, motion sensors, and extra battery while the ride notification is visible.")
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("I understand") } },
    )
}

@Composable
private fun FlightLogNavigation(selected: AppScreen, onSelect: (AppScreen) -> Unit) {
    NavigationBar {
        listOf(
            Triple(AppScreen.RIDE, Icons.Default.Map, "Ride"),
            Triple(AppScreen.HISTORY, Icons.Default.History, "History"),
            Triple(AppScreen.TRAILS, Icons.Default.Route, "Trails"),
            Triple(AppScreen.STATS, Icons.Default.BarChart, "Stats"),
            Triple(AppScreen.SETTINGS, Icons.Default.Settings, "Settings"),
        ).forEach { (screen, icon, label) ->
            NavigationBarItem(
                selected = selected == screen,
                onClick = { onSelect(screen) },
                icon = { Icon(icon, label) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun HomeScreen(
    rides: List<RideEntity>,
    points: List<com.example.flightlog.data.TrackPointEntity>,
    jumps: List<JumpEventEntity>,
    imperial: Boolean,
    recordingSettings: RecordingSettings,
    mapApiKey: String,
    mapStyle: MapStyle,
    onStart: () -> Unit,
    onConfigureMap: () -> Unit,
) {
    val context = LocalContext.current
    val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val currentLocation = rememberLastKnownTrackPoint(hasLocation)
    val sensors = context.getSystemService(SensorManager::class.java)
    val hasAccelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TrailMap(
                points = currentLocation?.let(::listOf) ?: points.takeLast(1),
                jumps = emptyList(),
                apiKey = mapApiKey,
                mapStyle = mapStyle,
                modifier = Modifier.fillMaxSize(),
                onConfigureMap = onConfigureMap,
                showRider = currentLocation != null,
            )
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(if (hasLocation) "GPS ready" else "GPS permission needed", hasLocation)
                StatusChip(
                    if (!hasAccelerometer) "No accelerometer"
                    else if (recordingSettings.mountingMode == MountingMode.POCKET) "Pocket mode" else "Bike mounted",
                    hasAccelerometer,
                )
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Ready to ride?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            rides.firstOrNull()?.let { ride ->
                Card {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("LAST RIDE", formatDistance(ride.distanceMeters, imperial))
                        Metric("MAX SPEED", formatSpeed(ride.maxSpeedMps, imperial))
                        Metric("JUMPS", jumps.count { it.status == JumpStatus.CONFIRMED }.toString())
                    }
                }
            } ?: Text("Your first route and jump review will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(60.dp).semantics { contentDescription = "Start ride recording" },
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Start ride", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun rememberLastKnownTrackPoint(permitted: Boolean): TrackPointEntity? {
    val context = LocalContext.current
    var point by remember(permitted) { mutableStateOf<TrackPointEntity?>(null) }
    LaunchedEffect(context, permitted) {
        if (!permitted) return@LaunchedEffect
        try {
            LocationServices.getFusedLocationProviderClient(context).lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        point = TrackPointEntity(
                            rideId = 0,
                            recordedAt = location.time,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            altitudeMeters = location.altitude.takeIf { location.hasAltitude() },
                            speedMps = location.speed.toDouble().takeIf { location.hasSpeed() } ?: 0.0,
                            bearingDegrees = location.bearing.takeIf { location.hasBearing() },
                            accuracyMeters = location.accuracy,
                        )
                    }
                }
        } catch (_: SecurityException) {
            point = null
        }
    }
    return point
}

@Composable
private fun ActiveRideScreen(
    live: LiveRideState,
    points: List<com.example.flightlog.data.TrackPointEntity>,
    jumps: List<JumpEventEntity>,
    mapApiKey: String,
    mapStyle: MapStyle,
    imperial: Boolean,
    onPauseResume: () -> Unit,
    onFinish: () -> Unit,
    onConfigureMap: () -> Unit,
) {
    val paused = live.state == RideState.PAUSED
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            TrailMap(points, jumps, mapApiKey, mapStyle, Modifier.fillMaxSize(), onConfigureMap, showRider = true)
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
            ) {
                Column(Modifier.padding(horizontal = 28.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (paused) "PAUSED" else "CURRENT SPEED", style = MaterialTheme.typography.labelMedium, color = if (paused) Amber else TrailCyan)
                        Box(
                            Modifier.size(8.dp).background(
                                if (live.gpsStatus == GpsStatus.READY) Lime else Amber,
                                RoundedCornerShape(50),
                            ),
                        )
                        Text(gpsStatusLabel(live.gpsStatus), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        formatSpeed(live.speedMps, imperial),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                Metric("TIME", formatDuration((System.currentTimeMillis() - (live.startedAt ?: System.currentTimeMillis())).coerceAtLeast(0)))
                Metric("DISTANCE", formatDistance(live.distanceMeters, imperial))
                Metric("JUMPS", live.jumpCount.toString())
                Metric("AIRTIME", String.format(Locale.US, "%.1fs", live.flightTimeSeconds))
            }
            val modeLabel = if (live.mountingMode == MountingMode.POCKET) "Pocket mode" else "Bike mounted"
            val gpsLabel = when (live.gpsStatus) {
                GpsStatus.READY -> live.gpsAccuracyMeters?.let { " • GPS ±${it.roundToInt()} m" } ?: " • GPS ready"
                GpsStatus.ACQUIRING -> " • acquiring GPS fix"
                GpsStatus.POOR_SIGNAL -> " • ${live.gpsMessage ?: "poor GPS signal"}"
                GpsStatus.UNAVAILABLE -> " • GPS unavailable"
                GpsStatus.PERMISSION_DENIED -> " • precise location required"
                GpsStatus.ERROR -> " • ${live.gpsMessage ?: "GPS error"}"
            }
            Text(
                "$modeLabel • rejects below ${String.format(Locale.US, "%.2f", live.minimumJumpHeightMeters)} m$gpsLabel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onPauseResume, Modifier.weight(1f).height(58.dp)) {
                    Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                    Spacer(Modifier.width(8.dp)); Text(if (paused) "Resume" else "Pause")
                }
                Button(onClick = onFinish, Modifier.weight(1f).height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black)) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Finish")
                }
            }
        }
    }
}

private fun gpsStatusLabel(status: GpsStatus): String = when (status) {
    GpsStatus.ACQUIRING -> "GPS ACQUIRING"
    GpsStatus.READY -> "GPS ACTIVE"
    GpsStatus.POOR_SIGNAL -> "GPS WEAK"
    GpsStatus.UNAVAILABLE -> "GPS OFF"
    GpsStatus.PERMISSION_DENIED -> "GPS DENIED"
    GpsStatus.ERROR -> "GPS ERROR"
}

@Composable
private fun HistoryScreen(rides: List<RideEntity>, imperial: Boolean, onRide: (Long) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Ride history", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        if (rides.isEmpty()) item { EmptyCard("No rides yet", "Record a ride to build your trail history.") }
        items(rides, key = { it.id }) { ride ->
            Card(onClick = { onRide(ride.id) }) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Route, null, tint = TrailCyan, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(formatDate(ride.startedAt), fontWeight = FontWeight.Bold)
                        Text("${formatDistance(ride.distanceMeters, imperial)} • ${formatDuration(ride.movingTimeMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ride.state == RideState.INTERRUPTED) AssistChip(onClick = {}, label = { Text("Interrupted") })
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
    }
}

@Composable
private fun ReviewScreen(
    ride: RideEntity?,
    points: List<com.example.flightlog.data.TrackPointEntity>,
    jumps: List<JumpEventEntity>,
    stops: List<StopEventEntity>,
    mapApiKey: String,
    mapStyle: MapStyle,
    imperial: Boolean,
    onBack: () -> Unit,
    onOpenJump: (Long) -> Unit,
    onStatus: (Long, JumpStatus) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    deletesReferencedTrail: Boolean,
    onConfigureMap: () -> Unit,
) {
    if (ride == null) { EmptyCard("Ride unavailable", "This ride could not be loaded."); return }
    var confirmingDelete by rememberSaveable(ride.id) { mutableStateOf(false) }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            icon = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete this ride?") },
            text = {
                Text(
                    if (deletesReferencedTrail) {
                        "This permanently deletes the ride, route, jumps, sensor data, and its referenced trail comparison. This cannot be undone."
                    } else {
                        "This permanently deletes the ride, route, jumps, sensor data, and section efforts. This cannot be undone."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmingDelete = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete ride") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Review ride") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Export GPX and CSV") }
                IconButton(onClick = { confirmingDelete = true }) { Icon(Icons.Default.Delete, "Delete ride") }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        Box(Modifier.fillMaxWidth().height(280.dp)) {
            TrailMap(
                points = points,
                jumps = jumps,
                stopPoints = stops.map { stop ->
                    TrackPointEntity(
                        rideId = stop.rideId, recordedAt = stop.startedAt,
                        latitude = stop.latitude, longitude = stop.longitude,
                        altitudeMeters = null, speedMps = 0.0, bearingDegrees = null,
                        accuracyMeters = stop.accuracyMeters,
                    )
                },
                apiKey = mapApiKey,
                mapStyle = mapStyle,
                modifier = Modifier.matchParentSize(),
                onConfigureMap = onConfigureMap,
                fitRoute = true,
            )
            if (points.isEmpty()) {
                Box(
                    Modifier.matchParentSize()
                        .background(Color.Black.copy(alpha = .76f))
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                        .semantics {
                            disabled()
                            contentDescription = "No GPS data recorded"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.LocationOff, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(34.dp))
                        Text("No data recorded", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("No GPS route is available for this ride.", color = Color.White.copy(alpha = .72f))
                    }
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (points.isEmpty()) {
                item {
                    EmptyCard(
                        "No data recorded",
                        "No usable GPS positions were recorded. Sensor and jump data were discarded because they could not be reliably assigned to the trail.",
                    )
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        Metric("DISTANCE", formatDistance(ride.distanceMeters, imperial))
                        Metric("JUMPS", jumps.count { it.status == JumpStatus.CONFIRMED }.toString())
                        Metric("AIRTIME", String.format(Locale.US, "%.1fs", jumps.filter { it.status == JumpStatus.CONFIRMED }.sumOf { it.displayFlightSeconds }))
                    }
                }
                val pending = jumps.count { it.status == JumpStatus.PENDING }
                if (pending > 0) item {
                    Surface(color = Amber.copy(alpha = .18f), shape = RoundedCornerShape(14.dp)) {
                        Text("$pending jumps need review", Modifier.fillMaxWidth().padding(14.dp), color = Amber, fontWeight = FontWeight.Bold)
                    }
                }
                if (jumps.isEmpty()) item { EmptyCard("No jumps detected", "Rough impacts were filtered and no flight pattern was found.") }
                items(jumps, key = { it.id }) { jump ->
                    JumpCard(jump, imperial, { onOpenJump(jump.id) }, { onStatus(jump.id, it) })
                }
            }
        }
    }
}

@Composable
private fun JumpCard(jump: JumpEventEntity, imperial: Boolean, onEdit: () -> Unit, onStatus: (JumpStatus) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Jump ${jump.id}", fontWeight = FontWeight.Bold)
                SuggestionChip(onClick = {}, label = { Text("${jump.confidence}% confidence") })
            }
            Text("${String.format(Locale.US, "%.2fs", jump.displayFlightSeconds)} • ${formatHeight(jump.displayHeightMeters, imperial)} high • ${formatDistance(jump.displayDistanceMeters, imperial)} long")
            when (jump.status) {
                JumpStatus.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onStatus(JumpStatus.CONFIRMED) }) { Text("Confirm") }
                    OutlinedButton(onClick = onEdit) { Text("Edit") }
                    TextButton(onClick = { onStatus(JumpStatus.REJECTED) }) { Text("Discard") }
                }
                JumpStatus.CONFIRMED -> TextButton(onClick = onEdit) { Text("Confirmed • Edit") }
                JumpStatus.REJECTED -> TextButton(onClick = { onStatus(JumpStatus.PENDING) }) { Text("Discarded • Restore") }
            }
        }
    }
}

@Composable
private fun JumpDetailScreen(jump: JumpEventEntity?, imperial: Boolean, onBack: () -> Unit, onSave: (JumpEventEntity, Double, Double, Double) -> Unit) {
    if (jump == null) { EmptyCard("Jump unavailable", "Return to the ride review."); return }
    var flight by rememberSaveable(jump.id) { mutableStateOf(String.format(Locale.US, "%.2f", jump.displayFlightSeconds)) }
    var height by rememberSaveable(jump.id) { mutableStateOf(String.format(Locale.US, "%.2f", jump.displayHeightMeters)) }
    var distance by rememberSaveable(jump.id) { mutableStateOf(String.format(Locale.US, "%.2f", jump.displayDistanceMeters)) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Jump detail") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(color = TrailCyan.copy(alpha = .14f), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ESTIMATED FLIGHT", color = TrailCyan, style = MaterialTheme.typography.labelLarge)
                    Text("${String.format(Locale.US, "%.2f", jump.estimatedFlightSeconds)} s", fontSize = 42.sp, fontWeight = FontWeight.Black)
                    Text("${jump.confidence}% confidence • ${jump.sensorQuality.name.lowercase().replace('_', ' ')}")
                }
            }
            Text("Correct the estimate", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            DecimalField("Flight time (seconds)", flight) { flight = it }
            DecimalField("Height (meters)", height) { height = it }
            DecimalField("Distance (meters)", distance) { distance = it }
            Text("Height uses g × airtime² ÷ 8. Distance uses takeoff speed × airtime. Values are not surveyed measurements.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = { onSave(jump, flight.toDoubleOrNull() ?: jump.displayFlightSeconds, height.toDoubleOrNull() ?: jump.displayHeightMeters, distance.toDoubleOrNull() ?: jump.displayDistanceMeters) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Save and confirm") }
        }
    }
}

@Composable
private fun TrailsScreen(
    trails: List<TrailEntity>,
    sections: List<TrailSectionEntity>,
    passes: List<TrailPassEntity>,
    efforts: List<SectionEffortEntity>,
    imperial: Boolean,
    onOpen: (Long) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Trail comparisons", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item { Text("Repeated routes appear here after two same-direction matches. Trail timing uses spatial boundaries, not ride Start and Finish.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (trails.isEmpty()) item { EmptyCard("No repeated trails yet", "Record the same trail twice to receive a suggestion.") }
        items(trails, key = { it.id }) { trail ->
            val trailPasses = passes.filter { it.trailId == trail.id }
            val wholeSectionId = sections.firstOrNull {
                it.trailId == trail.id && it.kind == com.example.flightlog.domain.SectionKind.WHOLE_TRAIL
            }?.id
            val latestPass = trailPasses.maxByOrNull { it.startedAt }
            val latestEffort = efforts.firstOrNull {
                it.passId == latestPass?.id && it.sectionId == wholeSectionId && it.valid
            }
            Card(onClick = { onOpen(trail.id) }) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(trail.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            if (latestEffort != null) {
                                Text(
                                    "Latest: ${formatSpeed(latestEffort.averageSpeedMps, imperial)} average • ${formatSpeed(latestEffort.exitSpeedMps, imperial)} exit",
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Bold,
                                )
                            } else {
                                Text(
                                    "${trailPasses.size} matched runs • No complete speed comparison yet",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(Icons.Default.ChevronRight, "Open trail")
                    }
                    if (trail.state == TrailState.SUGGESTED) {
                        Button(onClick = { onOpen(trail.id) }) { Text("Set trail boundaries") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrailDetailScreen(
    trail: TrailEntity?, sections: List<TrailSectionEntity>, passes: List<TrailPassEntity>,
    efforts: List<SectionEffortEntity>, rides: List<RideEntity>, mode: ComparisonMode,
    selectedPassAId: Long?, selectedPassBId: Long?, pointsA: List<TrackPointEntity>, pointsB: List<TrackPointEntity>,
    canonicalProfiles: List<SpatialProfileEntity>, pauseZones: List<TrailPauseZoneEntity>,
    imperial: Boolean, mapApiKey: String, mapStyle: MapStyle, onBack: () -> Unit,
    onMode: (ComparisonMode) -> Unit, onPassA: (Long) -> Unit, onPassB: (Long) -> Unit,
    onConfirmSection: (Long) -> Unit, onConfirmTrail: (Long, String, Double, Double) -> Unit,
    onAddSection: (Long, String, Double, Double) -> Unit,
    onUpdateSection: (Long, String, Double, Double) -> Unit,
    onSavePauseZones: (Long, List<com.example.flightlog.ui.PauseZoneDraft>) -> Unit,
) {
    if (trail == null) { EmptyCard("Trail unavailable", "Return to trail comparisons."); return }
    val passIds = passes.mapTo(hashSetOf()) { it.id }
    val trailSectionIds = sections.mapTo(hashSetOf()) { it.id }
    val trailEfforts = efforts.filter { it.passId in passIds && it.sectionId in trailSectionIds }
    val comparableSectionCount = sections.count { section ->
        trailEfforts.filter { it.sectionId == section.id && it.valid }.map { it.passId }.distinct().size >= 2
    }
    val comparisonReady = passes.size >= 2 && comparableSectionCount > 0
    val wholeTrailSection = sections.firstOrNull { it.kind == com.example.flightlog.domain.SectionKind.WHOLE_TRAIL }
    val splitSections = sections.filter { it.kind == com.example.flightlog.domain.SectionKind.SPLIT }.sortedBy { it.startMeters }
    val activePauseZones = pauseZones.filter {
        it.state == com.example.flightlog.domain.PauseZoneState.AUTOMATIC ||
            it.state == com.example.flightlog.domain.PauseZoneState.USER_LOCKED
    }.sortedBy { it.startMeters }
    val savedTrailMap = remember(canonicalProfiles, trail.startMeters, trail.endMeters) {
        buildTrailMapRoute(canonicalProfiles, trail.startMeters, trail.endMeters)
    }
    val pauseZonePoints = remember(canonicalProfiles, activePauseZones) {
        activePauseZones.mapNotNull { zone -> pointAtDistance(canonicalProfiles, (zone.startMeters + zone.endMeters) / 2.0) }
    }
    val splitRoutes = remember(canonicalProfiles, splitSections) {
        splitSections.map { routeForRange(canonicalProfiles, it.startMeters, it.endMeters) }
    }
    val pauseZoneRoutes = remember(canonicalProfiles, activePauseZones) {
        activePauseZones.map { routeForRange(canonicalProfiles, it.startMeters, it.endMeters) }
    }
    val showingRunComparison = comparisonReady && mode == ComparisonMode.A_B
    var resultTab by rememberSaveable(trail.id) { mutableStateOf(TrailResultTab.SPLITS) }
    var splitContext by rememberSaveable(trail.id) { mutableStateOf(SplitEffortContext.ALL) }
    var selectedSplitIndex by rememberSaveable(trail.id) { mutableIntStateOf(0) }
    val safeSelectedSplitIndex = selectedSplitIndex.coerceIn(0, (splitSections.size - 1).coerceAtLeast(0))
    val selectedSplit = splitSections.getOrNull(safeSelectedSplitIndex)
    var showingSplitEditor by rememberSaveable(trail.id) { mutableStateOf(false) }
    var renamingSplit by remember { mutableStateOf<TrailSectionEntity?>(null) }
    var splitRenameText by rememberSaveable(trail.id) { mutableStateOf("") }
    var addingSection by rememberSaveable { mutableStateOf(false) }
    var sectionName by rememberSaveable { mutableStateOf("") }
    var sectionStart by rememberSaveable { mutableStateOf("") }
    var sectionEnd by rememberSaveable { mutableStateOf("") }
    var editingSection by remember { mutableStateOf<TrailSectionEntity?>(null) }
    var showingBoundaryEditor by rememberSaveable(trail.id) { mutableStateOf(trail.state == TrailState.SUGGESTED) }
    fun beginEditing(section: TrailSectionEntity) {
        if (section.kind == com.example.flightlog.domain.SectionKind.WHOLE_TRAIL) {
            showingBoundaryEditor = true
            return
        }
        sectionName = section.name
        sectionStart = section.startMeters.toInt().toString()
        sectionEnd = section.endMeters.toInt().toString()
        editingSection = section
    }
    editingSection?.let { editing ->
        AlertDialog(
            onDismissRequest = { editingSection = null },
            title = { Text("Edit section") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(sectionName, { sectionName = it.take(80) }, label = { Text("Section name") }, singleLine = true)
                    OutlinedTextField(sectionStart, { sectionStart = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Section starts at (m into trail)") }, singleLine = true)
                    OutlinedTextField(sectionEnd, { sectionEnd = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Section ends at (m into trail)") }, singleLine = true)
                }
            },
            confirmButton = { Button(onClick = {
                val start = sectionStart.toDoubleOrNull(); val end = sectionEnd.toDoubleOrNull()
                if (start != null && end != null && start < end) {
                    onUpdateSection(editing.id, sectionName, start, end); editingSection = null
                }
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingSection = null }) { Text("Cancel") } },
        )
    }
    if (showingBoundaryEditor) {
        TrailBoundaryEditor(
            trailId = trail.id,
            trailName = trail.name,
            profiles = canonicalProfiles,
            passes = passes,
            initialStartMeters = trail.startMeters,
            initialEndMeters = trail.endMeters,
            imperial = imperial,
            apiKey = mapApiKey,
            mapStyle = mapStyle,
            allowNameEdit = trail.state == TrailState.SUGGESTED,
            onDismiss = { showingBoundaryEditor = false },
            onSave = { name, start, end ->
                onConfirmTrail(trail.id, name, start, end)
                showingBoundaryEditor = false
            },
        )
    }
    if (showingSplitEditor) {
        PauseZoneEditor(
            trailId = trail.id,
            trailStartMeters = trail.startMeters,
            trailEndMeters = trail.endMeters,
            profiles = canonicalProfiles,
            zones = pauseZones,
            apiKey = mapApiKey,
            mapStyle = mapStyle,
            onDismiss = { showingSplitEditor = false },
            onSave = { drafts ->
                onSavePauseZones(trail.id, drafts)
                showingSplitEditor = false
            },
        )
    }
    renamingSplit?.let { split ->
        AlertDialog(
            onDismissRequest = { renamingSplit = null },
            title = { Text("Rename split") },
            text = {
                OutlinedTextField(
                    value = splitRenameText,
                    onValueChange = { splitRenameText = it.take(80) },
                    label = { Text("Split name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateSection(split.id, splitRenameText, split.startMeters, split.endMeters)
                    renamingSplit = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renamingSplit = null }) { Text("Cancel") } },
        )
    }
    if (addingSection) AlertDialog(
        onDismissRequest = { addingSection = false },
        title = { Text("Add manual section") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sectionName, { sectionName = it.take(80) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(sectionStart, { sectionStart = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("Start distance (m)") }, singleLine = true)
                OutlinedTextField(sectionEnd, { sectionEnd = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("End distance (m)") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = {
            val start = sectionStart.toDoubleOrNull()
            val end = sectionEnd.toDoubleOrNull()
            if (start != null && end != null && end <= trail.lengthMeters) {
                onAddSection(trail.id, sectionName, start, end); addingSection = false
            }
        }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { addingSection = false }) { Text("Cancel") } },
    )
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(trail.name) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (comparisonReady) IconButton(onClick = { addingSection = true }) { Icon(Icons.Default.Add, "Add section") }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            listOf(TrailResultTab.SPLITS to "Splits", TrailResultTab.FULL_RUNS to "Full runs").forEachIndexed { index, (tab, label) ->
                SegmentedButton(
                    selected = resultTab == tab,
                    onClick = { resultTab = tab },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(label) }
            }
        }
        if (comparisonReady) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                ComparisonMode.entries.forEachIndexed { index, value ->
                    SegmentedButton(selected = mode == value, onClick = { onMode(value) }, shape = SegmentedButtonDefaults.itemShape(index, 3)) {
                        Text(when (value) { ComparisonMode.TREND -> "Progress"; ComparisonMode.A_B -> "Compare"; ComparisonMode.VIRTUAL_BEST -> "Best" })
                    }
                }
            }
        }
        if (comparisonReady && mode == ComparisonMode.A_B) {
            val selectablePasses = if (resultTab == TrailResultTab.FULL_RUNS) passes.filter { it.fullRunEligible } else passes
            Text("Run A", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelMedium)
            PassPicker(selectablePasses, selectedPassAId, rides, onPassA)
            Text("Run B", Modifier.padding(start = 16.dp, top = 4.dp), style = MaterialTheme.typography.labelMedium)
            PassPicker(selectablePasses, selectedPassBId, rides, onPassB)
        }
        TrailMap(
            points = if (showingRunComparison) pointsA else savedTrailMap.fullRoute.ifEmpty { pointsA },
            comparisonPoints = pointsB.takeIf { showingRunComparison }.orEmpty(),
            highlightedPoints = savedTrailMap.highlightedRoute.takeUnless { showingRunComparison }.orEmpty(),
            stopPoints = pauseZonePoints,
            splitRoutes = splitRoutes.takeIf { resultTab == TrailResultTab.SPLITS && !showingRunComparison }.orEmpty(),
            pauseZoneRoutes = pauseZoneRoutes.takeUnless { showingRunComparison }.orEmpty(),
            selectedSplitIndex = safeSelectedSplitIndex,
            jumps = emptyList(), apiKey = mapApiKey, mapStyle = mapStyle,
            modifier = Modifier.fillMaxWidth().height(230.dp), fitRoute = true,
        )
        if (resultTab == TrailResultTab.SPLITS && splitSections.isNotEmpty()) {
            SplitRouteScrubber(
                trailStartMeters = trail.startMeters,
                trailEndMeters = trail.endMeters,
                splits = splitSections,
                zones = pauseZones,
                selectedIndex = safeSelectedSplitIndex,
                onSelect = { selectedSplitIndex = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (resultTab == TrailResultTab.FULL_RUNS) {
                item {
                    FullRunsPanel(
                        passes = passes,
                        efforts = trailEfforts,
                        wholeTrailSection = wholeTrailSection,
                        splitSections = splitSections,
                        mode = mode,
                        selectedPassAId = selectedPassAId,
                        selectedPassBId = selectedPassBId,
                        imperial = imperial,
                    )
                }
            } else {
                item { PauseZoneEvidence(pauseZones, onEdit = { showingSplitEditor = true }) }
                if (!comparisonReady) item {
                    ComparisonUnavailableCard(
                        matchedRuns = passes.size,
                        hasInterruptedData = trailEfforts.any { !it.valid },
                        onAdjustBoundaries = { wholeTrailSection?.let(::beginEditing) },
                    )
                }
                if (comparisonReady && mode == ComparisonMode.VIRTUAL_BEST) item {
                    val bests = splitSections.mapNotNull { section ->
                        efforts.filter {
                            it.sectionId == section.id && it.passId in passIds && it.valid &&
                                (splitContext == SplitEffortContext.ALL || it.reachedWithoutPriorStop)
                        }.minOfOrNull { it.elapsedMillis }
                    }
                    Surface(color = TrailCyan.copy(alpha = .14f), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("THEORETICAL VIRTUAL BEST", color = TrailCyan, style = MaterialTheme.typography.labelLarge)
                            Text(if (bests.isEmpty()) "No valid splits yet" else formatDuration(bests.sum()), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                            Text("Fastest valid effort from each timed split; not a single recorded run.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = splitContext == SplitEffortContext.ALL,
                            onClick = { splitContext = SplitEffortContext.ALL },
                            label = { Text("All efforts") },
                        )
                        FilterChip(
                            selected = splitContext == SplitEffortContext.NONSTOP,
                            onClick = { splitContext = SplitEffortContext.NONSTOP },
                            label = { Text("Reached nonstop") },
                        )
                    }
                }
                selectedSplit?.let { split ->
                    item(key = split.id) {
                        SectionComparisonCard(
                            split, passes, efforts, selectedPassAId, selectedPassBId, mode, imperial, onConfirmSection,
                            onEdit = {
                                splitRenameText = split.name
                                renamingSplit = split
                            },
                            nonstopOnly = splitContext == SplitEffortContext.NONSTOP,
                        )
                    }
                    val features = sections.filter {
                        it.kind != com.example.flightlog.domain.SectionKind.SPLIT &&
                            it.kind != com.example.flightlog.domain.SectionKind.WHOLE_TRAIL &&
                            it.startMeters >= split.startMeters && it.endMeters <= split.endMeters
                    }
                    if (features.isNotEmpty()) item {
                        Text(
                            "Inside this split: ${features.joinToString { it.name }}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonUnavailableCard(
    matchedRuns: Int,
    hasInterruptedData: Boolean,
    onAdjustBoundaries: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Speed, null, tint = Amber)
                Text("No speed comparison yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text("$matchedRuns matched ${if (matchedRuns == 1) "ride" else "rides"}", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            Text(
                when {
                    matchedRuns < 2 -> "Ride this trail again in the same direction to create a comparison."
                    hasInterruptedData -> "The rides were found, but a stop or GPS gap interrupted the comparable part of the trail."
                    else -> "The rides do not both cross the full trail boundaries. Trim the start or finish to the portion they share."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (matchedRuns >= 2) {
                Button(onClick = onAdjustBoundaries, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Tune, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Adjust trail start and finish")
                }
            }
        }
    }
}

@Composable
private fun PassPicker(passes: List<TrailPassEntity>, selectedId: Long?, rides: List<RideEntity>, onSelect: (Long) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(passes.take(12), key = { it.id }) { pass ->
            val date = rides.firstOrNull { it.id == pass.rideId }?.startedAt ?: pass.startedAt
            FilterChip(selected = pass.id == selectedId, onClick = { onSelect(pass.id) }, label = { Text(formatDate(date)) })
        }
    }
}

@Composable
private fun SectionComparisonCard(
    section: TrailSectionEntity, passes: List<TrailPassEntity>, allEfforts: List<SectionEffortEntity>,
    passAId: Long?, passBId: Long?, mode: ComparisonMode, imperial: Boolean, onConfirm: (Long) -> Unit, onEdit: () -> Unit,
    nonstopOnly: Boolean = false,
) {
    val passById = passes.associateBy { it.id }
    val recordedEfforts = allEfforts.filter { it.sectionId == section.id && it.passId in passById }
        .sortedByDescending { passById[it.passId]?.startedAt }
    val efforts = recordedEfforts.filter { it.valid && (!nonstopOnly || it.reachedWithoutPriorStop) }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        if (section.kind == com.example.flightlog.domain.SectionKind.WHOLE_TRAIL) "Trail overview" else section.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val kind = when (section.kind) {
                        com.example.flightlog.domain.SectionKind.TURN -> "Suggested turn"
                        com.example.flightlog.domain.SectionKind.ROUGH -> "Rough section"
                        com.example.flightlog.domain.SectionKind.MANUAL -> "Manual section"
                        com.example.flightlog.domain.SectionKind.WHOLE_TRAIL -> "Complete trail"
                        com.example.flightlog.domain.SectionKind.SPLIT -> "Timed split"
                    }
                    Text("$kind • ${(section.endMeters - section.startMeters).toInt()} m", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    TextButton(onClick = onEdit) { Text("Edit") }
                    if (section.state == SectionState.SUGGESTED) TextButton(onClick = { onConfirm(section.id) }) { Text("Confirm") }
                }
            }
            when (mode) {
                ComparisonMode.TREND -> {
                    val recent = efforts.firstOrNull()
                    val best = efforts.minByOrNull { it.elapsedMillis }
                    val recentMedian = efforts.take(5).map { it.elapsedMillis.toDouble() }.median()
                    if (recent == null) {
                        Text(
                            if (nonstopOnly && recordedEfforts.any { it.valid }) "No effort has reached this split without an earlier stop yet."
                            else if (recordedEfforts.isEmpty()) "No run has covered this entire section yet."
                            else invalidEffortMessage(recordedEfforts),
                            color = Amber,
                        )
                    } else {
                        Surface(color = TrailCyan.copy(alpha = .10f), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("LATEST RUN", color = TrailCyan, style = MaterialTheme.typography.labelLarge)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Metric("SECTION TIME", formatDuration(recent.elapsedMillis))
                                    Metric("AVG SPEED", formatSpeed(recent.averageSpeedMps, imperial))
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Metric("ENTRY SPEED", formatSpeed(recent.entrySpeedMps, imperial))
                                    Metric("EXIT SPEED", formatSpeed(recent.exitSpeedMps, imperial))
                                }
                                if (section.kind == com.example.flightlog.domain.SectionKind.TURN) {
                                    Text("Slowest point in turn: ${formatSpeed(recent.minimumSpeedMps, imperial)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (section.kind == com.example.flightlog.domain.SectionKind.SPLIT) {
                                    Text(
                                        "Minimum ${formatSpeed(recent.minimumSpeedMps, imperial)} • maximum ${formatSpeed(recent.maximumSpeedMps, imperial)}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        val bestDelta = best?.let { recent.elapsedMillis - it.elapsedMillis } ?: 0L
                        Text(
                            "Personal best ${best?.let { formatDuration(it.elapsedMillis) }} • latest ${if (bestDelta >= 0) "+" else "−"}${formatDuration(kotlin.math.abs(bestDelta))} • recent median ${recentMedian?.toLong()?.let(::formatDuration)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val now = System.currentTimeMillis()
                        val windows = listOf("Week" to 7L, "Month" to 30L, "Year" to 365L)
                        Text(windows.joinToString(" • ") { (label, days) ->
                            val values = efforts.filter { (passById[it.passId]?.startedAt ?: 0) >= now - days * 86_400_000L }.map { it.elapsedMillis.toDouble() }
                            "$label ${values.median()?.toLong()?.let(::formatDuration) ?: "—"}"
                        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                ComparisonMode.A_B -> {
                    val a = efforts.firstOrNull { it.passId == passAId }
                    val b = efforts.firstOrNull { it.passId == passBId }
                    if (a == null || b == null) Text("Select two complete, uninterrupted passes") else {
                        Text("A • ${formatDuration(a.elapsedMillis)} • ${formatSpeed(a.averageSpeedMps, imperial)} average • ${formatSpeed(a.exitSpeedMps, imperial)} exit", color = TrailCyan)
                        Text("B • ${formatDuration(b.elapsedMillis)} • ${formatSpeed(b.averageSpeedMps, imperial)} average • ${formatSpeed(b.exitSpeedMps, imperial)} exit", color = Amber)
                        val faster = if (a.elapsedMillis <= b.elapsedMillis) "A" else "B"
                        val line = if (RideMathLineDifference(a, b)) "$faster is faster and the traces are spatially distinct" else "$faster is faster; GPS cannot confirm a different line"
                        Text(line, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    }
                }
                ComparisonMode.VIRTUAL_BEST -> {
                    val best = efforts.minByOrNull { it.elapsedMillis }
                    if (best == null) Text("No complete run is available for this section.") else {
                        Text("Best section ${formatDuration(best.elapsedMillis)}")
                        Text("${formatSpeed(best.averageSpeedMps, imperial)} average • ${formatSpeed(best.exitSpeedMps, imperial)} exit", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    }
                }
            }
            efforts.firstOrNull()?.roughnessScore?.let { score ->
                val label = if (efforts.first().roughnessKind == com.example.flightlog.domain.RoughnessKind.BIKE_ROUGHNESS) "Bike roughness" else "Pocket disturbance"
                Text("$label ${String.format(Locale.US, "%.2f", score)} • ${efforts.first().sampleQuality}% signal quality", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

internal fun invalidEffortMessage(efforts: List<SectionEffortEntity>): String {
    val reasons = efforts.mapNotNullTo(hashSetOf()) { it.invalidReason }
    return when {
        reasons == setOf(EffortInvalidReason.STOP) ->
            "A stop occurred inside this section, so that pass was excluded."
        reasons == setOf(EffortInvalidReason.GPS_GAP) ->
            "GPS samples were missing inside this section, so no reliable speed is available."
        EffortInvalidReason.STOP in reasons && EffortInvalidReason.GPS_GAP in reasons ->
            "Some passes stopped here and others lost GPS, so no reliable comparison is available."
        else -> "No uninterrupted pass is available for this section yet."
    }
}

private fun List<Double>.median(): Double? = if (isEmpty()) null else sorted().let { sorted ->
    if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
}

private fun RideMathLineDifference(a: SectionEffortEntity, b: SectionEffortEntity): Boolean =
    com.example.flightlog.tracking.TrailAnalysis.lineDifferenceIsReliable(a, b)

@Composable
private fun DecimalField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() || c == '.' }) onValue(it) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StatsScreen(rides: List<RideEntity>, jumps: List<JumpEventEntity>, imperial: Boolean) {
    var period by rememberSaveable { mutableStateOf(AggregatePeriod.SEASON) }
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val tomorrow = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val year = RideMath.calendarYearBounds(now, zone)
    val totals = when (period) {
        AggregatePeriod.DAY -> RideMath.aggregate(rides, jumps, today, tomorrow)
        AggregatePeriod.SEASON -> RideMath.aggregate(rides, jumps, year.first, year.last + 1)
        AggregatePeriod.LIFETIME -> RideMath.aggregate(rides, jumps)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Your stats", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AggregatePeriod.entries.forEachIndexed { index, value ->
                    SegmentedButton(selected = period == value, onClick = { period = value }, shape = SegmentedButtonDefaults.itemShape(index, 3)) {
                        Text(when (value) { AggregatePeriod.DAY -> "Day"; AggregatePeriod.SEASON -> "Season"; AggregatePeriod.LIFETIME -> "Lifetime" })
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("Trail", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("RIDES", totals.rides.toString())
                        Metric("DISTANCE", formatDistance(totals.distanceMeters, imperial))
                        Metric("TIME", formatDuration(totals.movingTimeMillis))
                    }
                    HorizontalDivider()
                    Text("Flight", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric("CONFIRMED", totals.confirmedJumps.toString())
                        Metric("AIRTIME", String.format(Locale.US, "%.1fs", totals.flightTimeSeconds))
                        Metric("JUMPED", formatDistance(totals.jumpedDistanceMeters, imperial))
                    }
                    Text("${totals.pendingJumps} pending • ${totals.rejectedJumps} discarded", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    imperial: Boolean,
    recordingSettings: RecordingSettings,
    onImperial: (Boolean) -> Unit,
    onMountingMode: (MountingMode) -> Unit,
    onMinimumJumpHeight: (MountingMode, Float) -> Unit,
    savedMapApiKey: String,
    hasBundledMapApiKey: Boolean,
    onSaveMapApiKey: (String) -> Boolean,
    onClearMapApiKey: () -> Unit,
    mapStyle: MapStyle,
    onMapStyle: (MapStyle) -> Unit,
    tileCacheState: TileCacheState,
    onClearTileCache: () -> Unit,
    onSetTileCacheLimit: (Int) -> Unit,
    onRefreshTileCache: () -> Unit,
    onGuidance: () -> Unit,
    focusMapProvider: Boolean = false,
    onMapProviderFocused: () -> Unit = {},
    showHeading: Boolean = true,
    telemetryBytes: Long = 0,
    motionBytes: Long = 0,
    estimatedProfileBytes: Long = 0,
    nextMotionExpiry: Long? = null,
    backupState: BackupUiState = BackupUiState.Idle,
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { onRefreshTileCache() }
    LaunchedEffect(focusMapProvider) {
        if (focusMapProvider) {
            listState.animateScrollToItem(if (showHeading) 2 else 1)
            onMapProviderFocused()
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showHeading) item { Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Phone placement", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Choose the profile that will be used for your next ride.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        MountingMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = recordingSettings.mountingMode == mode,
                                onClick = { onMountingMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, MountingMode.entries.size),
                            ) {
                                Text(if (mode == MountingMode.POCKET) "Pocket" else "Bike mounted")
                            }
                        }
                    }
                    HorizontalDivider()
                    SensitivityControl(
                        label = "Pocket sensitivity",
                        minimumHeightMeters = recordingSettings.pocketMinimumHeightMeters,
                        onChange = { onMinimumJumpHeight(MountingMode.POCKET, it) },
                    )
                    SensitivityControl(
                        label = "Bike-mounted sensitivity",
                        minimumHeightMeters = recordingSettings.mountedMinimumHeightMeters,
                        onChange = { onMinimumJumpHeight(MountingMode.BIKE_MOUNTED, it) },
                    )
                    Text("Lower values detect smaller hops but may create more false positives. All detected jumps still require review.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            MapApiKeyCard(
                savedKey = savedMapApiKey,
                hasBundledKey = hasBundledMapApiKey,
                onSave = onSaveMapApiKey,
                onClear = onClearMapApiKey,
            )
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Map style", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "OpenCycleMap emphasizes cycling routes. Clean terrain removes route overlays for a less cluttered view at bike parks.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        MapStyle.entries.forEachIndexed { index, style ->
                            SegmentedButton(
                                selected = mapStyle == style,
                                onClick = { onMapStyle(style) },
                                shape = SegmentedButtonDefaults.itemShape(index, MapStyle.entries.size),
                            ) {
                                Text(style.displayName)
                            }
                        }
                    }
                }
            }
        }
        item { MapTileCacheCard(tileCacheState, onClearTileCache, onSetTileCacheLimit) }
        item {
            Card {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Imperial units", fontWeight = FontWeight.Bold); Text("Miles, mph, feet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(checked = imperial, onCheckedChange = onImperial)
                }
            }
        }
        item {
            Card(onClick = onGuidance) {
                ListItem(
                    headlineContent = { Text("Placement and estimate guide") },
                    supportingContent = { Text("Mount securely or use a snug pocket; review every estimate") },
                    leadingContent = { Icon(Icons.Default.HealthAndSafety, null, tint = Lime) },
                )
            }
        }
        item {
            Card {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ride data and backups", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    val totalMb = telemetryBytes / (1_024.0 * 1_024.0)
                    val motionMb = motionBytes / (1_024.0 * 1_024.0)
                    val profileMb = estimatedProfileBytes / (1_024.0 * 1_024.0)
                    Text(String.format(Locale.US, "Compressed telemetry %.1f MB • permanent profiles %.1f MB • raw motion %.1f MB", totalMb, profileMb, motionMb))
                    Text(
                        nextMotionExpiry?.let { "The next raw-motion cleanup is ${formatDate(it)}. GPS and permanent spatial profiles are retained." }
                            ?: "No raw-motion cleanup is currently pending.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onExportBackup, enabled = backupState !is BackupUiState.Working) { Text("Export full backup") }
                        OutlinedButton(onClick = onImportBackup, enabled = backupState !is BackupUiState.Working) { Text("Import") }
                    }
                    when (backupState) {
                        BackupUiState.Idle -> Unit
                        BackupUiState.Working -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        is BackupUiState.Success -> Text(backupState.message, color = Lime)
                        is BackupUiState.Error -> Text(backupState.message, color = MaterialTheme.colorScheme.error)
                    }
                    Text("Backups contain sensitive location history and are not encrypted by FlightLog. Store them somewhere you trust.", color = Amber, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item { Text("Privacy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item { Text("Ride locations and sensor-derived jump estimates remain on this device unless you export them. FlightLog has no account, analytics, or cloud sync.") }
        item { Text("Maps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item { Text("Thunderforest OpenCycleMap or Clean terrain is used when an API key and network tiles are available. Cached tiles may appear offline; route recording always continues over the fallback canvas.") }
    }
}

@Composable
private fun MapTileCacheCard(state: TileCacheState, onClear: () -> Unit, onSetLimit: (Int) -> Unit) {
    var draftLimit by rememberSaveable(state.maxCacheMegabytes) { mutableIntStateOf(state.maxCacheMegabytes) }
    val sizeMegabytes = state.sizeBytes / (1_024.0 * 1_024.0)
    val usageFraction = (sizeMegabytes / state.maxCacheMegabytes).toFloat().coerceIn(0f, 1f)
    Card {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cached, null, tint = TrailCyan)
                Spacer(Modifier.width(10.dp))
                Text("Downloaded map tile cache", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                "Tiles viewed online are retained automatically and reused offline. Older tiles roll out when the selected limit is reached.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cache used", fontWeight = FontWeight.Bold)
                Text(
                    String.format(Locale.US, "%.1f MB of %d MB", sizeMegabytes, state.maxCacheMegabytes),
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold,
                )
            }
            LinearProgressIndicator(
                progress = { usageFraction },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(state.monthLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Downloaded this month", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "${state.downloadedTilesThisMonth} tiles",
                        color = TrailCyan,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Rolling cache limit", fontWeight = FontWeight.Bold)
                Text("$draftLimit MB", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = draftLimit.toFloat(),
                onValueChange = { draftLimit = MapTileCache.normalizeLimit(it.roundToInt()) },
                valueRange = MapTileCache.MIN_CACHE_MEGABYTES.toFloat()..MapTileCache.MAX_CACHE_MEGABYTES.toFloat(),
                steps = 18,
                modifier = Modifier.semantics { contentDescription = "Rolling map tile cache limit" },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${MapTileCache.MIN_CACHE_MEGABYTES} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${MapTileCache.MAX_CACHE_MEGABYTES / 1_000} GB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                when (state.status) {
                    TileCacheStatus.CONFIGURING -> "Preparing tile cache…"
                    TileCacheStatus.READY -> "Tile cache ready"
                    TileCacheStatus.CLEARING -> "Clearing cached tiles…"
                    TileCacheStatus.CLEARED -> "Cached tiles cleared"
                    TileCacheStatus.ERROR -> state.errorMessage?.let { "Cache error: $it" } ?: "Tile cache unavailable"
                },
                color = if (state.status == TileCacheStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelLarge,
            )
            Button(
                onClick = { onSetLimit(draftLimit) },
                enabled = draftLimit != state.maxCacheMegabytes && state.status !in setOf(TileCacheStatus.CONFIGURING, TileCacheStatus.CLEARING),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apply $draftLimit MB limit") }
            OutlinedButton(
                onClick = onClear,
                enabled = state.status !in setOf(TileCacheStatus.CONFIGURING, TileCacheStatus.CLEARING),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.DeleteSweep, null)
                Spacer(Modifier.width(8.dp))
                Text("Clear cached tiles")
            }
        }
    }
}

@Composable
private fun MapApiKeyCard(
    savedKey: String,
    hasBundledKey: Boolean,
    onSave: (String) -> Boolean,
    onClear: () -> Unit,
) {
    var draft by rememberSaveable(savedKey) { mutableStateOf(savedKey) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val invalid = draft.isNotBlank() && !MapApiKeyStore.isValid(draft)
    Card {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Map provider", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Bring your own Thunderforest API key", fontWeight = FontWeight.Bold)
            Text(
                "The key is stored privately on this device and is sent only with Thunderforest tile requests.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 256) draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Thunderforest API key") },
                placeholder = { Text("Paste API key") },
                singleLine = true,
                isError = invalid,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            if (visible) "Hide API key" else "Show API key",
                        )
                    }
                },
                supportingText = {
                    Text(
                        when {
                            invalid -> "Use only letters, numbers, hyphens, and underscores."
                            savedKey.isNotBlank() -> "Using your API key"
                            hasBundledKey -> "Using the app-provided API key"
                            else -> "No API key configured"
                        },
                    )
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onSave(draft) },
                    enabled = draft.isNotBlank() && !invalid && draft.trim() != savedKey,
                ) { Text("Save key") }
                OutlinedButton(
                    onClick = {
                        onClear()
                        draft = ""
                    },
                    enabled = savedKey.isNotBlank(),
                ) { Text("Remove my key") }
            }
        }
    }
}

@Composable
private fun SensitivityControl(label: String, minimumHeightMeters: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.Bold)
            Text(
                "Reject below ${String.format(Locale.US, "%.2f m", minimumHeightMeters)}",
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = minimumHeightMeters,
            onValueChange = { value -> onChange((value * 20).roundToInt() / 20f) },
            valueRange = RecordingSettings.MINIMUM_HEIGHT_METERS..RecordingSettings.MAXIMUM_HEIGHT_METERS,
            steps = 18,
            modifier = Modifier.semantics { contentDescription = "$label minimum jump height" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("More sensitive", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("More rejection", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusChip(text: String, ready: Boolean) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .92f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (ready) Lime else Amber, RoundedCornerShape(50)))
            Spacer(Modifier.width(7.dp)); Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable
private fun EmptyCard(title: String, detail: String) {
    Card { Column(Modifier.fillMaxWidth().padding(20.dp)) { Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}

private fun startRideService(context: Context) {
    ContextCompat.startForegroundService(context, Intent(context, RideTrackingService::class.java).setAction(RideTrackingService.ACTION_START))
}

private fun sendRideAction(context: Context, action: String) {
    context.startService(Intent(context, RideTrackingService::class.java).setAction(action))
}

private fun formatSpeed(mps: Double, imperial: Boolean): String = if (imperial) {
    String.format(Locale.US, "%.1f mph", mps * 2.23694)
} else String.format(Locale.US, "%.1f km/h", mps * 3.6)

private fun formatDistance(meters: Double, imperial: Boolean): String = if (imperial) {
    if (meters < 1_609.344) String.format(Locale.US, "%.0f ft", meters * 3.28084) else String.format(Locale.US, "%.2f mi", meters / 1_609.344)
} else if (meters < 1_000) String.format(Locale.US, "%.0f m", meters) else String.format(Locale.US, "%.2f km", meters / 1_000)

private fun formatHeight(meters: Double, imperial: Boolean): String = if (imperial) String.format(Locale.US, "%.1f ft", meters * 3.28084) else String.format(Locale.US, "%.1f m", meters)

private fun formatDuration(millis: Long): String {
    val seconds = millis / 1_000
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remaining = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining) else "%02d:%02d".format(minutes, remaining)
}

private fun formatDate(timestamp: Long): String = DateTimeFormatter.ofPattern("EEE, MMM d • h:mm a")
    .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
