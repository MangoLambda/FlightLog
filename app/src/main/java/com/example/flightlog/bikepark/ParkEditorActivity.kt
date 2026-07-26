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
    var parkId by rememberSaveable { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var zones by remember { mutableStateOf<List<ParkZoneDraft>>(emptyList()) }
    var drawing by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var zoneType by rememberSaveable { mutableStateOf(ParkZoneType.SUMMIT) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recordingGps by remember { mutableStateOf(false) }
    var recordedPath by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
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
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) error = "Precise location permission is required for GPS capture"
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

    LaunchedEffect(parkId) {
        val loaded = parkId?.let { loadPark(it) } ?: return@LaunchedEffect
        name = loaded.first.name
        zones = loaded.second.map {
            ParkZoneDraft(it.id, it.name, it.type, ParkGeometry.decode(it.encodedVertices), it.corridorWidthMeters)
        }
        drawing = emptyList()
    }

    val mapContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            ParkZoneMap(zones, drawing, recordedPath) { drawing = drawing + it }
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
            }
            if (fullScreen) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    tonalElevation = 4.dp,
                ) {
                    ZoneDrawingControls(
                        zoneType, { zoneType = it }, drawing.size, recordingGps,
                        onFinish = {
                            if (drawing.size < 3) error = "Add at least three points"
                            else {
                                zones = zones + ParkZoneDraft(
                                    name = "${zoneType.name.lowercase().replaceFirstChar(Char::uppercase)} ${zones.count { it.type == zoneType } + 1}",
                                    type = zoneType,
                                    vertices = drawing,
                                )
                                drawing = emptyList()
                            }
                        },
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
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") } },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { savePark(parkId, name, zones) }
                                .onSuccess { parkId = it; error = null }
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
                if (parks.isNotEmpty()) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            parks.forEach { park ->
                                FilterChip(selected = parkId == park.id, onClick = { parkId = park.id }, label = { Text(park.name) })
                            }
                            AssistChip(onClick = { parkId = null; name = ""; zones = emptyList() }, label = { Text("New") })
                        }
                    }
                }
                item {
                    ZoneDrawingControls(
                        zoneType, { zoneType = it }, drawing.size, recordingGps,
                        onFinish = {
                            if (drawing.size < 3) error = "Add at least three points"
                            else {
                                zones = zones + ParkZoneDraft(
                                    name = "${zoneType.name.lowercase().replaceFirstChar(Char::uppercase)} ${zones.count { it.type == zoneType } + 1}",
                                    type = zoneType,
                                    vertices = drawing,
                                )
                                drawing = emptyList()
                            }
                        },
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
                                    zones = zones - zone
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
                        TextButton(onClick = {
                            val deleting = parkId ?: return@TextButton
                            scope.launch {
                                deletePark(deleting)
                                parkId = null
                                name = ""
                                zones = emptyList()
                            }
                        }) { Text("Delete park", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoneDrawingControls(
    type: ParkZoneType,
    onType: (ParkZoneType) -> Unit,
    pointCount: Int,
    recordingGps: Boolean,
    onFinish: () -> Unit,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFinish, enabled = pointCount >= 3) { Text("Finish zone ($pointCount)") }
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
    onMapClick: (GeoPoint) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val provider = remember {
        MapProvider.configured(MapApiKeyStore.effectiveKey(context), MapStyleStore.read(context))
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val view = remember { MapView(context) }
    val clickListener = rememberUpdatedState(onMapClick)

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
                    clickListener.value(GeoPoint(coordinate.latitude, coordinate.longitude))
                    true
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
        val all = zones.flatMap { it.vertices } + drawing + recordedPath
        if (all.isNotEmpty()) {
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
