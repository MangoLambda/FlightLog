package com.example.flightlog.tracking

import com.example.flightlog.data.TrackPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryCodecTest {
    @Test fun gpsRoundTripPreservesComparisonPrecision() {
        val points = (0 until 500).map { index ->
            TrackPointEntity(
                rideId = 7, recordedAt = 1_700_000_000_000L + index * 200,
                latitude = 45.5000001 + index * 0.000001,
                longitude = -73.5000001 - index * 0.000001,
                altitudeMeters = if (index % 7 == 0) null else 123.456,
                speedMps = 8.123, bearingDegrees = 182.345f, accuracyMeters = 3.456f,
            )
        }
        val encoded = TelemetryCodec.encodeGps(points)
        val decoded = TelemetryCodec.decodeGps(7, encoded.payload, encoded.checksum)
        assertEquals(points.size, decoded.size)
        assertEquals(points.last().recordedAt, decoded.last().recordedAt)
        assertEquals(points.last().latitude, decoded.last().latitude, 0.0000001)
        assertEquals(points.last().longitude, decoded.last().longitude, 0.0000001)
        assertEquals(points[1].altitudeMeters!!, decoded[1].altitudeMeters!!, 0.01)
        assertEquals(points.last().speedMps, decoded.last().speedMps, 0.01)
        assertTrue(encoded.payload.size < points.size * 24)
    }

    @Test fun motionRoundTripAndChecksumFailure() {
        val samples = (0 until 500).map { index ->
            MotionSample(1_700_000_000_000L + index * 20, 0.1f * index, 1f, 9.8f, .2f, .3f, .4f)
        }
        val encoded = TelemetryCodec.encodeMotion(samples)
        val decoded = TelemetryCodec.decodeMotion(encoded.payload, encoded.checksum)
        assertEquals(samples.size, decoded.size)
        assertEquals(samples.last().timestampMillis, decoded.last().timestampMillis)
        assertEquals(samples[10].accelerationX, decoded[10].accelerationX, .001f)
        val corrupt = encoded.payload.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { TelemetryCodec.decodeMotion(corrupt, encoded.checksum) }
    }
}
