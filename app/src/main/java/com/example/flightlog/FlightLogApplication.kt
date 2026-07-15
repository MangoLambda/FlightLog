package com.example.flightlog

import android.app.Application
import com.example.flightlog.data.FlightLogDatabase
import com.example.flightlog.data.RideRepository
import com.example.flightlog.maps.MapTileCache
import com.example.flightlog.tracking.RideProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class FlightLogApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { FlightLogDatabase.get(this) }
    val repository by lazy { RideRepository(database.dao()) }
    val rideProcessor by lazy { RideProcessor(database) }

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        MapTileCache.configure(this)
        applicationScope.launch {
            database.dao().interruptStaleRides(System.currentTimeMillis())
            database.dao().ridesNeedingProcessing().forEach { rideProcessor.compactAndAnalyze(it.id) }
            rideProcessor.cleanupExpiredMotion()
        }
    }
}
