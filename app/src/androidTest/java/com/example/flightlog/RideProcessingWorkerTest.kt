package com.example.flightlog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.flightlog.data.RideEntity
import com.example.flightlog.domain.RideState
import com.example.flightlog.tracking.RideProcessingWorker
import com.example.flightlog.tracking.TrailAnalysis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideProcessingWorkerTest {
    @Test fun completedRideIsProcessedOutsideTrackingService() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<FlightLogApplication>()
        app.getSharedPreferences("processing", Context.MODE_PRIVATE)
            .edit().putInt("effort_version", TrailAnalysis.EFFORT_VERSION).commit()
        val rideId = app.database.dao().insertRide(RideEntity(
            startedAt = 1_000,
            endedAt = 2_000,
            state = RideState.COMPLETED,
        ))
        val worker = TestListenableWorkerBuilder<RideProcessingWorker>(app).build()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertNotNull(app.database.dao().ride(rideId)?.archivedAt)
        app.database.dao().deleteFinishedRide(rideId)
    }
}
