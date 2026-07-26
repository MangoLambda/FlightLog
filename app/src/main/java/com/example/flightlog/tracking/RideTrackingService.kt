package com.example.flightlog.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.flightlog.FlightLogApplication
import com.example.flightlog.MainActivity
import com.example.flightlog.R
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.domain.RideState
import com.example.flightlog.domain.SensorQuality
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import com.example.flightlog.bikepark.GeoPoint
import com.example.flightlog.bikepark.ParkDayState
import com.example.flightlog.bikepark.ParkGeometry
import com.example.flightlog.bikepark.ParkSamplingProfile
import com.example.flightlog.bikepark.ParkZoneType
import com.example.flightlog.bikepark.SummitZoneTracker
import com.example.flightlog.bikepark.samplingProfile
import com.example.flightlog.data.ParkZoneEntity

class RideTrackingService : Service(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { (application as FlightLogApplication).database.dao() }
    private lateinit var locationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var ride: RideEntity? = null
    private var previousLocation: LocationSample? = null
    private val speedWindow = ArrayDeque<Double>()
    private var detector: JumpDetector? = null
    private var latestLocation: Location? = null
    private var detectedJumps = 0
    private var detectedFlightSeconds = 0.0
    private var stoppingNormally = false
    private var sensorsRegistered = false
    private var parkSamplingProfile: ParkSamplingProfile? = null
    private lateinit var recordingSettings: RecordingSettings
    private var gpsStatus = GpsStatus.ACQUIRING
    private var gpsMessage: String? = null
    private val motionBuffer = MotionTelemetryBuffer()
    private var lastMotionJob: Job? = null
    private var lastLocationJob: Job? = null
    private var orientationSource = OrientationSource.NONE
    private var lastStationaryStoredAt = 0L
    private var bikeParkId: Long? = null
    private var bikeParkName: String? = null
    private var parkZones: List<ParkZoneEntity> = emptyList()
    private var parkDayState = ParkDayState.INACTIVE
    private val summitZoneTracker = SummitZoneTracker()
    private var bottomInsideFixes = 0
    private var pendingStartedAt = 0L
    private val pendingLocations = ArrayDeque<Location>()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::acceptLocation)
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable && latestLocation == null) {
                gpsStatus = GpsStatus.UNAVAILABLE
                gpsMessage = "Location provider unavailable"
                publishState()
            } else if (latestLocation == null) {
                gpsStatus = GpsStatus.ACQUIRING
                gpsMessage = null
                publishState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        recordingSettings = RecordingSettingsStore.read(this)
        createNotificationChannel()
        configureDetector()
        val preferences = getSharedPreferences(PARK_DAY_PREFERENCES, MODE_PRIVATE)
        preferences.getLong(KEY_PARK_ID, 0L).takeIf { it > 0 }?.let {
            bikeParkId = it
            restoreParkDay(it)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: if (bikeParkId != null) ACTION_RESTORE_PARK else ACTION_START) {
            ACTION_START -> startRide()
            ACTION_PAUSE -> pauseRide()
            ACTION_RESUME -> resumeRide()
            ACTION_STOP -> finishRide()
            ACTION_START_PARK -> startParkDay(intent?.getLongExtra(EXTRA_PARK_ID, 0L) ?: 0L)
            ACTION_END_PARK -> endParkDay()
            ACTION_RESTORE_PARK -> Unit
        }
        return if (bikeParkId != null || intent?.action == ACTION_START_PARK) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRide() {
        if (ride != null || bikeParkId != null) return
        startInForeground(paused = false)
        acquireWakeLock()
        scope.launch {
            val created = RideEntity(
                startedAt = System.currentTimeMillis(),
                mountingMode = recordingSettings.mountingMode,
            )
            val id = dao.insertRide(created)
            ride = created.copy(id = id)
            publishState()
            startSampling()
        }
    }

    private fun pauseRide() {
        if (bikeParkId != null) return
        val current = ride ?: return
        stopSampling()
        flushMotion()
        previousLocation = null
        speedWindow.clear()
        val pausedRide = current.copy(state = RideState.PAUSED)
        ride = pausedRide
        val previousWrite = lastLocationJob
        lastLocationJob = scope.launch { previousWrite?.join(); dao.updateRide(pausedRide); publishState() }
        startInForeground(paused = true)
    }

    private fun resumeRide() {
        if (bikeParkId != null) return
        val current = ride ?: return
        val resumedRide = current.copy(state = RideState.RECORDING)
        ride = resumedRide
        val previousWrite = lastLocationJob
        lastLocationJob = scope.launch { previousWrite?.join(); dao.updateRide(resumedRide); publishState() }
        startSampling()
        startInForeground(paused = false)
    }

    private fun finishRide() {
        if (bikeParkId != null) {
            endParkDay()
            return
        }
        val current = ride ?: run { stopSelf(); return }
        stoppingNormally = true
        stopSampling()
        releaseWakeLock()
        scope.launch {
            lastLocationJob?.join()
            flushMotion().joinAllMotion()
            val finished = current.copy(endedAt = System.currentTimeMillis(), state = RideState.COMPLETED)
            dao.updateRide(finished)
            ride = finished
            RideProcessingWorker.enqueue(this@RideTrackingService)
            TrackingState.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startSampling() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            gpsStatus = GpsStatus.PERMISSION_DENIED
            gpsMessage = "Precise location permission required"
            publishState()
            return
        }
        gpsStatus = GpsStatus.ACQUIRING
        gpsMessage = null
        publishState()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 200L)
            .setMinUpdateIntervalMillis(200L)
            .setMaxUpdateDelayMillis(1_000L)
            .setMinUpdateDistanceMeters(1f)
            .build()
        locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            .addOnFailureListener { error ->
                gpsStatus = GpsStatus.ERROR
                gpsMessage = error.message?.take(100) ?: "Could not start GPS"
                publishState()
            }
        registerSensors()
    }

    private fun applyParkSamplingProfile(force: Boolean = false) {
        val profile = parkDayState.samplingProfile()
        if (!force && parkSamplingProfile == profile) return
        parkSamplingProfile = profile
        startLocationSampling(profile)
        registerSensors(
            if (profile == ParkSamplingProfile.LOW_POWER) SensorManager.SENSOR_DELAY_NORMAL
            else SensorSamplingProfile.MOTION_PERIOD_US,
        )
    }

    private fun startLocationSampling(profile: ParkSamplingProfile) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            gpsStatus = GpsStatus.PERMISSION_DENIED
            gpsMessage = "Precise location permission required"
            publishState()
            return
        }
        gpsStatus = GpsStatus.ACQUIRING
        gpsMessage = null
        val request = when (profile) {
            ParkSamplingProfile.LOW_POWER ->
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, PARK_LOW_POWER_INTERVAL_MILLIS)
                    .setMinUpdateIntervalMillis(PARK_LOW_POWER_INTERVAL_MILLIS)
                    .setMaxUpdateDelayMillis(PARK_LOW_POWER_MAX_DELAY_MILLIS)
                    .setMinUpdateDistanceMeters(PARK_LOW_POWER_DISTANCE_METERS)
                    .build()
            ParkSamplingProfile.NORMAL ->
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
                    .setMinUpdateIntervalMillis(250L)
                    .setMaxUpdateDelayMillis(1_000L)
                    .setMinUpdateDistanceMeters(1f)
                    .build()
        }
        locationClient.removeLocationUpdates(locationCallback).addOnCompleteListener {
            locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                .addOnFailureListener { error ->
                    gpsStatus = GpsStatus.ERROR
                    gpsMessage = error.message?.take(100) ?: "Could not start GPS"
                    publishState()
                }
        }
    }

    private fun stopSampling() {
        locationClient.removeLocationUpdates(locationCallback)
        stopMotionSampling()
    }

    private fun stopMotionSampling() {
        if (sensorsRegistered) sensorManager.unregisterListener(this)
        sensorsRegistered = false
    }

    private fun registerSensors(samplingPeriodUs: Int = SensorSamplingProfile.MOTION_PERIOD_US) {
        if (sensorsRegistered) sensorManager.unregisterListener(this)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gameRotation = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val rotation = gameRotation ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        orientationSource = when (rotation?.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> OrientationSource.GAME_ROTATION_VECTOR
            Sensor.TYPE_ROTATION_VECTOR -> OrientationSource.ROTATION_VECTOR
            else -> OrientationSource.NONE
        }
        val registered = listOfNotNull(accelerometer, gyroscope, rotation).map {
            sensorManager.registerListener(this, it, samplingPeriodUs)
        }
        sensorsRegistered = registered.any { it }
    }

    private fun configureDetector() {
        val quality = when {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null -> SensorQuality.DEGRADED
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) == null -> SensorQuality.ACCELEROMETER_ONLY
            else -> SensorQuality.FULL
        }
        detector = JumpDetector(
            sensorQuality = quality,
            mountingMode = recordingSettings.mountingMode,
            minimumJumpHeightMeters = recordingSettings.activeMinimumHeightMeters.toDouble(),
        ) { takeoffNanos, landingNanos, confidence ->
            recordJump(takeoffNanos, landingNanos, confidence, quality)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val timestampMillis = sensorTimestampMillis(event.timestamp)
        val recording = ride?.state == RideState.RECORDING
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (recording) {
                    detector?.onAcceleration(event.timestamp, event.values[0], event.values[1], event.values[2])
                }
                if (recording && motionBuffer.addAcceleration(Vector3Sample(
                        timestampMillis, event.values[0], event.values[1], event.values[2],
                    )) >= MOTION_BUFFER_EVENT_LIMIT
                ) {
                    flushMotion()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (recording) detector?.onGyroscope(event.values[0], event.values[1], event.values[2])
                if (recording && motionBuffer.addGyroscope(Vector3Sample(
                        timestampMillis, event.values[0], event.values[1], event.values[2],
                    )) >= MOTION_BUFFER_EVENT_LIMIT
                ) {
                    flushMotion()
                }
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> if (recording) {
                val quaternion = FloatArray(4)
                SensorManager.getQuaternionFromVector(quaternion, event.values)
                if (motionBuffer.addOrientation(RotationSample(
                        timestampMillis, quaternion[1], quaternion[2], quaternion[3], quaternion[0],
                    )) >= MOTION_BUFFER_EVENT_LIMIT
                ) {
                    flushMotion()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun sensorTimestampMillis(timestampNanos: Long): Long {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        return System.currentTimeMillis() - (nowNanos - timestampNanos) / 1_000_000L
    }

    private fun acceptLocation(location: Location) {
        if (bikeParkId != null) {
            acceptParkLocation(location)
            return
        }
        acceptRideLocation(location)
    }

    private fun acceptRideLocation(location: Location) {
        val currentRide = ride ?: return
        if (currentRide.state != RideState.RECORDING) return
        val provisional = LocationSample(
            timestamp = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            speedMps = 0.0,
        )
        val sample = provisional.copy(
            speedMps = RideMath.effectiveSpeedMetersPerSecond(
                reportedSpeedMps = location.speed.toDouble().takeIf { location.hasSpeed() },
                previous = previousLocation,
                next = provisional,
            ),
        )
        if (location.accuracy > 35f) {
            gpsStatus = GpsStatus.POOR_SIGNAL
            gpsMessage = "Accuracy ±${location.accuracy.toInt()} m"
            publishState()
        }
        if (!RideMath.isUsableLocation(previousLocation, sample)) return
        val segment = previousLocation?.let { RideMath.distanceMeters(it, sample) } ?: 0.0
        val elapsed = previousLocation?.let { (sample.timestamp - it.timestamp).coerceIn(0L, 5_000L) } ?: 0L
        previousLocation = sample
        latestLocation = location
        gpsStatus = GpsStatus.READY
        gpsMessage = null
        speedWindow.addLast(sample.speedMps)
        while (speedWindow.size > 5) speedWindow.removeFirst()
        val speed = RideMath.smoothedSpeedMetersPerSecond(speedWindow)
        ride = currentRide.copy(
            distanceMeters = currentRide.distanceMeters + segment,
            movingTimeMillis = currentRide.movingTimeMillis + if (speed >= 0.8) elapsed else 0L,
            maxSpeedMps = max(currentRide.maxSpeedMps, speed),
        )
        if (speed < 0.8 && sample.timestamp - lastStationaryStoredAt < 1_000L) {
            publishState(speed)
            return
        }
        if (speed < 0.8) lastStationaryStoredAt = sample.timestamp
        val persistedRide = ride!!
        val previousWrite = lastLocationJob
        lastLocationJob = scope.launch {
            previousWrite?.join()
            dao.insertTrackPoint(TrackPointEntity(
                rideId = currentRide.id,
                recordedAt = location.time,
                latitude = location.latitude,
                longitude = location.longitude,
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                speedMps = speed,
                bearingDegrees = if (location.hasBearing()) location.bearing else null,
                accuracyMeters = location.accuracy,
            ))
            dao.updateRide(persistedRide)
            publishState(speed)
        }
    }

    private fun startParkDay(parkId: Long) {
        if (parkId <= 0 || ride != null || bikeParkId != null) return
        startInForeground(paused = true)
        acquireWakeLock()
        scope.launch {
            val park = dao.bikePark(parkId)
            val zones = dao.parkZones(parkId)
            if (park == null || zones.none { it.type == ParkZoneType.SUMMIT } || zones.none { it.type == ParkZoneType.BOTTOM }) {
                gpsStatus = GpsStatus.ERROR
                gpsMessage = "Bike park needs summit and bottom zones"
                publishState()
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseWakeLock()
                stopSelf()
                return@launch
            }
            bikeParkId = parkId
            bikeParkName = park.name
            parkZones = zones
            parkDayState = ParkDayState.WAITING_FOR_SUMMIT
            getSharedPreferences(PARK_DAY_PREFERENCES, MODE_PRIVATE).edit().putLong(KEY_PARK_ID, parkId).apply()
            applyParkSamplingProfile(force = true)
            startInForeground(paused = true)
            publishState()
        }
    }

    private fun restoreParkDay(parkId: Long) {
        startInForeground(paused = true)
        acquireWakeLock()
        scope.launch {
            val park = dao.bikePark(parkId)
            val zones = dao.parkZones(parkId)
            if (park == null || zones.isEmpty()) {
                clearParkPreference()
                stopSelf()
                return@launch
            }
            bikeParkId = parkId
            bikeParkName = park.name
            parkZones = zones
            parkDayState = ParkDayState.WAITING_FOR_SUMMIT
            applyParkSamplingProfile(force = true)
            publishState()
        }
    }

    private fun acceptParkLocation(location: Location) {
        latestLocation = location
        if (location.accuracy > PARK_MAX_ACCURACY_METERS) {
            gpsStatus = GpsStatus.POOR_SIGNAL
            gpsMessage = "Accuracy ±${location.accuracy.toInt()} m"
            publishState()
            return
        }
        gpsStatus = GpsStatus.READY
        gpsMessage = null
        val point = GeoPoint(location.latitude, location.longitude)
        fun inside(type: ParkZoneType) = parkZones.asSequence().filter { it.type == type }
            .any { ParkGeometry.contains(point, ParkGeometry.decode(it.encodedVertices)) }
        val inSummit = inside(ParkZoneType.SUMMIT)
        val inBottom = inside(ParkZoneType.BOTTOM)
        val inExclusion = inside(ParkZoneType.EXCLUSION)
        val previousParkDayState = parkDayState
        when (parkDayState) {
            ParkDayState.WAITING_IN_SUMMIT, ParkDayState.WAITING_FOR_SUMMIT -> {
                parkDayState = summitZoneTracker.observe(parkDayState, inSummit)
                if (parkDayState == ParkDayState.PENDING_START) {
                    pendingStartedAt = location.time
                    pendingLocations.clear()
                    pendingLocations += Location(location)
                }
            }
            ParkDayState.PENDING_START -> {
                pendingLocations += Location(location)
                while (pendingLocations.size > PARK_PENDING_POINT_LIMIT) pendingLocations.removeFirst()
                if (inExclusion || inSummit) {
                    pendingLocations.clear()
                    parkDayState = if (inSummit) {
                        summitZoneTracker.observe(ParkDayState.WAITING_IN_SUMMIT, inSummit = true)
                    } else {
                        ParkDayState.WAITING_FOR_SUMMIT
                    }
                } else if (location.time - pendingStartedAt >= PARK_PENDING_WINDOW_MILLIS) {
                    beginAutomaticRun()
                }
            }
            ParkDayState.RECORDING_RUN -> {
                acceptRideLocation(location)
                if (inBottom) bottomInsideFixes++ else bottomInsideFixes = 0
                if (bottomInsideFixes >= PARK_CONFIRMATION_FIXES) finishAutomaticRun()
            }
            ParkDayState.INACTIVE -> Unit
        }
        if (parkDayState != previousParkDayState) applyParkSamplingProfile()
        publishState()
    }

    private fun beginAutomaticRun() {
        if (ride != null) return
        val parkId = bikeParkId ?: return
        scope.launch {
            val first = pendingLocations.firstOrNull()
            val created = RideEntity(
                startedAt = first?.time ?: System.currentTimeMillis(),
                mountingMode = recordingSettings.mountingMode,
                bikeParkId = parkId,
                automaticParkRun = true,
            )
            val id = dao.insertRide(created)
            ride = created.copy(id = id)
            previousLocation = null
            speedWindow.clear()
            detectedJumps = 0
            detectedFlightSeconds = 0.0
            parkDayState = ParkDayState.RECORDING_RUN
            configureDetector()
            applyParkSamplingProfile()
            val buffered = pendingLocations.toList()
            pendingLocations.clear()
            buffered.forEach(::acceptRideLocation)
            startInForeground(paused = false)
            publishState()
        }
    }

    private fun finishAutomaticRun() {
        val current = ride ?: return
        parkDayState = ParkDayState.WAITING_FOR_SUMMIT
        bottomInsideFixes = 0
        stopMotionSampling()
        val finalMotion = flushMotion()
        ride = null
        applyParkSamplingProfile()
        previousLocation = null
        speedWindow.clear()
        scope.launch {
            lastLocationJob?.join()
            finalMotion.joinAllMotion()
            dao.updateRide(current.copy(endedAt = System.currentTimeMillis(), state = RideState.COMPLETED))
            RideProcessingWorker.enqueue(this@RideTrackingService)
            startInForeground(paused = true)
            publishState()
        }
    }

    private fun endParkDay() {
        if (bikeParkId == null) return
        val current = ride
        stoppingNormally = true
        stopSampling()
        val finalMotion = flushMotion()
        ride = null
        bikeParkId = null
        bikeParkName = null
        parkZones = emptyList()
        parkDayState = ParkDayState.INACTIVE
        parkSamplingProfile = null
        clearParkPreference()
        releaseWakeLock()
        scope.launch {
            lastLocationJob?.join()
            finalMotion.joinAllMotion()
            if (current != null) {
                dao.updateRide(current.copy(endedAt = System.currentTimeMillis(), state = RideState.COMPLETED))
                RideProcessingWorker.enqueue(this@RideTrackingService)
            }
            TrackingState.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun clearParkPreference() {
        getSharedPreferences(PARK_DAY_PREFERENCES, MODE_PRIVATE).edit().remove(KEY_PARK_ID).apply()
    }

    private fun recordJump(takeoffNanos: Long, landingNanos: Long, confidence: Int, quality: SensorQuality) {
        val currentRide = ride ?: return
        val flightSeconds = (landingNanos - takeoffNanos) / 1_000_000_000.0
        val estimate = RideMath.jumpEstimate(flightSeconds, RideMath.smoothedSpeedMetersPerSecond(speedWindow), confidence)
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val nowMillis = System.currentTimeMillis()
        val takeoffAt = nowMillis - (nowNanos - takeoffNanos) / 1_000_000L
        detectedJumps += 1
        detectedFlightSeconds += estimate.flightTimeSeconds
        scope.launch {
            dao.insertJump(JumpEventEntity(
                rideId = currentRide.id,
                takeoffAt = takeoffAt,
                landingAt = takeoffAt + (estimate.flightTimeSeconds * 1_000).toLong(),
                estimatedFlightSeconds = estimate.flightTimeSeconds,
                estimatedHeightMeters = estimate.heightMeters,
                estimatedDistanceMeters = estimate.distanceMeters,
                confidence = estimate.confidence,
                sensorQuality = quality,
                latitude = latestLocation?.latitude,
                longitude = latestLocation?.longitude,
            ))
            publishState()
        }
    }

    private fun flushMotion(): Job? {
        val currentRide = ride ?: return null
        val telemetry = motionBuffer.drain(orientationSource)
        if (telemetry.sampleCount == 0) return null
        val previousWrite = lastMotionJob
        return scope.launch {
            previousWrite?.join()
            val encoded = TelemetryCodec.encodeMotion(telemetry)
            dao.insertTelemetryChunk(encoded.toEntity(
                rideId = currentRide.id,
                kind = com.example.flightlog.domain.TelemetryKind.MOTION,
                expiresAt = encoded.endedAt + MOTION_RETENTION_MILLIS,
            ))
        }.also { lastMotionJob = it }
    }

    private suspend fun Job?.joinAllMotion() {
        this?.join()
        lastMotionJob?.join()
    }

    private fun publishState(speedMps: Double = RideMath.smoothedSpeedMetersPerSecond(speedWindow)) {
        val current = ride
        if (current == null && bikeParkId == null) return
        TrackingState.update(LiveRideState(
            rideId = current?.id,
            state = current?.state,
            startedAt = current?.startedAt,
            speedMps = speedMps,
            distanceMeters = current?.distanceMeters ?: 0.0,
            jumpCount = detectedJumps,
            flightTimeSeconds = detectedFlightSeconds,
            gpsAccuracyMeters = latestLocation?.accuracy,
            mountingMode = recordingSettings.mountingMode,
            minimumJumpHeightMeters = recordingSettings.activeMinimumHeightMeters,
            gpsStatus = gpsStatus,
            gpsMessage = gpsMessage,
            latestLocation = latestLocation?.let {
                LiveLocation(
                    recordedAt = it.time,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    bearingDegrees = it.bearing.takeIf { _ -> it.hasBearing() },
                    accuracyMeters = it.accuracy,
                )
            },
            bikeParkId = bikeParkId,
            bikeParkName = bikeParkName,
            parkDayState = parkDayState,
        ))
    }

    private fun startInForeground(paused: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (bikeParkId != null) bikeParkName ?: "Bike park day" else if (paused) "Ride paused" else "FlightLog is recording")
            .setContentText(if (bikeParkId != null) {
                if (parkDayState == ParkDayState.RECORDING_RUN) "Recording downhill run" else "GPS active · waiting for next run"
            } else if (paused) "Resume when you are ready" else "Location and motion sensors are active")
            .setContentIntent(activityIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (bikeParkId != null) addAction(0, "End park day", serviceIntent(ACTION_END_PARK, 11))
                else {
                    addAction(0, if (paused) "Resume" else "Pause", serviceIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, 10))
                    addAction(0, "Finish", serviceIntent(ACTION_STOP, 11))
                }
            }
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Ride recording", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shows while a mountain bike ride is being recorded"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, RideTrackingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun acquireWakeLock() {
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlightLog::RideRecording").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1_000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        stopSampling()
        val finalMotion = flushMotion()
        releaseWakeLock()
        val current = ride
        runBlocking(Dispatchers.IO) {
            lastLocationJob?.join()
            finalMotion.joinAllMotion()
            if (!stoppingNormally && current != null && current.state != RideState.COMPLETED) {
                dao.updateRide(current.copy(state = RideState.INTERRUPTED, endedAt = System.currentTimeMillis()))
            }
        }
        TrackingState.clear()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.flightlog.START_RIDE"
        const val ACTION_PAUSE = "com.example.flightlog.PAUSE_RIDE"
        const val ACTION_RESUME = "com.example.flightlog.RESUME_RIDE"
        const val ACTION_STOP = "com.example.flightlog.STOP_RIDE"
        const val ACTION_START_PARK = "com.example.flightlog.START_PARK_DAY"
        const val ACTION_END_PARK = "com.example.flightlog.END_PARK_DAY"
        private const val ACTION_RESTORE_PARK = "com.example.flightlog.RESTORE_PARK_DAY"
        const val EXTRA_PARK_ID = "park_id"
        private const val CHANNEL_ID = "ride_recording"
        private const val NOTIFICATION_ID = 42
        private const val MOTION_BUFFER_EVENT_LIMIT = 3_250
        private const val MOTION_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1_000
        private const val PARK_DAY_PREFERENCES = "park_day"
        private const val KEY_PARK_ID = "park_id"
        private const val PARK_CONFIRMATION_FIXES = 2
        private const val PARK_PENDING_WINDOW_MILLIS = 5_000L
        private const val PARK_PENDING_POINT_LIMIT = 40
        private const val PARK_MAX_ACCURACY_METERS = 25f
        private const val PARK_LOW_POWER_INTERVAL_MILLIS = 10_000L
        private const val PARK_LOW_POWER_MAX_DELAY_MILLIS = 30_000L
        private const val PARK_LOW_POWER_DISTANCE_METERS = 10f
    }
}
