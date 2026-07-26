@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.flightlog.bikepark

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.flightlog.FlightLogApplication
import com.example.flightlog.data.BikeParkEntity
import com.example.flightlog.data.ParkZoneEntity
import com.example.flightlog.maps.MapApiKeyStore
import com.example.flightlog.maps.MapProvider
import com.example.flightlog.maps.MapStyleStore
import com.example.flightlog.ui.theme.FlightLogTheme
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

class ParkEditorActivity : ComponentActivity() {
    private val repository get() = (application as FlightLogApplication).repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlightLogTheme {
                ParkEditorScreen(
                    loadParks = { repository.bikeParks },
                    loadPark = repository::bikePark,
                    savePark = repository::saveBikePark,
                    deletePark = repository::deleteBikePark,
                    onClose = ::finish,
                )
            }
        }
    }
}

private enum class ParkMapMode { MOVE, DRAW, ERASE }

@Composable
private fun ParkEditorScreen(
    loadParks: () -> kotlinx.coroutines.flow.Flow<List<BikeParkEntity>>,
    loadPark: suspend (Long) -> Pair<BikeParkEntity, List<ParkZoneEntity>>?,
    savePark: suspend (Long?, String, List<ParkZoneDraft>) -> Long,
    deletePark: suspend (Long) -> Unit,
    onClose: () -> Unit,
) {
    val parks by remember { loadParks() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showingEditor by rememberSaveable { mutableStateOf(false) }
    var parkId by rememberSaveable { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var zones by remember { mutableStateOf<List<ParkZoneDraft>>(emptyList()) }
    var drawing by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var zoneType by rememberSaveable { mutableStateOf(ParkZoneType.SUMMIT) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recordingGps by remember { mutableStateOf(false) }
    var recordedPath by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var mapMode by rememberSaveable { mutableStateOf(ParkMapMode.MOVE) }
    var editingZone by remember { mutableStateOf<ParkZoneDraft?>(null) }
    var locationCenter by remember { mutableStateOf<GeoPoint?>(null) }
    var locationCameraKey by rememberSaveable { mutableIntStateOf(0) }
    var locationRequestKey by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var startGpsAfterPermission by remember { mutableStateOf(false) }
    var gpsPermissionKey by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val locationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val callback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val accepted = result.locations.filter { it.accuracy <= 25f }
                    .map { GeoPoint(it.latitude, it.longitude) }
                if (accepted.isNotEmpty()) recordedPath = recordedPath + accepted
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { locationClient.removeLocationUpdates(callback) }
    }
    fun centerOnCurrentLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            error = "Precise location permission is needed to center the map"
            return
        }
        val cancellation = CancellationTokenSource()
        locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    locationCenter = GeoPoint(location.latitude, location.longitude)
                    locationCameraKey++
                    error = null
                } else {
                    locationClient.lastLocation.addOnSuccessListener { last ->
                        if (last != null) {
                            locationCenter = GeoPoint(last.latitude, last.longitude)
                            locationCameraKey++
                        }
                        else error = "Current location is unavailable; you can still move the map manually"
                    }
                }
            }
            .addOnFailureListener { error = "Current location is unavailable; you can still move the map manually" }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (startGpsAfterPermission) {
                startGpsAfterPermission = false
                gpsPermissionKey++
            } else centerOnCurrentLocation()
        }
        else error = "Precise location permission is required for GPS features"
    }

    fun stopGpsRecording() {
        locationClient.removeLocationUpdates(callback)
        recordingGps = false
        val polygon = ParkGeometry.bufferPath(recordedPath, 20.0)
        if (polygon.size >= 3) {
            zones = zones + ParkZoneDraft(
                name = "Exclusion ${zones.count { it.type == ParkZoneType.EXCLUSION } + 1}",
                type = ParkZoneType.EXCLUSION,
                vertices = polygon,
            )
            recordedPath = emptyList()
        } else error = "Move farther before finishing GPS capture"
    }

    fun startGpsRecording() {
        zoneType = ParkZoneType.EXCLUSION
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            startGpsAfterPermission = true
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        recordedPath = emptyList()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
            .setMinUpdateDistanceMeters(2f)
            .build()
        try {
            locationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            recordingGps = true
        } catch (_: SecurityException) {
            error = "Precise location permission is required for GPS capture"
        }
    }

    LaunchedEffect(gpsPermissionKey) {
        if (gpsPermissionKey > 0 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            startGpsRecording()
        }
    }

    LaunchedEffect(showingEditor, parkId, locationRequestKey) {
        if (!showingEditor || parkId != null) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            centerOnCurrentLocation()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    LaunchedEffect(parkId) {
        val loaded = parkId?.let { loadPark(it) } ?: return@LaunchedEffect
        name = loaded.first.name
        zones = loaded.second.map {
            ParkZoneDraft(it.id, it.name, it.type, ParkGeometry.decode(it.encodedVertices), it.corridorWidthMeters)
        }
        drawing = emptyList()
        editingZone = null
        mapMode = ParkMapMode.MOVE
    }

    fun resetDraft() {
        drawing = emptyList()
        editingZone = null
        recordedPath = emptyList()
        mapMode = ParkMapMode.MOVE
        if (recordingGps) {
            locationClient.removeLocationUpdates(callback)
            recordingGps = false
        }
    }

    fun finishZone() {
        if (drawing.size < 3) {
            error = "Add at least three points"
            return
        }
        val original = editingZone
        val completed = (original ?: ParkZoneDraft(
            name = "${zoneType.name.lowercase().replaceFirstChar(Char::uppercase)} ${zones.count { it.type == zoneType } + 1}",
            type = zoneType,
            vertices = emptyList(),
        )).copy(type = zoneType, vertices = drawing)
        zones = if (original == null) zones + completed else zones.map { if (it === original) completed else it }
        resetDraft()
        error = null
    }

    if (!showingEditor) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Bike parks") },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") } },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        parkId = null
                        name = ""
                        zones = emptyList()
                        resetDraft()
                        locationCenter = null
                        locationRequestKey++
                        showingEditor = true
                    },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("New park") },
                )
            },
        ) { padding ->
            if (parks.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("No saved bike parks yet")
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(parks, key = { it.id }) { park ->
                        Card(onClick = {
                            parkId = park.id
                            locationCenter = null
                            resetDraft()
                            showingEditor = true
                        }) {
                            ListItem(
                                headlineContent = { Text(park.name) },
                                supportingContent = { Text("Tap to view and edit") },
                                trailingContent = { Icon(Icons.Default.Edit, "Edit ${park.name}") },
                            )
                        }
                    }
                }
            }
        }
        return
    }

    val mapContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            ParkZoneMap(
                zones = zones,
                drawing = drawing,
                recordedPath = recordedPath,
                mode = mapMode,
                locationCenter = locationCenter,
                locationCameraKey = locationCameraKey,
                focusKey = parkId ?: -locationRequestKey.toLong() - 1,
                onDraw = { drawing = drawing + it },
                onErase = { index -> drawing = drawing.filterIndexed { candidate, _ -> candidate != index } },
            )
            Column(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FloatingActionButton(onClick = { fullScreen = !fullScreen }) {
                    Icon(if (fullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Toggle full screen")
                }
                if (drawing.isNotEmpty()) {
                    SmallFloatingActionButton(onClick = { drawing = drawing.dropLast(1) }) {
                        Icon(Icons.Default.Undo, "Undo point")
                    }
                }
                SmallFloatingActionButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        centerOnCurrentLocation()
                    } else {
                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }) {
                    Icon(Icons.Default.MyLocation, "Center map on current location")
                }
            }
            if (fullScreen) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    tonalElevation = 4.dp,
                ) {
                    ZoneDrawingControls(
                        zoneType, { zoneType = it }, drawing.size, recordingGps, mapMode, { mapMode = it },
                        editingZone != null, ::finishZone, ::resetDraft,
                        onGps = { if (recordingGps) stopGpsRecording() else startGpsRecording() },
                    )
                }
            }
        }
    }

    if (fullScreen) {
        mapContent()
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bike park editor") },
                navigationIcon = { IconButton(onClick = { resetDraft(); showingEditor = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Bike parks") } },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { savePark(parkId, name, zones) }
                                .onSuccess { parkId = it; error = null; resetDraft(); showingEditor = false }
                                .onFailure { error = it.message ?: "Could not save park" }
                        }
                    }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxWidth().weight(1f)) { mapContent() }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 330.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    OutlinedTextField(name, { name = it }, label = { Text("Park name") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    ZoneDrawingControls(
                        zoneType, { zoneType = it }, drawing.size, recordingGps, mapMode, { mapMode = it },
                        editingZone != null, ::finishZone, ::resetDraft,
                        onGps = { if (recordingGps) stopGpsRecording() else startGpsRecording() },
                    )
                }
                items(zones, key = { "${it.type}-${it.id}-${it.name}" }) { zone ->
                    Column {
                        ListItem(
                            headlineContent = { Text(zone.name) },
                            supportingContent = { Text("${zone.type.name.lowercase()} · ${zone.vertices.size} points") },
                            leadingContent = {
                                IconButton(onClick = {
                                    drawing = zone.vertices
                                    zoneType = zone.type
                                    editingZone = zone
                                    mapMode = ParkMapMode.DRAW
                                }) { Icon(Icons.Default.Edit, "Edit zone") }
                            },
                            trailingContent = {
                                IconButton(onClick = { zones = zones - zone }) { Icon(Icons.Default.Delete, "Delete zone") }
                            },
                        )
                        if (zone.type == ParkZoneType.EXCLUSION) {
                            Text(
                                "Corridor width: ${zone.corridorWidthMeters.toInt()} m",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Slider(
                                value = zone.corridorWidthMeters.toFloat(),
                                onValueChange = { width ->
                                    zones = zones.map {
                                        if (it === zone) it.copy(
                                            vertices = ParkGeometry.resizeCorridor(it.vertices, width.toDouble()),
                                            corridorWidthMeters = width.toDouble(),
                                        ) else it
                                    }
                                },
                                valueRange = 2f..100f,
                            )
                        }
                    }
                }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                if (parkId != null) {
                    item {
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete park", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete bike park?") },
            text = { Text("This permanently deletes the park and all of its zones.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val deleting = parkId ?: return@TextButton
                    scope.launch {
                        deletePark(deleting)
                        parkId = null
                        name = ""
                        zones = emptyList()
                        resetDraft()
                        showingEditor = false
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ZoneDrawingControls(
    type: ParkZoneType,
    onType: (ParkZoneType) -> Unit,
    pointCount: Int,
    recordingGps: Boolean,
    mode: ParkMapMode,
    onMode: (ParkMapMode) -> Unit,
    editing: Boolean,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onGps: () -> Unit,
) {
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow {
            ParkZoneType.entries.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = type == value,
                    onClick = { onType(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, ParkZoneType.entries.size),
                ) { Text(value.name.lowercase().replaceFirstChar(Char::uppercase)) }
            }
        }
        SingleChoiceSegmentedButtonRow {
            ParkMapMode.entries.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = mode == value,
                    onClick = { onMode(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, ParkMapMode.entries.size),
                ) {
                    Icon(
                        when (value) {
                            ParkMapMode.MOVE -> Icons.Default.PanTool
                            ParkMapMode.DRAW -> Icons.Default.Draw
                            ParkMapMode.ERASE -> Icons.Default.Delete
                        },
                        null,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(value.name.lowercase().replaceFirstChar(Char::uppercase))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFinish, enabled = pointCount >= 3) { Text("Finish zone ($pointCount)") }
            if (editing || pointCount > 0) TextButton(onClick = onCancel) { Text("Cancel") }
            OutlinedButton(onClick = onGps) {
                Icon(if (recordingGps) Icons.Default.Stop else Icons.Default.GpsFixed, null)
                Spacer(Modifier.width(6.dp))
                Text(if (recordingGps) "Finish GPS" else "Record exclusion")
            }
        }
    }
}

@Composable
private fun ParkZoneMap(
    zones: List<ParkZoneDraft>,
    drawing: List<GeoPoint>,
    recordedPath: List<GeoPoint>,
    mode: ParkMapMode,
    locationCenter: GeoPoint?,
    locationCameraKey: Int,
    focusKey: Long,
    onDraw: (GeoPoint) -> Unit,
    onErase: (Int) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val provider = remember {
        MapProvider.configured(MapApiKeyStore.effectiveKey(context), MapStyleStore.read(context))
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val view = remember { MapView(context) }
    val currentMode = rememberUpdatedState(mode)
    val currentDrawing = rememberUpdatedState(drawing)
    val drawListener = rememberUpdatedState(onDraw)
    val eraseListener = rememberUpdatedState(onErase)
    val eraseRadius = with(LocalDensity.current) { 32.dp.toPx() }
    var handledLocationCameraKey by remember { mutableIntStateOf(-1) }

    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                Lifecycle.Event.ON_DESTROY -> view.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view.onDestroy() }
    }
    LaunchedEffect(Unit) {
        view.getMapAsync { ready ->
            map = ready
            ready.setStyle(Style.Builder().fromJson(provider.styleJson())) { style ->
                ParkZoneType.entries.forEach { type -> addZoneLayers(style, type) }
                style.addSource(GeoJsonSource("park-drawing"))
                style.addLayer(LineLayer("park-drawing-line", "park-drawing").withProperties(lineColor("#ffffff"), lineWidth(4f)))
                ready.addOnMapClickListener { coordinate ->
                    when (currentMode.value) {
                        ParkMapMode.MOVE -> false
                        ParkMapMode.DRAW -> {
                            drawListener.value(GeoPoint(coordinate.latitude, coordinate.longitude))
                            true
                        }
                        ParkMapMode.ERASE -> {
                            val tapped = ready.projection.toScreenLocation(coordinate)
                            val projected = currentDrawing.value.map { point ->
                                val screen = ready.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
                                screen.x.toDouble() to screen.y.toDouble()
                            }
                            nearestPointIndex(projected, tapped.x.toDouble(), tapped.y.toDouble(), eraseRadius.toDouble())
                                ?.let(eraseListener.value)
                            true
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(map, zones, drawing, recordedPath) {
        val ready = map ?: return@LaunchedEffect
        val style = ready.style ?: return@LaunchedEffect
        ParkZoneType.entries.forEach { type ->
            val features = zones.filter { it.type == type }.mapNotNull { polygonFeature(it.vertices) }
            style.getSourceAs<GeoJsonSource>("park-${type.name.lowercase()}")?.setGeoJson(FeatureCollection.fromFeatures(features))
        }
        val line = drawing.ifEmpty { recordedPath }
        style.getSourceAs<GeoJsonSource>("park-drawing")?.setGeoJson(
            if (line.isEmpty()) FeatureCollection.fromFeatures(emptyArray())
            else FeatureCollection.fromFeature(Feature.fromGeometry(
                org.maplibre.geojson.LineString.fromLngLats(line.map { Point.fromLngLat(it.longitude, it.latitude) }),
            )),
        )
    }
    LaunchedEffect(map, focusKey, locationCameraKey, zones) {
        val ready = map ?: return@LaunchedEffect
        val all = zones.flatMap { it.vertices }
        if (locationCenter != null && locationCameraKey != handledLocationCameraKey) {
            handledLocationCameraKey = locationCameraKey
            ready.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(locationCenter.latitude, locationCenter.longitude))
                    .zoom(16.0)
                    .build(),
            ), 350)
        } else if (all.isNotEmpty()) {
            val bounds = LatLngBounds.Builder().includes(all.map { LatLng(it.latitude, it.longitude) }).build()
            ready.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80), 250)
        }
    }
    AndroidView({ view }, Modifier.fillMaxSize())
}

private fun addZoneLayers(style: Style, type: ParkZoneType) {
    val id = "park-${type.name.lowercase()}"
    val color = when (type) {
        ParkZoneType.SUMMIT -> "#37d67a"
        ParkZoneType.BOTTOM -> "#36a3ff"
        ParkZoneType.EXCLUSION -> "#ff7043"
    }
    style.addSource(GeoJsonSource(id))
    style.addLayer(FillLayer("$id-fill", id).withProperties(fillColor(color), fillOpacity(0.28f)))
    style.addLayer(LineLayer("$id-line", id).withProperties(lineColor(color), lineWidth(3f)))
}

private fun polygonFeature(vertices: List<GeoPoint>): Feature? {
    if (vertices.size < 3) return null
    val ring = (vertices + vertices.first()).map { Point.fromLngLat(it.longitude, it.latitude) }
    return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
}
