package com.example.flightlog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.flightlog.data.FlightLogDatabase
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.domain.RideState
import com.example.flightlog.export.FlightLogBackup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlightLogBackupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun completeArchiveRoundTripIsDeduplicated() = runBlocking {
        val source = Room.inMemoryDatabaseBuilder(context, FlightLogDatabase::class.java).build()
        val ride = RideEntity(startedAt = 1_000, endedAt = 2_000, state = RideState.COMPLETED)
        val rideId = source.dao().insertRide(ride)
        source.dao().insertTrackPoint(TrackPointEntity(
            rideId = rideId, recordedAt = 1_000, latitude = 45.5, longitude = -73.5,
            altitudeMeters = 10.0, speedMps = 5.0, bearingDegrees = 90f, accuracyMeters = 3f,
        ))
        val bytes = ByteArrayOutputStream().also { FlightLogBackup(context, source).export(it) }.toByteArray()

        val destination = Room.inMemoryDatabaseBuilder(context, FlightLogDatabase::class.java).build()
        val backup = FlightLogBackup(context, destination)
        val first = backup.import(ByteArrayInputStream(bytes))
        val importedRideId = destination.dao().allRides().single().id
        assertEquals(1, destination.dao().telemetryChunks(importedRideId).size)
        val second = backup.import(ByteArrayInputStream(bytes))
        assertEquals(1, first.ridesAdded)
        assertEquals(0, second.ridesAdded)
        assertEquals(1, second.duplicateRides)
        assertEquals(1, destination.dao().allRides().size)
        assertEquals(1, destination.dao().telemetryChunks(importedRideId).size)
        source.close()
        destination.close()
    }
}
