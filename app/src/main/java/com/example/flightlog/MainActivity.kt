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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
import com.example.flightlog.data.TrailDefinitionDraft
import com.example.flightlog.data.TrailEditImpact
import com.example.flightlog.data.BulkRideDeletePreview
import com.example.flightlog.data.BulkRideDeleteResult
import com.example.flightlog.domain.AggregatePeriod
import com.example.flightlog.domain.EffortInvalidReason
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.TrailState
import com.example.flightlog.domain.RideState
import com.example.flightlog.maps.MapApiKeyStore
import com.example.flightlog.maps.MapTileCache
import com.example.flightlog.maps.TileCacheState
import com.example.flightlog.maps.TileCacheStatus
import com.example.flightlog.tracking.LiveRideState
import com.example.flightlog.tracking.MotionTelemetry
import com.example.flightlog.tracking.JumpMotionTrace
import com.example.flightlog.tracking.JumpSensorAnalyzer
import com.example.flightlog.tracking.TimedValue
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
import com.example.flightlog.ui.SplitRouteScrubber
import com.example.flightlog.ui.PauseZoneEditor
import com.example.flightlog.ui.SplitOverviewCard
import com.example.flightlog.ui.FullRunsOverview
import com.example.flightlog.ui.IdealRunCard
import com.example.flightlog.ui.TrailComparisonScreen
import com.example.flightlog.ui.flightLogTopAppBarColors
import com.example.flightlog.ui.accelerationTrace
import com.example.flightlog.ui.PEAK_G_FILTER_MILLIS
import com.example.flightlog.ui.jumpNumbers
import com.example.flightlog.ui.routeForRange
import com.example.flightlog.ui.pointAtDistance
import com.example.flightlog.ui.prePumpSpeedMetersPerSecond
import com.example.flightlog.ui.pumpAccelerationPoint
import com.example.flightlog.ui.flightGpsSpeedSamples
import com.example.flightlog.ui.GpsSpeedPoint
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
import kotlin.math.abs
import kotlinx.coroutines.launch

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
    val comparisonProfilesA by vm.comparisonProfilesA.collectAsStateWithLifecycle()
    val comparisonProfilesB by vm.comparisonProfilesB.collectAsStateWithLifecycle()
    val selectedTrailProfiles by vm.selectedTrailProfiles.collectAsStateWithLifecycle()
    val telemetryBytes by vm.telemetryBytes.collectAsStateWithLifecycle()
    val motionBytes by vm.motionBytes.collectAsStateWithLifecycle()
    val nextMotionExpiry by vm.nextMotionExpiry.collectAsStateWithLifecycle()
    val estimatedProfileBytes by vm.estimatedProfileBytes.collectAsStateWithLifecycle()
    val backupState by vm.backupState.collectAsStateWithLifecycle()
    val points by vm.selectedPoints.collectAsStateWithLifecycle()
    val selectedJumps by vm.selectedRideJumps.collectAsStateWithLifecycle()
    val selectedJumpMotion by vm.selectedJumpMotion.collectAsStateWithLifecycle()
    val selectedRidePeakGForces by vm.selectedRidePeakGForces.collectAsStateWithLifecycle()
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
    val rideStorageBytes = if (
        screen == AppScreen.HISTORY || screen == AppScreen.SETTINGS || showActiveMapSettings
    ) {
        vm.rideStorageBytes.collectAsStateWithLifecycle().value
    } else {
        emptyMap()
    }
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
                    colors = flightLogTopAppBarColors(),
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
                    savedRideBytes = rideStorageBytes.values.sum(),
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
                    AppScreen.HISTORY -> HistoryScreen(
                        rides = rides,
                        imperial = imperial,
                        rideStorageBytes = rideStorageBytes,
                        onRide = vm::openRide,
                        onPreviewDelete = vm::previewBulkRideDeletion,
                        onDelete = vm::deleteRides,
                    )
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
                        savedRideBytes = rideStorageBytes.values.sum(),
                        nextMotionExpiry = nextMotionExpiry,
                        backupState = backupState,
                        onExportBackup = { backupExportLauncher.launch("flightlog-backup.flightlog.zip") },
                        onImportBackup = { backupImportLauncher.launch(arrayOf(FlightLogBackup.MIME_TYPE, "application/octet-stream")) },
                    )
                    AppScreen.REVIEW -> ReviewScreen(
                        ride = rides.firstOrNull { it.id == selectedRideId },
                        points = points,
                        jumps = selectedJumps,
                        peakGForces = selectedRidePeakGForces,
                        stops = selectedStops,
                        mapApiKey = effectiveMapApiKey,
                        mapStyle = mapStyle,
                        imperial = imperial,
                        onBack = { vm.screen.value = AppScreen.HISTORY },
                        selectedJumpId = selectedJumpId,
                        onSelectJump = vm::selectJump,
                        onOpenJump = vm::openJump,
                        onStatus = vm::setJumpStatus,
                        onShare = { selectedRideId?.let(vm::shareRide) },
                        onDelete = { selectedRideId?.let(vm::deleteRide) },
                        deletesReferencedTrail = trails.any { it.canonicalRideId == selectedRideId },
                        onConfigureMap = openMapSettings,
                        onLoadTrailProfiles = vm::trailProfiles,
                        onPreviewTrail = vm::previewTrailDefinition,
                        onSaveTrail = vm::saveTrailDefinition,
                    )
                    AppScreen.JUMP_DETAIL -> JumpDetailScreen(
                        jump = selectedJumps.firstOrNull { it.id == selectedJumpId },
                        jumpNumber = jumpNumbers(selectedJumps)[selectedJumpId],
                        points = points,
                        motion = selectedJumpMotion,
                        mountingMode = rides.firstOrNull { it.id == selectedRideId }?.mountingMode,
                        mapApiKey = effectiveMapApiKey,
                        mapStyle = mapStyle,
                        imperial = imperial,
                        onBack = { vm.screen.value = AppScreen.REVIEW },
                        onConfigureMap = openMapSettings,
                    )
                    AppScreen.TRAIL_DETAIL -> TrailDetailScreen(
                        trail = trails.firstOrNull { it.id == selectedTrailId },
                        sections = sections.filter { it.trailId == selectedTrailId },
                        passes = passes.filter { it.trailId == selectedTrailId },
                        efforts = efforts,
                        rides = rides,
                        selectedPassAId = selectedPassAId,
                        selectedPassBId = selectedPassBId,
                        profilesA = comparisonProfilesA,
                        profilesB = comparisonProfilesB,
                        canonicalProfiles = selectedTrailProfiles,
                        pauseZones = pauseZones.filter { it.trailId == selectedTrailId },
                        imperial = imperial,
                        mapApiKey = effectiveMapApiKey,
                        mapStyle = mapStyle,
                        onBack = { vm.screen.value = AppScreen.TRAILS },
                        onPassA = { vm.selectedPassAId.value = it },
                        onPassB = { vm.selectedPassBId.value = it },
                        onLoadTrailProfiles = vm::trailProfiles,
                        onPreviewTrail = vm::previewTrailDefinition,
                        onSaveTrail = vm::saveTrailDefinition,
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

internal fun toggleRideSelection(selected: Set<Long>, rideId: Long): Set<Long> =
    if (rideId in selected) selected - rideId else selected + rideId

internal fun toggleAllRideSelection(selected: Set<Long>, eligible: Set<Long>): Set<Long> =
    if (eligible.isNotEmpty() && selected.containsAll(eligible)) emptySet() else eligible

@Composable
private fun BulkRideDeleteDialog(
    preview: BulkRideDeletePreview,
    working: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        icon = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete ${preview.rideIds.size} ${if (preview.rideIds.size == 1) "ride" else "rides"}?") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text("This permanently removes the selected rides, GPS routes, jumps, sensor data, and stops. This cannot be undone.")
                }
                if (preview.reassignments.isNotEmpty()) {
                    item { Text("Trails kept with a new reference ride", fontWeight = FontWeight.Bold) }
                    items(preview.reassignments, key = { "reassign:${it.trailId}" }) { reassignment ->
                        Text("• ${reassignment.trailName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (preview.deletedTrails.isNotEmpty()) {
                    item { Text("Trails that will also be deleted", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) }
                    items(preview.deletedTrails, key = { "delete:${it.trailId}" }) { trail ->
                        Text("• ${trail.trailName}", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (preview.removedCustomItems.isNotEmpty()) {
                    item { Text("Custom trail items that cannot be remapped", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) }
                    preview.reassignments.filter { it.removedItems.isNotEmpty() }.forEach { reassignment ->
                        item(key = "custom-trail:${reassignment.trailId}") {
                            Text(reassignment.trailName, fontWeight = FontWeight.Bold)
                        }
                        items(reassignment.removedItems, key = { it.key }) { item ->
                            Text("• ${item.name}", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                errorMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !working,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Delete permanently")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } },
    )
}

@Composable
internal fun HistoryScreen(
    rides: List<RideEntity>,
    imperial: Boolean,
    rideStorageBytes: Map<Long, Long> = emptyMap(),
    onRide: (Long) -> Unit,
    onPreviewDelete: suspend (Set<Long>) -> BulkRideDeletePreview,
    onDelete: suspend (BulkRideDeletePreview) -> BulkRideDeleteResult,
) {
    val scope = rememberCoroutineScope()
    val eligibleRideIds = remember(rides) {
        rides.filter { it.state != RideState.RECORDING && it.state != RideState.PAUSED }.mapTo(linkedSetOf()) { it.id }
    }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedRideIds by rememberSaveable { mutableStateOf(longArrayOf()) }
    val selected = selectedRideIds.toSet()
    var preview by remember { mutableStateOf<BulkRideDeletePreview?>(null) }
    var working by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun setSelected(ids: Set<Long>) {
        selectedRideIds = ids.sorted().toLongArray()
        errorMessage = null
    }
    fun cancelSelection() {
        selectionMode = false
        setSelected(emptySet())
        preview = null
        errorMessage = null
    }

    LaunchedEffect(eligibleRideIds) {
        val retained = selected.intersect(eligibleRideIds)
        if (retained != selected) setSelected(retained)
    }
    BackHandler(enabled = selectionMode && !working) { cancelSelection() }

    preview?.let { impact ->
        BulkRideDeleteDialog(
            preview = impact,
            working = working,
            errorMessage = errorMessage,
            onDismiss = { if (!working) { preview = null; errorMessage = null } },
            onConfirm = {
                scope.launch {
                    working = true
                    errorMessage = null
                    runCatching { onDelete(impact) }
                        .onSuccess { cancelSelection() }
                        .onFailure { errorMessage = it.message ?: "Unable to delete the selected rides." }
                    working = false
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                IconButton(onClick = ::cancelSelection, enabled = !working) { Icon(Icons.Default.Close, "Cancel selection") }
                Text("${selected.size} selected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { setSelected(toggleAllRideSelection(selected, eligibleRideIds)) },
                    enabled = eligibleRideIds.isNotEmpty() && !working,
                ) { Text(if (selected.containsAll(eligibleRideIds) && eligibleRideIds.isNotEmpty()) "Clear all" else "Select all") }
                IconButton(
                    onClick = {
                        scope.launch {
                            working = true
                            errorMessage = null
                            runCatching { onPreviewDelete(selected) }
                                .onSuccess { preview = it }
                                .onFailure { errorMessage = it.message ?: "Unable to review these rides." }
                            working = false
                        }
                    },
                    enabled = selected.isNotEmpty() && !working,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    if (working) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Delete, "Delete selected rides")
                }
            } else {
                Text("Ride history", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { selectionMode = true },
                    enabled = eligibleRideIds.isNotEmpty(),
                ) { Text("Select") }
            }
        }
        errorMessage?.takeIf { preview == null }?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rides.isEmpty()) item { EmptyCard("No rides yet", "Record a ride to build your trail history.") }
            items(rides, key = { it.id }) { ride ->
                val selectedRide = ride.id in selected
                val eligible = ride.id in eligibleRideIds
                Card(
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Ride ${formatDate(ride.startedAt)}${if (selectedRide) ", selected" else ""}"
                    }.combinedClickable(
                        enabled = !working,
                        onClick = {
                            if (selectionMode) {
                                if (eligible) setSelected(toggleRideSelection(selected, ride.id))
                            } else {
                                onRide(ride.id)
                            }
                        },
                        onLongClick = {
                            if (eligible) {
                                selectionMode = true
                                setSelected(toggleRideSelection(selected, ride.id))
                            }
                        },
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedRide) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Route, null, tint = TrailCyan, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(formatDate(ride.startedAt), fontWeight = FontWeight.Bold)
                            Text("${formatDistance(ride.distanceMeters, imperial)} • ${formatDuration(ride.movingTimeMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "About ${formatDataSize(rideStorageBytes[ride.id] ?: 0L)} saved",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (ride.state == RideState.INTERRUPTED) AssistChip(onClick = {}, label = { Text("Interrupted") })
                        if (selectionMode) {
                            Checkbox(
                                checked = selectedRide,
                                onCheckedChange = { if (eligible) setSelected(toggleRideSelection(selected, ride.id)) },
                                enabled = eligible && !working,
                            )
                        } else {
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
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
    peakGForces: Map<Long, Double>,
    stops: List<StopEventEntity>,
    mapApiKey: String,
    mapStyle: MapStyle,
    imperial: Boolean,
    onBack: () -> Unit,
    selectedJumpId: Long?,
    onSelectJump: (Long) -> Unit,
    onOpenJump: (Long) -> Unit,
    onStatus: (Long, JumpStatus) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    deletesReferencedTrail: Boolean,
    onConfigureMap: () -> Unit,
    onLoadTrailProfiles: suspend (Long) -> List<SpatialProfileEntity>,
    onPreviewTrail: suspend (TrailDefinitionDraft) -> TrailEditImpact,
    onSaveTrail: suspend (TrailDefinitionDraft, Set<String>) -> Long,
) {
    if (ride == null) { EmptyCard("Ride unavailable", "This ride could not be loaded."); return }
    val numbers = remember(jumps) { jumpNumbers(jumps) }
    val jumpListState = rememberLazyListState()
    val pendingCount = jumps.count { it.status == JumpStatus.PENDING }
    var focusFastestSegmentKey by rememberSaveable(ride.id) { mutableIntStateOf(0) }
    LaunchedEffect(selectedJumpId, jumps) {
        val index = jumps.indexOfFirst { it.id == selectedJumpId }
        if (index >= 0) jumpListState.animateScrollToItem(1 + (if (pendingCount > 0) 1 else 0) + index)
    }
    var confirmingDelete by rememberSaveable(ride.id) { mutableStateOf(false) }
    var creatingTrail by rememberSaveable(ride.id) { mutableStateOf(false) }
    if (creatingTrail) {
        TrailBoundaryEditor(
            trailId = null,
            trailName = "New trail",
            referenceRides = listOf(ride),
            initialReferenceRideId = ride.id,
            initialProfiles = emptyList(),
            initialStartMeters = 0.0,
            initialEndMeters = ride.distanceMeters,
            imperial = imperial,
            apiKey = mapApiKey,
            mapStyle = mapStyle,
            onDismiss = { creatingTrail = false },
            onLoadProfiles = onLoadTrailProfiles,
            onPreview = onPreviewTrail,
            onSave = onSaveTrail,
            onApplied = { creatingTrail = false },
        )
    }
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
                IconButton(
                    onClick = { creatingTrail = true },
                    enabled = points.isNotEmpty() && ride.state != RideState.RECORDING && ride.state != RideState.PAUSED,
                ) { Icon(Icons.Default.Route, "Create trail from this ride") }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Export GPX and CSV") }
                IconButton(onClick = { confirmingDelete = true }) { Icon(Icons.Default.Delete, "Delete ride") }
            },
            colors = flightLogTopAppBarColors(),
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
                selectedJumpId = selectedJumpId,
                onJumpClick = onSelectJump,
                showSpeedGradient = true,
                focusFastestSegmentKey = focusFastestSegmentKey,
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
        LazyColumn(
            state = jumpListState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (points.isEmpty()) {
                item {
                    EmptyCard(
                        "No data recorded",
                        "No usable GPS positions were recorded. Sensor and jump data were discarded because they could not be reliably assigned to the trail.",
                    )
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Metric("DISTANCE", formatDistance(ride.distanceMeters, imperial))
                        Metric(
                            "TOP SPEED",
                            formatSpeed(ride.maxSpeedMps, imperial),
                            prominent = true,
                            onClick = { focusFastestSegmentKey += 1 },
                        )
                        Metric(
                            "AVG SPEED",
                            averageMovingSpeedMps(ride.distanceMeters, ride.movingTimeMillis)
                                ?.let { formatSpeed(it, imperial) } ?: "—",
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Metric("JUMPS", jumps.count { it.status == JumpStatus.CONFIRMED }.toString())
                        Metric("AIRTIME", String.format(Locale.US, "%.1fs", jumps.filter { it.status == JumpStatus.CONFIRMED }.sumOf { it.displayFlightSeconds }))
                    }
                }
                if (pendingCount > 0) item {
                    Surface(color = Amber.copy(alpha = .18f), shape = RoundedCornerShape(14.dp)) {
                        Text("$pendingCount jumps need review", Modifier.fillMaxWidth().padding(14.dp), color = Amber, fontWeight = FontWeight.Bold)
                    }
                }
                if (jumps.isEmpty()) item { EmptyCard("No jumps detected", "Rough impacts were filtered and no flight pattern was found.") }
                items(jumps, key = { it.id }) { jump ->
                    JumpCard(
                        jump = jump,
                        number = numbers.getValue(jump.id),
                        imperial = imperial,
                        peakGForce = peakGForces[jump.id],
                        selected = jump.id == selectedJumpId,
                        onSelect = { onSelectJump(jump.id) },
                        onOpen = { onOpenJump(jump.id) },
                        onStatus = { onStatus(jump.id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun JumpCard(
    jump: JumpEventEntity,
    number: Int,
    imperial: Boolean,
    peakGForce: Double?,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onStatus: (JumpStatus) -> Unit,
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Amber.copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.semantics { contentDescription = "Jump $number${if (selected) ", selected" else ""}" },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Jump $number", fontWeight = FontWeight.Bold)
                SuggestionChip(onClick = {}, label = { Text("${jump.confidence}% confidence") })
            }
            Text("${String.format(Locale.US, "%.2fs", jump.displayFlightSeconds)} • ${formatHeight(jump.displayHeightMeters, imperial)} high • ${formatDistance(jump.displayDistanceMeters, imperial)} long")
            peakGForce?.let {
                Text(
                    "Peak ${String.format(Locale.US, "%.1f g", it)} • ${PEAK_G_FILTER_MILLIS} ms filtered",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when (jump.status) {
                JumpStatus.PENDING -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onStatus(JumpStatus.CONFIRMED) }, modifier = Modifier.weight(1f)) { Text("Confirm") }
                        OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("View flight") }
                    }
                    TextButton(onClick = { onStatus(JumpStatus.REJECTED) }) { Text("Discard") }
                }
                JumpStatus.CONFIRMED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpen) { Text("View flight") }
                    TextButton(onClick = { onStatus(JumpStatus.REJECTED) }) { Text("Discard") }
                }
                JumpStatus.REJECTED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpen) { Text("View flight") }
                    TextButton(onClick = { onStatus(JumpStatus.PENDING) }) { Text("Restore") }
                }
            }
        }
    }
}

@Composable
private fun JumpDetailScreen(
    jump: JumpEventEntity?,
    jumpNumber: Int?,
    points: List<TrackPointEntity>,
    motion: MotionTelemetry,
    mountingMode: MountingMode?,
    mapApiKey: String,
    mapStyle: MapStyle,
    imperial: Boolean,
    onBack: () -> Unit,
    onConfigureMap: () -> Unit,
) {
    if (jump == null) { EmptyCard("Jump unavailable", "Return to the ride review."); return }
    val nearbyPoints = remember(jump.id, points) {
        val window = (jump.takeoffAt - JumpMotionTrace.PRE_TAKEOFF_MILLIS)..(jump.landingAt + JumpMotionTrace.POST_LANDING_MILLIS)
        points.filter { it.recordedAt in window }.ifEmpty {
            points.sortedBy { abs(it.recordedAt - jump.takeoffAt) }.take(20).sortedBy { it.recordedAt }
        }
    }
    val acceleration = remember(jump.id, motion) { accelerationTrace(jump, motion.accelerationFrames()) }
    val prePumpSpeed = remember(jump.id, acceleration, points) {
        prePumpSpeedMetersPerSecond(jump, acceleration, points)
    }
    val flightSpeeds = remember(jump.id, points) { flightGpsSpeedSamples(jump, points) }
    val sensorAnalysis = remember(jump, motion, mountingMode) { JumpSensorAnalyzer.analyze(jump, motion, mountingMode) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Jump ${jumpNumber ?: ""}".trim()) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = flightLogTopAppBarColors(),
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if ((jump.latitude != null && jump.longitude != null) || nearbyPoints.isNotEmpty()) item {
                Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().height(190.dp)) {
                    TrailMap(
                        points = nearbyPoints,
                        jumps = listOf(jump),
                        apiKey = mapApiKey,
                        mapStyle = mapStyle,
                        modifier = Modifier.fillMaxSize(),
                        onConfigureMap = onConfigureMap,
                        selectedJumpId = jump.id,
                    )
                }
            }
            item {
                Surface(color = TrailCyan.copy(alpha = .14f), shape = RoundedCornerShape(20.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("JUMP ESTIMATE", color = TrailCyan, style = MaterialTheme.typography.labelLarge)
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                JumpDetailMetric("FLIGHT", String.format(Locale.US, "%.2f s", jump.displayFlightSeconds), Modifier.weight(1f))
                                JumpDetailMetric("HEIGHT", formatHeight(jump.displayHeightMeters, imperial), Modifier.weight(1f))
                            }
                            Row(Modifier.fillMaxWidth()) {
                                JumpDetailMetric("DISTANCE", formatDistance(jump.displayDistanceMeters, imperial), Modifier.weight(1f))
                                JumpDetailMetric("TAKEOFF SPEED", prePumpSpeed?.let { formatSpeed(it, imperial) } ?: "—", Modifier.weight(1f))
                            }
                        }
                        Text("${jump.confidence}% confidence • ${jump.sensorQuality.name.lowercase().replace('_', ' ')}")
                    }
                }
            }
            item {
                FlightGpsSpeedCard(flightSpeeds, imperial)
            }
            item {
                AccelerationTraceChart(
                    trace = acceleration,
                    verticalTrace = sensorAnalysis.worldVerticalAcceleration,
                    takeoffAt = jump.takeoffAt,
                    flightMillis = jump.landingAt - jump.takeoffAt,
                )
            }
        }
    }
}

@Composable
private fun FlightGpsSpeedCard(samples: List<GpsSpeedPoint>, imperial: Boolean) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GPS speed during flight", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (samples.isEmpty()) {
                Text(
                    "No GPS speed sample was recorded between takeoff and landing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "${samples.size} sample${if (samples.size == 1) "" else "s"} from takeoff through landing",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                samples.forEachIndexed { index, sample ->
                    if (index > 0) HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(String.format(Locale.US, "+%.3f s", sample.millisFromTakeoff / 1_000.0))
                        Text(formatSpeed(sample.speedMps, imperial), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun JumpDetailMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AccelerationTraceChart(
    trace: List<com.example.flightlog.ui.AccelerationPoint>,
    verticalTrace: List<TimedValue>,
    takeoffAt: Long,
    flightMillis: Long,
) {
    val scrollState = rememberScrollState()
    val chartStart = -JumpMotionTrace.PRE_TAKEOFF_MILLIS
    val chartEnd = flightMillis + JumpMotionTrace.POST_LANDING_MILLIS
    val chartDurationMillis = (chartEnd - chartStart).coerceAtLeast(1L)
    val chartWidth = (chartDurationMillis / 1_000f * JUMP_CHART_DP_PER_SECOND).dp
    val density = LocalDensity.current
    LaunchedEffect(scrollState.maxValue, chartWidth, flightMillis) {
        if (scrollState.maxValue > 0) {
            val contentWidth = with(density) { chartWidth.roundToPx() }
            val viewportWidth = contentWidth - scrollState.maxValue
            val flightCenter = with(density) {
                ((JumpMotionTrace.PRE_TAKEOFF_MILLIS + flightMillis / 2f) / 1_000f * JUMP_CHART_DP_PER_SECOND).dp.roundToPx()
            }
            scrollState.scrollTo((flightCenter - viewportWidth / 2).coerceIn(0, scrollState.maxValue))
        }
    }
    val lineColor = Amber
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val flightColor = TrailCyan.copy(alpha = .14f)
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Measured phone acceleration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (trace.size < 2) {
                Text(
                    "Sensor trace unavailable for this ride.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "Measured sensor trace unavailable" },
                )
            } else {
                val verticalG = verticalTrace.map { TimedValue(it.timestampMillis - takeoffAt, it.value / 9.80665) }
                val minimumG = minOf(-1.25, verticalG.minOfOrNull { it.value } ?: 0.0)
                val maximumG = maxOf(trace.maxOf { it.magnitudeG }, verticalG.maxOfOrNull { it.value } ?: 0.0, 2.0)
                val pump = pumpAccelerationPoint(trace)
                Column(Modifier.horizontalScroll(scrollState)) {
                    Canvas(
                        Modifier.width(chartWidth).height(140.dp).semantics {
                            contentDescription = "Measured acceleration from 10 seconds before takeoff through 10 seconds after landing; peak ${String.format(Locale.US, "%.1f", maximumG)} g"
                        },
                    ) {
                        fun x(milliseconds: Long) = ((milliseconds - chartStart).toFloat() / chartDurationMillis) * size.width
                        fun y(valueG: Double) = ((maximumG - valueG) / (maximumG - minimumG) * size.height).toFloat()
                        val takeoffX = x(0L).coerceIn(0f, size.width)
                        val landingX = x(flightMillis).coerceIn(0f, size.width)
                        drawRect(flightColor, topLeft = Offset(takeoffX, 0f), size = androidx.compose.ui.geometry.Size((landingX - takeoffX).coerceAtLeast(0f), size.height))
                        drawLine(gridColor, Offset(0f, y(1.0)), Offset(size.width, y(1.0)), strokeWidth = 2f)
                        if (verticalG.isNotEmpty()) drawLine(gridColor, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 2f)
                        val path = Path()
                        trace.forEachIndexed { index, point ->
                            val px = x(point.millisFromTakeoff)
                            val py = y(point.magnitudeG)
                            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        drawPath(path, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))
                        if (verticalG.size >= 2) {
                            val verticalPath = Path()
                            verticalG.forEachIndexed { index, point ->
                                val px = x(point.timestampMillis)
                                val py = y(point.value)
                                if (index == 0) verticalPath.moveTo(px, py) else verticalPath.lineTo(px, py)
                            }
                            drawPath(verticalPath, TrailCyan, style = Stroke(width = 3f, cap = StrokeCap.Round))
                        }
                        pump?.let { point ->
                            drawCircle(
                                Lime,
                                radius = 5.dp.toPx(),
                                center = Offset(x(point.millisFromTakeoff), y(point.magnitudeG)),
                            )
                        }
                        drawLine(TrailCyan, Offset(takeoffX, 0f), Offset(takeoffX, size.height), strokeWidth = 2f)
                        drawLine(TrailCyan, Offset(landingX, 0f), Offset(landingX, size.height), strokeWidth = 2f)
                    }
                    Box(Modifier.width(chartWidth).height(42.dp)) {
                        Text("10s before", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.TopStart))
                        Text(
                            "Takeoff",
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(64.dp).offset(
                                x = (JumpMotionTrace.PRE_TAKEOFF_MILLIS / 1_000f * JUMP_CHART_DP_PER_SECOND - 32f).dp,
                            ),
                        )
                        Text(
                            "Landing",
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(64.dp).offset(
                                x = ((JumpMotionTrace.PRE_TAKEOFF_MILLIS + flightMillis) / 1_000f * JUMP_CHART_DP_PER_SECOND - 32f).dp,
                                y = 20.dp,
                            ),
                        )
                        Text("10s after", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.BottomEnd))
                    }
                }
                Text("Swipe horizontally to inspect the full 10-second context on either side.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (verticalG.isNotEmpty()) {
                    Text("Amber: total force • cyan: orientation-corrected vertical", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private const val JUMP_CHART_DP_PER_SECOND = 200f

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
    efforts: List<SectionEffortEntity>, rides: List<RideEntity>,
    selectedPassAId: Long?, selectedPassBId: Long?, profilesA: List<SpatialProfileEntity>, profilesB: List<SpatialProfileEntity>,
    canonicalProfiles: List<SpatialProfileEntity>, pauseZones: List<TrailPauseZoneEntity>,
    imperial: Boolean, mapApiKey: String, mapStyle: MapStyle, onBack: () -> Unit,
    onPassA: (Long) -> Unit, onPassB: (Long) -> Unit,
    onLoadTrailProfiles: suspend (Long) -> List<SpatialProfileEntity>,
    onPreviewTrail: suspend (TrailDefinitionDraft) -> TrailEditImpact,
    onSaveTrail: suspend (TrailDefinitionDraft, Set<String>) -> Long,
    onAddSection: (Long, String, Double, Double) -> Unit,
    onUpdateSection: (Long, String, Double, Double) -> Unit,
    onSavePauseZones: (Long, List<com.example.flightlog.ui.PauseZoneDraft>) -> Unit,
) {
    if (trail == null) { EmptyCard("Trail unavailable", "Return to trail comparisons."); return }
    val passIds = passes.mapTo(hashSetOf()) { it.id }
    val trailSectionIds = sections.mapTo(hashSetOf()) { it.id }
    val trailEfforts = efforts.filter { it.passId in passIds && it.sectionId in trailSectionIds }
    val wholeTrailSection = sections.firstOrNull { it.kind == com.example.flightlog.domain.SectionKind.WHOLE_TRAIL }
    val splitSections = sections.filter { it.kind == com.example.flightlog.domain.SectionKind.SPLIT }.sortedBy { it.startMeters }
    val splitComparisonReady = passes.size >= 2 && splitSections.any { section ->
        trailEfforts.filter { it.sectionId == section.id && it.valid }.map { it.passId }.distinct().size >= 2
    }
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
    var resultTab by rememberSaveable(trail.id) { mutableStateOf(TrailResultTab.SPLITS) }
    var noEarlierStops by rememberSaveable(trail.id) { mutableStateOf(false) }
    var selectedSplitIndex by rememberSaveable(trail.id) { mutableIntStateOf(0) }
    val safeSelectedSplitIndex = selectedSplitIndex.coerceIn(0, (splitSections.size - 1).coerceAtLeast(0))
    val selectedSplit = splitSections.getOrNull(safeSelectedSplitIndex)
    var showingSplitEditor by rememberSaveable(trail.id) { mutableStateOf(false) }
    var showingComparison by rememberSaveable(trail.id) { mutableStateOf(false) }
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
        val referenceRideIds = passes.mapTo(linkedSetOf(trail.canonicalRideId)) { it.rideId }
        TrailBoundaryEditor(
            trailId = trail.id,
            trailName = trail.name,
            referenceRides = rides.filter { it.id in referenceRideIds },
            initialReferenceRideId = trail.canonicalRideId,
            initialProfiles = canonicalProfiles,
            initialStartMeters = trail.startMeters,
            initialEndMeters = trail.endMeters,
            imperial = imperial,
            apiKey = mapApiKey,
            mapStyle = mapStyle,
            onDismiss = { showingBoundaryEditor = false },
            onLoadProfiles = onLoadTrailProfiles,
            onPreview = onPreviewTrail,
            onSave = onSaveTrail,
            onApplied = { showingBoundaryEditor = false },
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
    if (showingComparison) {
        TrailComparisonScreen(
            resultTab = resultTab,
            trail = trail,
            sections = sections,
            passes = passes,
            efforts = trailEfforts,
            rides = rides,
            canonicalProfiles = canonicalProfiles,
            profilesA = profilesA,
            profilesB = profilesB,
            selectedPassAId = selectedPassAId,
            selectedPassBId = selectedPassBId,
            onPassA = onPassA,
            onPassB = onPassB,
            imperial = imperial,
            mapApiKey = mapApiKey,
            mapStyle = mapStyle,
            onDismiss = { showingComparison = false },
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
                if (passes.size >= 2) IconButton(onClick = { addingSection = true }) { Icon(Icons.Default.Add, "Add section") }
                IconButton(onClick = { showingBoundaryEditor = true }) { Icon(Icons.Default.Edit, "Edit trail") }
            },
            colors = flightLogTopAppBarColors(),
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
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (resultTab == TrailResultTab.FULL_RUNS) {
                item {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        FullRunsOverview(
                            passes = passes,
                            efforts = trailEfforts,
                            wholeTrailSection = wholeTrailSection,
                            splitSections = splitSections,
                            imperial = imperial,
                            onCompare = { showingComparison = true },
                        )
                    }
                }
            } else {
                item {
                    TrailMap(
                        points = savedTrailMap.fullRoute,
                        highlightedPoints = savedTrailMap.highlightedRoute,
                        stopPoints = pauseZonePoints,
                        splitRoutes = splitRoutes,
                        pauseZoneRoutes = pauseZoneRoutes,
                        selectedSplitIndex = safeSelectedSplitIndex,
                        jumps = emptyList(), apiKey = mapApiKey, mapStyle = mapStyle,
                        modifier = Modifier.fillMaxWidth().height(230.dp), fitRoute = true,
                    )
                }
                if (splitSections.isNotEmpty()) item {
                    SplitRouteScrubber(
                        trailStartMeters = trail.startMeters,
                        trailEndMeters = trail.endMeters,
                        splits = splitSections,
                        zones = pauseZones,
                        selectedIndex = safeSelectedSplitIndex,
                        onSelect = { selectedSplitIndex = it },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (activePauseZones.isEmpty()) {
                            Text("Ride again to discover natural pause areas.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showingSplitEditor = true }) {
                            Icon(Icons.Default.Tune, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Edit splits")
                        }
                    }
                }
                if (!splitComparisonReady) item {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        ComparisonUnavailableCard(
                            matchedRuns = passes.size,
                            hasInterruptedData = trailEfforts.any { !it.valid },
                            onAdjustBoundaries = { wholeTrailSection?.let(::beginEditing) },
                        )
                    }
                }
                selectedSplit?.let { split ->
                    item(key = split.id) {
                        val features = sections.filter {
                            it.kind != com.example.flightlog.domain.SectionKind.SPLIT &&
                                it.kind != com.example.flightlog.domain.SectionKind.WHOLE_TRAIL &&
                                it.startMeters >= split.startMeters && it.endMeters <= split.endMeters
                        }
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            SplitOverviewCard(
                                section = split,
                                passes = passes,
                                allEfforts = trailEfforts,
                                features = features,
                                imperial = imperial,
                                noEarlierStops = noEarlierStops,
                                onNoEarlierStops = { noEarlierStops = it },
                                onEdit = {
                                    splitRenameText = split.name
                                    renamingSplit = split
                                },
                            )
                        }
                    }
                }
                if (splitComparisonReady) item {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        Button(onClick = { showingComparison = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Filled.CompareArrows, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Compare two rides")
                        }
                    }
                }
                if (splitSections.isNotEmpty()) item {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        IdealRunCard(splitSections, passes, trailEfforts, wholeTrailSection)
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

internal fun invalidEffortMessage(efforts: List<SectionEffortEntity>): String {
    val reasons = efforts.mapNotNullTo(hashSetOf()) { it.invalidReason }
    return when {
        reasons == setOf(EffortInvalidReason.STOP) -> "A stop occurred inside this split, so that pass was excluded."
        reasons == setOf(EffortInvalidReason.GPS_GAP) -> "GPS samples were missing inside this split, so no reliable speed is available."
        EffortInvalidReason.STOP in reasons && EffortInvalidReason.GPS_GAP in reasons ->
            "Some passes stopped here and others lost GPS, so no reliable comparison is available."
        else -> "No uninterrupted effort is available for this split yet."
    }
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
    savedRideBytes: Long = 0,
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
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Saved ride history", fontWeight = FontWeight.Bold)
                                Text("Estimated on-device data", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "About ${formatDataSize(savedRideBytes)}",
                                color = TrailCyan,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    val totalMb = telemetryBytes / (1_024.0 * 1_024.0)
                    val motionMb = motionBytes / (1_024.0 * 1_024.0)
                    val profileMb = estimatedProfileBytes / (1_024.0 * 1_024.0)
                    Text(String.format(Locale.US, "Compressed telemetry %.1f MB • permanent profiles %.1f MB • raw motion %.1f MB", totalMb, profileMb, motionMb))
                    Text(
                        nextMotionExpiry?.let { "The next raw-motion cleanup is ${formatDate(it)}. GPS, spatial profiles, and per-jump sensor snippets are retained." }
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
private fun Metric(
    label: String,
    value: String,
    prominent: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (prominent) FontWeight.Black else FontWeight.Normal,
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (prominent) FontWeight.Black else FontWeight.Bold,
            fontSize = if (prominent) 19.sp else 17.sp,
        )
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

internal fun averageMovingSpeedMps(distanceMeters: Double, movingTimeMillis: Long): Double? =
    if (movingTimeMillis <= 0L || !distanceMeters.isFinite() || distanceMeters < 0.0) null
    else distanceMeters / (movingTimeMillis / 1_000.0)

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

internal fun formatDataSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0)
    val kibibyte = 1_024.0
    val mebibyte = kibibyte * 1_024.0
    val gibibyte = mebibyte * 1_024.0
    return when {
        safeBytes < kibibyte -> "$safeBytes B"
        safeBytes < mebibyte -> String.format(Locale.US, "%.1f KB", safeBytes / kibibyte)
        safeBytes < gibibyte -> String.format(Locale.US, "%.1f MB", safeBytes / mebibyte)
        else -> String.format(Locale.US, "%.2f GB", safeBytes / gibibyte)
    }
}

private fun formatDate(timestamp: Long): String = DateTimeFormatter.ofPattern("EEE, MMM d • h:mm a")
    .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
