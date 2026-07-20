package com.example.flightlog.export

import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.RideEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.FlightKind
import com.example.flightlog.domain.SensorQuality
import org.junit.Assert.assertTrue
import org.junit.Test

class RideExporterTest {
    @Test fun gpxContainsLocationElevationAndUtcTime() {
        val output = RideExporter.gpx(
            RideEntity(id = 9, startedAt = 0),
            listOf(TrackPointEntity(
                rideId = 9, recordedAt = 0, latitude = 45.5, longitude = -73.5,
                altitudeMeters = 123.4, speedMps = 2.0, bearingDegrees = null, accuracyMeters = 4f,
            )),
        )
        assertTrue(output.contains("lat=\"45.5000000\" lon=\"-73.5000000\""))
        assertTrue(output.contains("<ele>123.40</ele>"))
        assertTrue(output.contains("1970-01-01T00:00:00Z"))
    }

    @Test fun csvUsesCorrectedValuesAndStatus() {
        val output = RideExporter.jumpCsv(listOf(JumpEventEntity(
            id = 3, rideId = 9, takeoffAt = 0, landingAt = 500,
            estimatedFlightSeconds = .5, estimatedHeightMeters = .3, estimatedDistanceMeters = 4.0,
            correctedDistanceMeters = 4.5, confidence = 75, status = JumpStatus.CONFIRMED,
            sensorQuality = SensorQuality.ACCELEROMETER_ONLY,
            estimatedFlightKind = FlightKind.DROP,
            flightKindConfidence = 83,
        )))
        assertTrue(output.contains("CONFIRMED,75,0.500,0.300,4.500,ACCELEROMETER_ONLY"))
        assertTrue(output.contains("ACCELEROMETER_ONLY,DROP,DROP,83"))
    }
}
