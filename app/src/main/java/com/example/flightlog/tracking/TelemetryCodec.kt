package com.example.flightlog.tracking

import com.example.flightlog.data.TrackPointEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.roundToInt

data class EncodedTelemetry(
    val payload: ByteArray,
    val checksum: String,
    val sampleCount: Int,
    val startedAt: Long,
    val endedAt: Long,
)

data class MotionSample(
    val timestampMillis: Long,
    val accelerationX: Float,
    val accelerationY: Float,
    val accelerationZ: Float,
    val gyroscopeX: Float,
    val gyroscopeY: Float,
    val gyroscopeZ: Float,
)

/** Versioned, bounded binary codecs used before telemetry is persisted as a Room BLOB. */
object TelemetryCodec {
    const val ENCODING_VERSION = 1
    private const val GPS_MAGIC = 0x464C4750 // FLGP
    private const val MOTION_MAGIC = 0x464C4D4F // FLMO
    private const val MAX_DECOMPRESSED_BYTES = 128 * 1024 * 1024
    private const val MAX_SAMPLES = 2_000_000

    fun encodeGps(points: List<TrackPointEntity>): EncodedTelemetry {
        require(points.isNotEmpty())
        val raw = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(GPS_MAGIC)
                output.writeByte(ENCODING_VERSION)
                writeUnsigned(output, points.size.toLong())
                var time = 0L
                var latitude = 0
                var longitude = 0
                points.forEach { point ->
                    val nextLatitude = (point.latitude * 10_000_000).roundToInt()
                    val nextLongitude = (point.longitude * 10_000_000).roundToInt()
                    writeSigned(output, point.recordedAt - time)
                    writeSigned(output, (nextLatitude - latitude).toLong())
                    writeSigned(output, (nextLongitude - longitude).toLong())
                    writeSigned(output, point.altitudeMeters?.let { (it * 100).roundToInt().toLong() } ?: Int.MIN_VALUE.toLong())
                    writeUnsigned(output, (point.speedMps.coerceIn(0.0, 100.0) * 100).roundToInt().toLong())
                    writeSigned(output, point.bearingDegrees?.let { (it * 100).roundToInt().toLong() } ?: -1L)
                    writeUnsigned(output, (point.accuracyMeters.coerceAtLeast(0f) * 100).roundToInt().toLong())
                    time = point.recordedAt
                    latitude = nextLatitude
                    longitude = nextLongitude
                }
            }
        }.toByteArray()
        return encoded(raw, points.size, points.first().recordedAt, points.last().recordedAt)
    }

    fun decodeGps(rideId: Long, payload: ByteArray, checksum: String): List<TrackPointEntity> {
        verify(payload, checksum)
        return DataInputStream(ByteArrayInputStream(inflateLimited(payload))).use { input ->
            require(input.readInt() == GPS_MAGIC) { "Not FlightLog GPS telemetry" }
            require(input.readUnsignedByte() == ENCODING_VERSION) { "Unsupported GPS telemetry version" }
            val count = readCount(input)
            var time = 0L
            var latitude = 0
            var longitude = 0
            List(count) {
                time += readSigned(input)
                latitude += readSigned(input).toInt()
                longitude += readSigned(input).toInt()
                val altitude = readSigned(input).takeUnless { it == Int.MIN_VALUE.toLong() }?.div(100.0)
                val speed = readUnsigned(input) / 100.0
                val bearing = readSigned(input).takeUnless { it < 0 }?.div(100f)
                val accuracy = readUnsigned(input) / 100f
                TrackPointEntity(
                    rideId = rideId, recordedAt = time,
                    latitude = latitude / 10_000_000.0, longitude = longitude / 10_000_000.0,
                    altitudeMeters = altitude, speedMps = speed,
                    bearingDegrees = bearing, accuracyMeters = accuracy,
                )
            }.also { require(input.read() == -1) { "Trailing GPS telemetry data" } }
        }
    }

    fun encodeMotion(samples: List<MotionSample>): EncodedTelemetry {
        require(samples.isNotEmpty())
        val raw = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MOTION_MAGIC)
                output.writeByte(ENCODING_VERSION)
                writeUnsigned(output, samples.size.toLong())
                var time = 0L
                samples.forEach { sample ->
                    writeSigned(output, sample.timestampMillis - time)
                    listOf(
                        sample.accelerationX, sample.accelerationY, sample.accelerationZ,
                        sample.gyroscopeX, sample.gyroscopeY, sample.gyroscopeZ,
                    ).forEach { writeSigned(output, (it * 1_000).roundToInt().toLong()) }
                    time = sample.timestampMillis
                }
            }
        }.toByteArray()
        return encoded(raw, samples.size, samples.first().timestampMillis, samples.last().timestampMillis)
    }

    fun decodeMotion(payload: ByteArray, checksum: String): List<MotionSample> {
        verify(payload, checksum)
        return DataInputStream(ByteArrayInputStream(inflateLimited(payload))).use { input ->
            require(input.readInt() == MOTION_MAGIC) { "Not FlightLog motion telemetry" }
            require(input.readUnsignedByte() == ENCODING_VERSION) { "Unsupported motion telemetry version" }
            val count = readCount(input)
            var time = 0L
            List(count) {
                time += readSigned(input)
                val values = FloatArray(6) { readSigned(input) / 1_000f }
                MotionSample(time, values[0], values[1], values[2], values[3], values[4], values[5])
            }.also { require(input.read() == -1) { "Trailing motion telemetry data" } }
        }
    }

    fun checksum(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload).joinToString("") { "%02x".format(it) }

    private fun encoded(raw: ByteArray, count: Int, startedAt: Long, endedAt: Long): EncodedTelemetry {
        val payload = ByteArrayOutputStream().also { bytes ->
            DeflaterOutputStream(bytes, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(raw) }
        }.toByteArray()
        return EncodedTelemetry(payload, checksum(payload), count, startedAt, endedAt)
    }

    private fun verify(payload: ByteArray, expected: String) {
        require(expected.length == 64 && MessageDigest.isEqual(checksum(payload).toByteArray(), expected.toByteArray())) {
            "Telemetry checksum mismatch"
        }
    }

    private fun inflateLimited(payload: ByteArray): ByteArray {
        val result = ByteArrayOutputStream()
        InflaterInputStream(ByteArrayInputStream(payload)).use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(result.size() + read <= MAX_DECOMPRESSED_BYTES) { "Telemetry exceeds decompression limit" }
                result.write(buffer, 0, read)
            }
        }
        return result.toByteArray()
    }

    private fun readCount(input: DataInputStream): Int = readUnsigned(input).also {
        require(it in 1..MAX_SAMPLES.toLong()) { "Invalid telemetry sample count" }
    }.toInt()

    private fun writeUnsigned(output: DataOutputStream, value: Long) {
        require(value >= 0)
        var remaining = value
        while (remaining and 0x7f.inv().toLong() != 0L) {
            output.writeByte(((remaining and 0x7f) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        output.writeByte(remaining.toInt())
    }

    private fun readUnsigned(input: DataInputStream): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val byte = input.readUnsignedByte()
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Malformed telemetry varint")
    }

    private fun writeSigned(output: DataOutputStream, value: Long) =
        writeUnsigned(output, (value shl 1) xor (value shr 63))

    private fun readSigned(input: DataInputStream): Long {
        val encoded = readUnsigned(input)
        return (encoded ushr 1) xor -(encoded and 1)
    }
}
