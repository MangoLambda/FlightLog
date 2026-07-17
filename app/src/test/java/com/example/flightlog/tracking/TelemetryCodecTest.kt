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

    @Test fun multiChannelMotionRoundTripAndChecksumFailure() {
        val start = 1_700_000_000_000L
        val telemetry = MotionTelemetry(
            orientationSource = OrientationSource.GAME_ROTATION_VECTOR,
            accelerometer = (0 until 500).map { Vector3Sample(start + it * 10, 0.1f * it, 1f, 9.8f) },
            gyroscope = (0 until 500).map { Vector3Sample(start + it * 10, .2f, .3f, .4f) },
            orientation = (0 until 500).map { RotationSample(start + it * 10, 0f, 0f, 0f, 1f) },
            pressure = (0 until 125).map { PressureSample(start + it * 40, 1_000f - it * .0001f) },
        )
        val encoded = TelemetryCodec.encodeMotion(telemetry)
        val decoded = TelemetryCodec.decodeMotion(encoded.payload, encoded.checksum)
        assertEquals(TelemetryCodec.MOTION_ENCODING_VERSION, encoded.encodingVersion)
        assertEquals(telemetry.sampleCount, decoded.sampleCount)
        assertEquals(telemetry.endedAt, decoded.endedAt)
        assertEquals(telemetry.accelerometer[10].x, decoded.accelerometer[10].x, .001f)
        assertEquals(telemetry.pressure[10].pressureHpa, decoded.pressure[10].pressureHpa, .0001f)
        assertEquals(OrientationSource.GAME_ROTATION_VECTOR, decoded.orientationSource)
        val corrupt = encoded.payload.clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { TelemetryCodec.decodeMotion(corrupt, encoded.checksum) }
    }

    @Test fun legacyMotionDecodesIntoSeparateAccelerationAndGyroscopeChannels() {
        val samples = (0 until 20).map { index ->
            MotionSample(1_000L + index * 20, index.toFloat(), 1f, 9.8f, .2f, .3f, .4f)
        }

        val decoded = TelemetryCodec.encodeLegacyMotion(samples).let {
            TelemetryCodec.decodeMotion(it.payload, it.checksum)
        }

        assertEquals(TelemetryCodec.LEGACY_MOTION_ENCODING_VERSION, decoded.encodingVersion)
        assertEquals(samples.size, decoded.sampleCount)
        assertEquals(samples, decoded.accelerationFrames())
        assertTrue(decoded.orientation.isEmpty())
        assertTrue(decoded.pressure.isEmpty())
    }

    @Test fun reencodingLegacyMotionReportsTheV2ChannelSampleCount() {
        val legacy = TelemetryCodec.encodeLegacyMotion(listOf(
            MotionSample(1_000, 0f, 0f, 9.8f, .1f, .2f, .3f),
            MotionSample(1_010, 0f, 0f, 9.7f, .2f, .3f, .4f),
        )).let { TelemetryCodec.decodeMotion(it.payload, it.checksum) }

        val reencoded = TelemetryCodec.encodeMotion(legacy)
        val decoded = TelemetryCodec.decodeMotion(reencoded.payload, reencoded.checksum)

        assertEquals(4, reencoded.sampleCount)
        assertEquals(reencoded.sampleCount, decoded.sampleCount)
        assertEquals(TelemetryCodec.MOTION_ENCODING_VERSION, decoded.encodingVersion)
    }
}
