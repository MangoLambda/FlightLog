package com.example.flightlog.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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
import androidx.compose.runtime.rememberUpdatedState
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
import org.maplibre.android.camera.CameraUpdateFactory
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
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.tile.TileOperation
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.sin

private const val ROUTE_SOURCE = "flightlog-route"
private const val COMPARISON_ROUTE_SOURCE = "flightlog-comparison-route"
private const val HIGHLIGHTED_ROUTE_SOURCE = "flightlog-highlighted-route"
private const val BOUNDARY_START_SOURCE = "flightlog-boundary-start"
private const val BOUNDARY_END_SOURCE = "flightlog-boundary-end"
private const val BOUNDARY_START_IMAGE = "flightlog-boundary-start-image"
private const val BOUNDARY_END_IMAGE = "flightlog-boundary-end-image"
private const val JUMP_SOURCE = "flightlog-jumps"
private const val JUMP_SELECTED_LAYER = "flightlog-selected-jump"
private const val JUMP_CIRCLE_LAYER = "flightlog-jump-points"
private const val JUMP_LABEL_LAYER = "flightlog-jump-labels"
private const val JUMP_TAKEOFF_SOURCE = "flightlog-jump-takeoffs"
private const val JUMP_LANDING_SOURCE = "flightlog-jump-landings"
private const val JUMP_TAKEOFF_IMAGE = "flightlog-jump-takeoff-image"
private const val JUMP_LANDING_IMAGE = "flightlog-jump-landing-image"
private const val JUMP_TAKEOFF_LAYER = "flightlog-jump-takeoff-points"
private const val JUMP_LANDING_LAYER = "flightlog-jump-landing-points"
private const val STOP_SOURCE = "flightlog-stops"
private const val STOP_IMAGE = "flightlog-stop-image"
private const val SPLIT_ROUTE_SOURCE = "flightlog-split-routes"
private const val PAUSE_ZONE_ROUTE_SOURCE = "flightlog-pause-zone-routes"
private const val SPLIT_LABEL_SOURCE = "flightlog-split-labels"
private const val RIDER_SOURCE = "flightlog-rider"
private const val RIDER_IMAGE = "flightlog-rider-arrow"
private const val RIDER_ZOOM = 14.5
private const val JUMP_ZOOM = 17.0

private enum class BoundaryDragTarget { START, END }

private class BoundaryDragState {
    var target: BoundaryDragTarget? = null
    var lastPointTimestamp: Long? = null
}

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
    highlightedPoints: List<TrackPointEntity> = emptyList(),
    stopPoints: List<TrackPointEntity> = emptyList(),
    splitRoutes: List<List<TrackPointEntity>> = emptyList(),
    pauseZoneRoutes: List<List<TrackPointEntity>> = emptyList(),
    selectedSplitIndex: Int = 0,
    boundaryStart: TrackPointEntity? = null,
    boundaryEnd: TrackPointEntity? = null,
    onBoundaryStartChange: ((TrackPointEntity) -> Unit)? = null,
    onBoundaryEndChange: ((TrackPointEntity) -> Unit)? = null,
    selectedJumpId: Long? = null,
    onJumpClick: ((Long) -> Unit)? = null,
) {
    val context = LocalContext.current
    val routePaddingPixels = with(LocalDensity.current) { 96.dp.roundToPx() }
    val boundaryTouchRadiusPixels = with(LocalDensity.current) { 32.dp.toPx() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val provider = remember(apiKey, mapStyle) { MapProvider.configured(apiKey, mapStyle) }
    val networkAvailable = rememberValidatedNetworkAvailable()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var tileLoadFailed by remember { mutableStateOf(false) }
    var followingRider by remember { mutableStateOf(showRider) }
    val mapView = remember { mutableStateOf<MapView?>(null) }
    val tileActionListener = remember { mutableStateOf<MapView.OnTileActionListener?>(null) }
    val mapClickListener = remember { mutableStateOf<MapLibreMap.OnMapClickListener?>(null) }
    val boundaryDragState = remember { BoundaryDragState() }
    val currentPoints = rememberUpdatedState(points)
    val currentBoundaryStart = rememberUpdatedState(boundaryStart)
    val currentBoundaryEnd = rememberUpdatedState(boundaryEnd)
    val currentOnBoundaryStartChange = rememberUpdatedState(onBoundaryStartChange)
    val currentOnBoundaryEndChange = rememberUpdatedState(onBoundaryEndChange)
    val currentOnJumpClick = rememberUpdatedState(onJumpClick)
    val routeFitKey = remember(points) {
        Triple(points.size, points.firstOrNull()?.recordedAt, points.lastOrNull()?.recordedAt)
    }

    LaunchedEffect(showRider) {
        if (showRider) {
            followingRider = true
            updateMap(
                map, points, jumps, showRider = true, fitRoute = false,
                routePaddingPixels = routePaddingPixels, moveCamera = true,
                comparisonPoints = comparisonPoints, highlightedPoints = highlightedPoints,
                stopPoints = stopPoints,
                splitRoutes = splitRoutes, pauseZoneRoutes = pauseZoneRoutes, selectedSplitIndex = selectedSplitIndex,
                boundaryStart = boundaryStart, boundaryEnd = boundaryEnd,
                selectedJumpId = selectedJumpId,
            )
        }
    }

    LaunchedEffect(provider, networkAvailable, map) {
        tileLoadFailed = false
        val readyMap = map ?: return@LaunchedEffect
        readyMap.uiSettings.isAttributionEnabled = provider !is MapProvider.OfflineCanvas
        readyMap.setStyle(Style.Builder().fromJson(provider.styleJson())) { style ->
            addRideLayers(style, context)
            updateMap(
                readyMap, points, jumps, showRider, fitRoute, routePaddingPixels,
                moveCamera = showRider || fitRoute || points.size < 2,
                comparisonPoints = comparisonPoints, highlightedPoints = highlightedPoints,
                stopPoints = stopPoints,
                splitRoutes = splitRoutes, pauseZoneRoutes = pauseZoneRoutes, selectedSplitIndex = selectedSplitIndex,
                boundaryStart = boundaryStart, boundaryEnd = boundaryEnd,
                selectedJumpId = selectedJumpId,
            )
        }
    }

    LaunchedEffect(map, fitRoute, routeFitKey) {
        if (fitRoute && points.size >= 2) {
            updateMap(
                map, points, jumps, showRider, fitRoute = true,
                routePaddingPixels = routePaddingPixels, moveCamera = false,
                comparisonPoints = comparisonPoints, highlightedPoints = highlightedPoints,
                stopPoints = stopPoints,
                splitRoutes = splitRoutes, pauseZoneRoutes = pauseZoneRoutes, selectedSplitIndex = selectedSplitIndex,
                boundaryStart = boundaryStart, boundaryEnd = boundaryEnd,
                selectedJumpId = selectedJumpId,
            )
        }
    }

    LaunchedEffect(map, selectedJumpId, jumps, points) {
        val selected = jumps.firstOrNull { it.id == selectedJumpId }
        val coordinate = selected?.let { jumpMapCoordinates(it, points).center } ?: return@LaunchedEffect
        val readyMap = map ?: return@LaunchedEffect
        val camera = CameraPosition.Builder()
            .target(LatLng(coordinate.latitude, coordinate.longitude))
            .zoom(maxOf(readyMap.cameraPosition.zoom, JUMP_ZOOM))
            .build()
        readyMap.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 350)
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val options = MapLibreMapOptions.createFromAttributes(context).textureMode(true)
                object : MapView(context, options) {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        // AndroidView does not automatically negotiate nested scrolling with
                        // Compose. Do this before MapLibre dispatches the event to one of its
                        // child views; onTouchEvent is not guaranteed to see every gesture.
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL,
                            -> parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        return super.dispatchTouchEvent(event)
                    }

                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        val handled = handleBoundaryDrag(
                            view = this,
                            event = event,
                            map = map,
                            points = currentPoints.value,
                            boundaryStart = currentBoundaryStart.value,
                            boundaryEnd = currentBoundaryEnd.value,
                            touchRadiusPixels = boundaryTouchRadiusPixels,
                            state = boundaryDragState,
                            onStartChange = currentOnBoundaryStartChange.value,
                            onEndChange = currentOnBoundaryEndChange.value,
                        )
                        return if (handled) true else super.onTouchEvent(event)
                    }
                }.also { view ->
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
                        MapLibreMap.OnMapClickListener { coordinate ->
                            val feature = readyMap.queryRenderedFeatures(
                                readyMap.projection.toScreenLocation(coordinate),
                                JUMP_SELECTED_LAYER,
                                JUMP_CIRCLE_LAYER,
                                JUMP_LABEL_LAYER,
                                JUMP_TAKEOFF_LAYER,
                                JUMP_LANDING_LAYER,
                            ).firstOrNull()
                            val jumpId = feature?.getNumberProperty("jumpId")?.toLong()
                            if (jumpId == null) false else {
                                currentOnJumpClick.value?.invoke(jumpId)
                                true
                            }
                        }.also {
                            mapClickListener.value = it
                            readyMap.addOnMapClickListener(it)
                        }
                    }
                }
            },
            update = {
                updateMap(
                    map, points, jumps, showRider, fitRoute = false,
                    routePaddingPixels = routePaddingPixels,
                    moveCamera = followingRider, comparisonPoints = comparisonPoints,
                    highlightedPoints = highlightedPoints, stopPoints = stopPoints,
                    splitRoutes = splitRoutes, pauseZoneRoutes = pauseZoneRoutes, selectedSplitIndex = selectedSplitIndex,
                    boundaryStart = boundaryStart,
                    boundaryEnd = boundaryEnd,
                    selectedJumpId = selectedJumpId,
                )
            },
        )

        if (showRider && points.isNotEmpty()) {
            FloatingActionButton(
                onClick = {
                    followingRider = true
                    updateMap(
                        map, points, jumps, showRider = true, fitRoute = false,
                        routePaddingPixels = routePaddingPixels, moveCamera = true,
                        comparisonPoints = comparisonPoints, highlightedPoints = highlightedPoints,
                        stopPoints = stopPoints,
                        splitRoutes = splitRoutes, pauseZoneRoutes = pauseZoneRoutes, selectedSplitIndex = selectedSplitIndex,
                        boundaryStart = boundaryStart, boundaryEnd = boundaryEnd,
                        selectedJumpId = selectedJumpId,
                    )
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
            mapClickListener.value?.let { listener -> map?.removeOnMapClickListener(listener) }
            mapView.value?.onDestroy()
        }
    }
}

private fun handleBoundaryDrag(
    view: MapView,
    event: MotionEvent,
    map: MapLibreMap?,
    points: List<TrackPointEntity>,
    boundaryStart: TrackPointEntity?,
    boundaryEnd: TrackPointEntity?,
    touchRadiusPixels: Float,
    state: BoundaryDragState,
    onStartChange: ((TrackPointEntity) -> Unit)?,
    onEndChange: ((TrackPointEntity) -> Unit)?,
): Boolean {
    val readyMap = map ?: return false
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            val candidates = buildList {
                if (boundaryStart != null && onStartChange != null) add(BoundaryDragTarget.START to boundaryStart)
                if (boundaryEnd != null && onEndChange != null) add(BoundaryDragTarget.END to boundaryEnd)
            }
            val selected = candidates.minByOrNull { (_, point) ->
                val screenPoint = readyMap.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
                val deltaX = screenPoint.x - event.x
                val deltaY = screenPoint.y - event.y
                deltaX * deltaX + deltaY * deltaY
            } ?: return false
            val selectedScreenPoint = readyMap.projection.toScreenLocation(
                LatLng(selected.second.latitude, selected.second.longitude),
            )
            val deltaX = selectedScreenPoint.x - event.x
            val deltaY = selectedScreenPoint.y - event.y
            if (deltaX * deltaX + deltaY * deltaY > touchRadiusPixels * touchRadiusPixels) return false

            state.target = selected.first
            state.lastPointTimestamp = selected.second.recordedAt
            view.parent?.requestDisallowInterceptTouchEvent(true)
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return true
        }

        MotionEvent.ACTION_MOVE -> {
            val target = state.target ?: return false
            val location = readyMap.projection.fromScreenLocation(PointF(event.x, event.y))
            val nearest = nearestRoutePoint(points, location.latitude, location.longitude) ?: return true
            if (nearest.recordedAt != state.lastPointTimestamp) {
                state.lastPointTimestamp = nearest.recordedAt
                when (target) {
                    BoundaryDragTarget.START -> onStartChange?.invoke(nearest)
                    BoundaryDragTarget.END -> onEndChange?.invoke(nearest)
                }
            }
            return true
        }

        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            if (state.target == null) return false
            state.target = null
            state.lastPointTimestamp = null
            view.parent?.requestDisallowInterceptTouchEvent(false)
            return true
        }

        else -> return state.target != null
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
    style.addSource(GeoJsonSource(HIGHLIGHTED_ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(LineLayer("flightlog-highlighted-route-line", HIGHLIGHTED_ROUTE_SOURCE).withProperties(
        lineColor("#42D9E8"), lineWidth(7f),
    ))
    style.addImage(BOUNDARY_START_IMAGE, boundaryHandleBitmap(context, Color.rgb(183, 243, 74)))
    style.addImage(BOUNDARY_END_IMAGE, boundaryHandleBitmap(context, Color.rgb(255, 184, 77)))
    style.addSource(GeoJsonSource(BOUNDARY_START_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(SymbolLayer("flightlog-boundary-start-point", BOUNDARY_START_SOURCE).withProperties(
        iconImage(BOUNDARY_START_IMAGE), iconSize(.8f), iconAllowOverlap(true),
    ))
    style.addSource(GeoJsonSource(BOUNDARY_END_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(SymbolLayer("flightlog-boundary-end-point", BOUNDARY_END_SOURCE).withProperties(
        iconImage(BOUNDARY_END_IMAGE), iconSize(.8f), iconAllowOverlap(true),
    ))
    style.addSource(GeoJsonSource(COMPARISON_ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(LineLayer("flightlog-comparison-route-line", COMPARISON_ROUTE_SOURCE).withProperties(
        lineColor("#FFB84D"), lineWidth(4f), lineDasharray(arrayOf(2f, 2f)),
    ))
    style.addImage(JUMP_TAKEOFF_IMAGE, jumpEndpointBitmap(context, Color.rgb(183, 243, 74)))
    style.addSource(GeoJsonSource(JUMP_TAKEOFF_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(SymbolLayer(JUMP_TAKEOFF_LAYER, JUMP_TAKEOFF_SOURCE).withProperties(
        iconImage(JUMP_TAKEOFF_IMAGE), iconAllowOverlap(true),
    ))
    style.addImage(JUMP_LANDING_IMAGE, jumpEndpointBitmap(context, Color.rgb(255, 184, 77)))
    style.addSource(GeoJsonSource(JUMP_LANDING_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(SymbolLayer(JUMP_LANDING_LAYER, JUMP_LANDING_SOURCE).withProperties(
        iconImage(JUMP_LANDING_IMAGE), iconAllowOverlap(true),
    ))
    style.addSource(GeoJsonSource(JUMP_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(CircleLayer(JUMP_SELECTED_LAYER, JUMP_SOURCE).withProperties(
        circleColor("#42D9E8"), circleRadius(14f),
    ).withFilter(Expression.eq(Expression.get("selected"), Expression.literal(true))))
    style.addLayer(CircleLayer(JUMP_CIRCLE_LAYER, JUMP_SOURCE).withProperties(
        circleColor("#132019"), circleRadius(11f),
    ))
    style.addLayer(SymbolLayer(JUMP_LABEL_LAYER, JUMP_SOURCE).withProperties(
        textField(Expression.get("label")), textSize(12f), textColor("#FFFFFF"),
        textHaloColor("#132019"), textHaloWidth(1f), textAllowOverlap(true),
    ))
    style.addSource(GeoJsonSource(SPLIT_ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(LineLayer("flightlog-split-route-lines", SPLIT_ROUTE_SOURCE).withProperties(
        lineColor(Expression.get("color")), lineWidth(7f),
    ))
    style.addSource(GeoJsonSource(PAUSE_ZONE_ROUTE_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(LineLayer("flightlog-pause-zone-lines", PAUSE_ZONE_ROUTE_SOURCE).withProperties(
        lineColor("#D22A2A"), lineWidth(13f), lineOpacity(.58f),
    ))
    style.addSource(GeoJsonSource(SPLIT_LABEL_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(CircleLayer("flightlog-split-label-circles", SPLIT_LABEL_SOURCE).withProperties(
        circleColor("#132019"), circleRadius(11f),
    ))
    style.addLayer(SymbolLayer("flightlog-split-labels", SPLIT_LABEL_SOURCE).withProperties(
        textField(Expression.get("label")), textSize(12f), textColor("#FFFFFF"),
        textHaloColor("#132019"), textHaloWidth(1f), textAllowOverlap(true),
    ))
    style.addImage(STOP_IMAGE, stopSignBitmap(context))
    style.addSource(GeoJsonSource(STOP_SOURCE, FeatureCollection.fromFeatures(emptyArray())))
    style.addLayer(SymbolLayer("flightlog-stop-points", STOP_SOURCE).withProperties(
        iconImage(STOP_IMAGE), iconSize(.75f), iconAllowOverlap(true),
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
    highlightedPoints: List<TrackPointEntity> = emptyList(),
    stopPoints: List<TrackPointEntity> = emptyList(),
    splitRoutes: List<List<TrackPointEntity>> = emptyList(),
    pauseZoneRoutes: List<List<TrackPointEntity>> = emptyList(),
    selectedSplitIndex: Int = 0,
    boundaryStart: TrackPointEntity? = null,
    boundaryEnd: TrackPointEntity? = null,
    selectedJumpId: Long? = null,
) {
    val readyMap = map ?: return
    readyMap.getStyle { style ->
        val routeFeatures = if (points.size >= 2) {
            arrayOf(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })))
        } else emptyArray()
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(routeFeatures))
        style.getLayerAs<LineLayer>("flightlog-route-line")?.setProperties(
            lineColor(if (highlightedPoints.isEmpty()) "#42D9E8" else "#6F7B73"),
            lineWidth(if (highlightedPoints.isEmpty()) 5f else 4f),
        )
        val highlightedFeatures = if (highlightedPoints.size >= 2) {
            arrayOf(Feature.fromGeometry(LineString.fromLngLats(highlightedPoints.map { Point.fromLngLat(it.longitude, it.latitude) })))
        } else emptyArray()
        style.getSourceAs<GeoJsonSource>(HIGHLIGHTED_ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(highlightedFeatures))
        style.getSourceAs<GeoJsonSource>(BOUNDARY_START_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(boundaryStart?.let {
                arrayOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))
            } ?: emptyArray()),
        )
        style.getSourceAs<GeoJsonSource>(BOUNDARY_END_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(boundaryEnd?.let {
                arrayOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))
            } ?: emptyArray()),
        )
        val comparisonFeatures = if (comparisonPoints.size >= 2) {
            arrayOf(Feature.fromGeometry(LineString.fromLngLats(comparisonPoints.map { Point.fromLngLat(it.longitude, it.latitude) })))
        } else emptyArray()
        style.getSourceAs<GeoJsonSource>(COMPARISON_ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(comparisonFeatures))
        val numbers = jumpNumbers(jumps)
        val jumpCoordinates = jumps.associateWith { jump -> jumpMapCoordinates(jump, points) }
        val jumpFeatures = jumps.mapNotNull { jump ->
            val coordinate = jumpCoordinates.getValue(jump).center ?: return@mapNotNull null
            Feature.fromGeometry(Point.fromLngLat(coordinate.longitude, coordinate.latitude)).apply {
                addNumberProperty("jumpId", jump.id)
                addStringProperty("label", numbers[jump.id]?.toString() ?: "")
                addBooleanProperty("selected", jump.id == selectedJumpId)
            }
        }
        style.getSourceAs<GeoJsonSource>(JUMP_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(jumpFeatures))
        val takeoffFeatures = jumps.mapNotNull { jump ->
            jumpCoordinates.getValue(jump).takeoff?.let { coordinate ->
                Feature.fromGeometry(Point.fromLngLat(coordinate.longitude, coordinate.latitude)).apply {
                    addNumberProperty("jumpId", jump.id)
                }
            }
        }
        style.getSourceAs<GeoJsonSource>(JUMP_TAKEOFF_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(takeoffFeatures))
        val landingFeatures = jumps.mapNotNull { jump ->
            jumpCoordinates.getValue(jump).landing?.let { coordinate ->
                Feature.fromGeometry(Point.fromLngLat(coordinate.longitude, coordinate.latitude)).apply {
                    addNumberProperty("jumpId", jump.id)
                }
            }
        }
        style.getSourceAs<GeoJsonSource>(JUMP_LANDING_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(landingFeatures))
        val stopFeatures = stopPoints.map {
            Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))
        }
        style.getSourceAs<GeoJsonSource>(STOP_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(stopFeatures))
        val splitColors = listOf("#42D9E8", "#A7E34B", "#FFB84D", "#9C8CFF", "#54B6FF")
        val splitFeatures = splitRoutes.mapIndexedNotNull { index, route ->
            if (route.size < 2) return@mapIndexedNotNull null
            Feature.fromGeometry(LineString.fromLngLats(route.map { Point.fromLngLat(it.longitude, it.latitude) })).apply {
                addStringProperty("color", if (index == selectedSplitIndex) "#42D9E8" else splitColors[index % splitColors.size])
            }
        }
        style.getSourceAs<GeoJsonSource>(SPLIT_ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(splitFeatures))
        val pauseFeatures = pauseZoneRoutes.mapNotNull { route ->
            if (route.size < 2) null else Feature.fromGeometry(
                LineString.fromLngLats(route.map { Point.fromLngLat(it.longitude, it.latitude) }),
            )
        }
        style.getSourceAs<GeoJsonSource>(PAUSE_ZONE_ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(pauseFeatures))
        val labelFeatures = splitRoutes.mapIndexedNotNull { index, route ->
            route.getOrNull(route.size / 2)?.let { point ->
                Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
                    addStringProperty("label", (index + 1).toString())
                }
            }
        }
        style.getSourceAs<GeoJsonSource>(SPLIT_LABEL_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(labelFeatures))
        val rider = points.lastOrNull().takeIf { showRider }
        val riderFeatures = rider?.let {
            arrayOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply {
                addNumberProperty("bearing", it.bearingDegrees ?: 0f)
            })
        } ?: emptyArray()
        style.getSourceAs<GeoJsonSource>(RIDER_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(riderFeatures))
        val selectedJump = jumps.firstOrNull { it.id == selectedJumpId }
        val selectedCoordinate = selectedJump?.let { jumpCoordinates.getValue(it).center }
        if (fitRoute && selectedCoordinate != null) {
            readyMap.cameraPosition = CameraPosition.Builder()
                .target(LatLng(selectedCoordinate.latitude, selectedCoordinate.longitude))
                .zoom(maxOf(readyMap.cameraPosition.zoom, JUMP_ZOOM))
                .build()
        } else if (fitRoute && points.size >= 2) {
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

private fun stopSignBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (30 * density).toInt()
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val center = size / 2f
        fun octagon(radius: Float) = Path().apply {
            repeat(8) { index ->
                val angle = Math.PI / 8.0 + index * Math.PI / 4.0
                val x = center + cos(angle).toFloat() * radius
                val y = center + sin(angle).toFloat() * radius
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        canvas.drawPath(octagon(size * .48f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        })
        canvas.drawPath(octagon(size * .40f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(210, 42, 42)
            style = Paint.Style.FILL
        })
    }
}

private fun boundaryHandleBitmap(context: Context, fillColor: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (48 * density).toInt()
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val center = size / 2f
        canvas.drawCircle(center, center, size * .48f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(16, 21, 18)
            style = Paint.Style.FILL
        })
        canvas.drawCircle(center, center, size * .41f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        })
        val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(23, 32, 0)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = size * .055f
        }
        canvas.drawLine(size * .34f, size * .44f, size * .66f, size * .44f, gripPaint)
        canvas.drawLine(size * .34f, size * .56f, size * .66f, size * .56f, gripPaint)
    }
}

private fun jumpEndpointBitmap(context: Context, fillColor: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (24 * density).toInt()
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val center = size / 2f
        canvas.drawCircle(center, center, size * .48f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(16, 21, 18)
            style = Paint.Style.FILL
        })
        canvas.drawCircle(center, center, size * .34f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        })
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
