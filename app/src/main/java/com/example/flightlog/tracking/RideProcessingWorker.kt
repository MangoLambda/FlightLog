package com.example.flightlog.tracking

import android.content.Context
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.flightlog.FlightLogApplication
import kotlinx.coroutines.CancellationException

/** Performs ride compaction and trail analysis after recording has stopped. */
class RideProcessingWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FlightLogApplication
        return try {
            app.rideProcessor.materializeMissingJumpTraces()
            app.database.dao().ridesNeedingProcessing(TrailAnalysis.ANALYSIS_VERSION)
                .forEach { app.rideProcessor.compactAndAnalyze(it.id) }
            app.rideProcessor.cleanupExpiredMotion()

            val preferences = app.getSharedPreferences(PROCESSING_PREFERENCES, Context.MODE_PRIVATE)
            if (preferences.getInt(EFFORT_VERSION_KEY, 0) < TrailAnalysis.EFFORT_VERSION) {
                app.rideProcessor.rebuildAllTrails()
                preferences.edit { putInt(EFFORT_VERSION_KEY, TrailAnalysis.EFFORT_VERSION) }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "ride-processing"
        private const val PROCESSING_PREFERENCES = "processing"
        private const val EFFORT_VERSION_KEY = "effort_version"
        private const val MAX_RETRY_ATTEMPTS = 3

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RideProcessingWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
