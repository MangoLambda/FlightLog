package com.example.flightlog.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.maps.MapProvider
import com.example.flightlog.maps.MapStyle
import com.example.flightlog.maps.MapTileCache
import com.example.flightlog.ui.theme.Amber
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.tile.TileOperation
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE = "flightlog-route"
private const val COMPARISON_ROUTE_SOURCE = "flightlog-comparison-route"
private const val JUMP_SOURCE = "flightlog-jumps"
private const val RIDER_SOURCE = "flightlog-rider"
private const val RIDER_IMAGE = "flightlog-rider-arrow"
private const val RIDER_ZOOM = 14.5

@Composable
fun TrailMap(
    points: List<TrackPointEntity>,
    jumps: List<JumpEventEntity>,
    apiKey: String,
    mapStyle: MapStyle,
    modifier: Modifier = Modifier,
    onConfigureMap: (() -> Unit)? = null,
    showRider: Boolean = false,
    fitRoute: Boolean = false,
    comparisonPoints: List<TrackPointEntity> = emptyList(),
) {
    val context = LocalContext.current
    val routePaddingPixels = with(LocalDensity.current) { 96.dp.roundToPx() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val provider = remember(apiKey, mapStyle) { MapProvider.configured(apiKey, mapStyle) }
    val networkAvailable = rememberValidatedNetworkAvailable()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var tileLoadFailed by remember { mutableStateOf(false) }
    var followingRider by remember { mutableStateOf(showRider) }
    val mapView = remember { mutableStateOf<MapView?>(null) }
    val tileActionListener = remember { mutableStateOf<MapView.OnTileActionListener?>(null) }

    LaunchedEffect(showRider) {
        if (showRider) {
            followingRider = true
            updateMap(map, points, jumps, showRider = true, fitRoute = false, routePaddingPixels = routePaddingPixels, moveCamera = true, comparisonPoints = comparisonPoints)
        }
    }

    LaunchedEffect(provider, networkAvailable, map) {
        tileLoadFailed = false
        val readyMap = map ?: return@LaunchedEffect
        readyMap.uiSettings.isAttributionEnabled = provider !is MapProvider.OfflineCanvas
        readyMap.setStyle(Style.Builder().fromJson(provider.styleJson())) { style ->
            addRideLayers(style, context)
            updateMap(readyMap, points, jumps, showRider, fitRoute, routePaddingPixels, moveCamera = showRider || fitRoute || points.size < 2, comparisonPoints = comparisonPoints)
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
                MapView(context, options).also { view ->
                    mapView.value = view
                    val listener = MapView.OnTileActionListener { operation, _, _, _, _, _, sourceId ->
                        if (operation == TileOperation.LoadFromNetwork && sourceId == "thunderforest") {
                            MapTileCache.recordDownloadedTile(context)
                        }
                    }
                    tileActionListener.value = listener
                    view.addOnTileActionListener(listener)
                    view.onCreate(null)
                    view.addOnDidFailLoadingMapListener { tileLoadFailed = true }
                    view.getMapAsync { readyMap ->
                        map = readyMap
                        readyMap.uiSettings.isCompassEnabled = false
                        readyMap.uiSettings.isLogoEnabled = false
                        readyMap.addOnCameraMoveStartedListener { reason ->
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                followingRider = false
                            }
                        }
                    }
                }
            },
            update = { updateMap(map, points, jumps, showRider, fitRoute, routePaddingPixels, moveCamera = followingRider, comparisonPoints = comparisonPoints) },
        )

        if (showRider && points.isNotEmpty()) {
            FloatingActionButton(
                onClick = {
                    followingRider = true
                    updateMap(map, points, jumps, showRider = true, fitRoute = false, routePaddingPixels = routePaddingPixels, moveCamera = true, comparisonPoints = comparisonPoints)
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.MyLocation, "Recenter map on your location")
            }
        }

        val warning = when {
            provider is MapProvider.OfflineCanvas -> "Map unavailable — add a Thunderforest API key"
            !networkAvailable -> "Offline — showing cached map tiles where available"
            tileLoadFailed -> "Map tiles unavailable — route recording continues"
            else -> null
        }
        warning?.let {
            MapWarning(
                message = it,
                onClick = onConfigureMap.takeIf { provider is MapProvider.OfflineCanvas },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.value?.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.value?.onPause()
                Lifecycle.Event.ON_STOP -> mapView.value?.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        when {
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> {
                mapView.value?.onStart()
                mapView.value?.onResume()
            }
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> mapView.value?.onStart()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.value?.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.value?.onStop()
            tileActionListener.value?.let { mapView.value?.removeOnTileActionListener(it) }
            mapView.value?.onDestroy()
        }
    }
}

@Composable
private fun MapWarning(message: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .widthIn(max = 380.dp)
            .then(
                if (onClick != null) Modifier.clickable(
                    onClickLabel = "Open map provider settings",
                    onClick = onClick,
                ) else Modifier,
            ),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        shadowElevation = 6.dp,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CloudOff, null, tint = Amber, modifier = Modifier.size(20.dp))
            Text(
                message,
                modifier = Modifier.padding(start = 9.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun rememberValidatedNetworkAvailable(): Boolean {
    val context = LocalContext.current
    val connectivity = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    fun isAvailable(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    var available by remember { mutableStateOf(isAvailable()) }
    DisposableEffect(connectivity) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update()

            private fun update() {
                context.mainExecutor.execute { available = isAvailable() }
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    return available
}

private fun addRideLayers(style: Style, context: Context) {
    style.addSource(GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(LineLayer("flightlog-route-line", ROUTE_SOURCE).withProperties(
        lineColor("#42D9E8"), lineWidth(5f),
    ))
    style.addSource(GeoJsonSource(COMPARISON_ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(LineLayer("flightlog-comparison-route-line", COMPARISON_ROUTE_SOURCE).withProperties(
        lineColor("#FFB84D"), lineWidth(4f),
    ))
    style.addSource(GeoJsonSource(JUMP_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(CircleLayer("flightlog-jump-points", JUMP_SOURCE).withProperties(
        circleColor("#FFB84D"), circleRadius(6f),
    ))
    style.addImage(RIDER_IMAGE, riderArrowBitmap(context))
    style.addSource(GeoJsonSource(RIDER_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(SymbolLayer("flightlog-rider-position", RIDER_SOURCE).withProperties(
        iconImage(RIDER_IMAGE),
        iconSize(0.8f),
        iconAllowOverlap(true),
        iconRotationAlignment("map"),
        iconRotate(Expression.get("bearing")),
    ))
}

private fun updateMap(
    map: MapLibreMap?,
    points: List<TrackPointEntity>,
    jumps: List<JumpEventEntity>,
    showRider: Boolean,
    fitRoute: Boolean,
    routePaddingPixels: Int,
    moveCamera: Boolean,
    comparisonPoints: List<TrackPointEntity> = emptyList(),
) {
    val readyMap = map ?: return
    readyMap.getStyle { style ->
        val routeFeatures = if (points.size >= 2) {
            arrayOf(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })))
        } else emptyArray()
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(routeFeatures))
        val comparisonFeatures = if (comparisonPoints.size >= 2) {
            arrayOf(Feature.fromGeometry(LineString.fromLngLats(comparisonPoints.map { Point.fromLngLat(it.longitude, it.latitude) })))
        } else emptyArray()
        style.getSourceAs<GeoJsonSource>(COMPARISON_ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(comparisonFeatures))
        val jumpFeatures = jumps.mapNotNull { jump ->
            val lat = jump.latitude ?: return@mapNotNull null
            val lon = jump.longitude ?: return@mapNotNull null
            Feature.fromGeometry(Point.fromLngLat(lon, lat))
        }
        style.getSourceAs<GeoJsonSource>(JUMP_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(jumpFeatures))
        val rider = points.lastOrNull().takeIf { showRider }
        val riderFeatures = rider?.let {
            arrayOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply {
                addNumberProperty("bearing", it.bearingDegrees ?: 0f)
            })
        } ?: emptyArray()
        style.getSourceAs<GeoJsonSource>(RIDER_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(riderFeatures))
        if (fitRoute && points.size >= 2) {
            val bounds = LatLngBounds.Builder()
                .includes(points.map { LatLng(it.latitude, it.longitude) })
                .build()
            readyMap.getCameraForLatLngBounds(
                bounds,
                intArrayOf(routePaddingPixels, routePaddingPixels, routePaddingPixels, routePaddingPixels),
            )?.let { readyMap.cameraPosition = it }
        } else if ((moveCamera || points.size == 2) && points.isNotEmpty()) {
            val last = points.last()
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(LatLng(last.latitude, last.longitude))
                .zoom(if (showRider) RIDER_ZOOM else 15.0)
                .build()
        }
    }
}

private fun riderArrowBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (42 * density).toInt()
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val path = Path().apply {
            moveTo(center, size * 0.08f)
            lineTo(size * 0.82f, size * 0.86f)
            lineTo(center, size * 0.68f)
            lineTo(size * 0.18f, size * 0.86f)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5 * density
            strokeJoin = Paint.Join.ROUND
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 150, 243)
            style = Paint.Style.FILL
        })
    }
}
