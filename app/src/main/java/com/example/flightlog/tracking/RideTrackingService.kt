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
    private lateinit var recordingSettings: RecordingSettings
    private var gpsStatus = GpsStatus.ACQUIRING
    private var gpsMessage: String? = null
    private val motionBuffer = ArrayList<MotionSample>(512)
    private var lastMotionJob: Job? = null
    private var lastLocationJob: Job? = null
    private val latestGyroscope = FloatArray(3)
    private var lastStationaryStoredAt = 0L

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startRide()
            ACTION_PAUSE -> pauseRide()
            ACTION_RESUME -> resumeRide()
            ACTION_STOP -> finishRide()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRide() {
        if (ride != null) return
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
        val current = ride ?: return
        val resumedRide = current.copy(state = RideState.RECORDING)
        ride = resumedRide
        val previousWrite = lastLocationJob
        lastLocationJob = scope.launch { previousWrite?.join(); dao.updateRide(resumedRide); publishState() }
        startSampling()
        startInForeground(paused = false)
    }

    private fun finishRide() {
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

    private fun stopSampling() {
        locationClient.removeLocationUpdates(locationCallback)
        if (sensorsRegistered) sensorManager.unregisterListener(this)
        sensorsRegistered = false
    }

    private fun registerSensors() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sensorsRegistered = accelerometer != null || gyroscope != null
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
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                detector?.onAcceleration(event.timestamp, event.values[0], event.values[1], event.values[2])
                val current = ride
                if (current?.state == RideState.RECORDING) {
                    val nowNanos = SystemClock.elapsedRealtimeNanos()
                    val timestampMillis = System.currentTimeMillis() - (nowNanos - event.timestamp) / 1_000_000L
                    synchronized(motionBuffer) {
                        motionBuffer += MotionSample(
                            timestampMillis, event.values[0], event.values[1], event.values[2],
                            latestGyroscope[0], latestGyroscope[1], latestGyroscope[2],
                        )
                    }
                    if (motionBuffer.size >= 500) flushMotion()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyroscope[0] = event.values[0]
                latestGyroscope[1] = event.values[1]
                latestGyroscope[2] = event.values[2]
                detector?.onGyroscope(event.values[0], event.values[1], event.values[2])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun acceptLocation(location: Location) {
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
        val samples = synchronized(motionBuffer) {
            if (motionBuffer.isEmpty()) return null
            motionBuffer.toList().also { motionBuffer.clear() }
        }
        val previousWrite = lastMotionJob
        return scope.launch {
            previousWrite?.join()
            val encoded = TelemetryCodec.encodeMotion(samples)
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
        val current = ride ?: return
        TrackingState.update(LiveRideState(
            rideId = current.id,
            state = current.state,
            startedAt = current.startedAt,
            speedMps = speedMps,
            distanceMeters = current.distanceMeters,
            jumpCount = detectedJumps,
            flightTimeSeconds = detectedFlightSeconds,
            gpsAccuracyMeters = latestLocation?.accuracy,
            mountingMode = recordingSettings.mountingMode,
            minimumJumpHeightMeters = recordingSettings.activeMinimumHeightMeters,
            gpsStatus = gpsStatus,
            gpsMessage = gpsMessage,
        ))
    }

    private fun startInForeground(paused: Boolean) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (paused) "Ride paused" else "FlightLog is recording")
            .setContentText(if (paused) "Resume when you are ready" else "Location and motion sensors are active")
            .setContentIntent(activityIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, if (paused) "Resume" else "Pause", serviceIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, 10))
            .addAction(0, "Finish", serviceIntent(ACTION_STOP, 11))
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
        private const val CHANNEL_ID = "ride_recording"
        private const val NOTIFICATION_ID = 42
        private const val MOTION_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1_000
    }
}
