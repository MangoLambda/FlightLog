package com.example.flightlog.tracking

import com.example.flightlog.domain.RideState
import com.example.flightlog.domain.MountingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GpsStatus { ACQUIRING, READY, POOR_SIGNAL, UNAVAILABLE, PERMISSION_DENIED, ERROR }

data class LiveRideState(
    val rideId: Long? = null,
    val state: RideState? = null,
    val startedAt: Long? = null,
    val speedMps: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val jumpCount: Int = 0,
    val flightTimeSeconds: Double = 0.0,
    val gpsAccuracyMeters: Float? = null,
    val mountingMode: MountingMode = MountingMode.POCKET,
    val minimumJumpHeightMeters: Float = RecordingSettings.DEFAULT_POCKET_MINIMUM_HEIGHT_METERS,
    val gpsStatus: GpsStatus = GpsStatus.ACQUIRING,
    val gpsMessage: String? = null,
)

object TrackingState {
    private val mutable = MutableStateFlow(LiveRideState())
    val state = mutable.asStateFlow()
    fun update(value: LiveRideState) { mutable.value = value }
    fun clear() { mutable.value = LiveRideState() }
}
