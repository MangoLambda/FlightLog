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
import kotlin.math.sqrt

data class EncodedTelemetry(
    val encodingVersion: Int,
    val payload: ByteArray,
    val checksum: String,
    val sampleCount: Int,
    val startedAt: Long,
    val endedAt: Long,
)

/** Acceleration-paced compatibility frame used by trail roughness and the acceleration chart. */
data class MotionSample(
    val timestampMillis: Long,
    val accelerationX: Float,
    val accelerationY: Float,
    val accelerationZ: Float,
    val gyroscopeX: Float,
    val gyroscopeY: Float,
    val gyroscopeZ: Float,
)

data class Vector3Sample(
    val timestampMillis: Long,
    val x: Float,
    val y: Float,
    val z: Float,
)

data class RotationSample(
    val timestampMillis: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float,
)

data class PressureSample(val timestampMillis: Long, val pressureHpa: Float)

enum class OrientationSource { NONE, GAME_ROTATION_VECTOR, ROTATION_VECTOR }

data class MotionTelemetry(
    val encodingVersion: Int = TelemetryCodec.MOTION_ENCODING_VERSION,
    val orientationSource: OrientationSource = OrientationSource.NONE,
    val accelerometer: List<Vector3Sample> = emptyList(),
    val gyroscope: List<Vector3Sample> = emptyList(),
    val orientation: List<RotationSample> = emptyList(),
    val pressure: List<PressureSample> = emptyList(),
) {
    val sampleCount: Int get() = if (encodingVersion == TelemetryCodec.LEGACY_MOTION_ENCODING_VERSION) {
        accelerometer.size
    } else {
        accelerometer.size + gyroscope.size + orientation.size + pressure.size
    }
    val startedAt: Long? get() = allTimestamps().minOrNull()
    val endedAt: Long? get() = allTimestamps().maxOrNull()

    fun accelerationFrames(): List<MotionSample> {
        val gyro = gyroscope.sortedBy { it.timestampMillis }
        var gyroIndex = -1
        return accelerometer.sortedBy { it.timestampMillis }.map { acceleration ->
            while (gyroIndex + 1 < gyro.size && gyro[gyroIndex + 1].timestampMillis <= acceleration.timestampMillis) gyroIndex++
            val nearest = gyro.getOrNull(gyroIndex) ?: gyro.firstOrNull()
            MotionSample(
                acceleration.timestampMillis,
                acceleration.x,
                acceleration.y,
                acceleration.z,
                nearest?.x ?: 0f,
                nearest?.y ?: 0f,
                nearest?.z ?: 0f,
            )
        }
    }

    private fun allTimestamps(): Sequence<Long> = sequence {
        accelerometer.forEach { yield(it.timestampMillis) }
        gyroscope.forEach { yield(it.timestampMillis) }
        orientation.forEach { yield(it.timestampMillis) }
        pressure.forEach { yield(it.timestampMillis) }
    }

    companion object {
        val EMPTY = MotionTelemetry()

        fun merge(chunks: Iterable<MotionTelemetry>): MotionTelemetry {
            val values = chunks.toList()
            if (values.isEmpty()) return EMPTY
            return MotionTelemetry(
                encodingVersion = values.maxOf { it.encodingVersion },
                orientationSource = values.firstOrNull { it.orientationSource != OrientationSource.NONE }?.orientationSource
                    ?: OrientationSource.NONE,
                accelerometer = values.flatMap { it.accelerometer }.sortedBy { it.timestampMillis },
                gyroscope = values.flatMap { it.gyroscope }.sortedBy { it.timestampMillis },
                orientation = values.flatMap { it.orientation }.sortedBy { it.timestampMillis },
                pressure = values.flatMap { it.pressure }.sortedBy { it.timestampMillis },
            )
        }
    }
}

/** Versioned, bounded binary codecs used before telemetry is persisted as a Room BLOB. */
object TelemetryCodec {
    const val GPS_ENCODING_VERSION = 1
    const val LEGACY_MOTION_ENCODING_VERSION = 1
    const val MOTION_ENCODING_VERSION = 2
    private const val GPS_MAGIC = 0x464C4750 // FLGP
    private const val MOTION_MAGIC = 0x464C4D4F // FLMO
    private const val MAX_DECOMPRESSED_BYTES = 128 * 1024 * 1024
    private const val MAX_SAMPLES = 2_000_000
    private const val VECTOR_SCALE = 1_000.0
    private const val ROTATION_SCALE = 1_000_000.0
    private const val PRESSURE_SCALE = 10_000.0

    fun encodeGps(points: List<TrackPointEntity>): EncodedTelemetry {
        require(points.isNotEmpty())
        val raw = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(GPS_MAGIC)
                output.writeByte(GPS_ENCODING_VERSION)
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
        return encoded(raw, GPS_ENCODING_VERSION, points.size, points.first().recordedAt, points.last().recordedAt)
    }

    fun decodeGps(rideId: Long, payload: ByteArray, checksum: String): List<TrackPointEntity> {
        verify(payload, checksum)
        return DataInputStream(ByteArrayInputStream(inflateLimited(payload))).use { input ->
            require(input.readInt() == GPS_MAGIC) { "Not FlightLog GPS telemetry" }
            require(input.readUnsignedByte() == GPS_ENCODING_VERSION) { "Unsupported GPS telemetry version" }
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

    fun encodeMotion(telemetry: MotionTelemetry): EncodedTelemetry {
        val accelerometer = telemetry.accelerometer.sortedBy { it.timestampMillis }
        val gyroscope = telemetry.gyroscope.sortedBy { it.timestampMillis }
        val orientation = telemetry.orientation.sortedBy { it.timestampMillis }
        val pressure = telemetry.pressure.sortedBy { it.timestampMillis }
        val sampleCount = accelerometer.size + gyroscope.size + orientation.size + pressure.size
        require(sampleCount in 1..MAX_SAMPLES)
        val raw = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MOTION_MAGIC)
                output.writeByte(MOTION_ENCODING_VERSION)
                output.writeByte(telemetry.orientationSource.ordinal)
                writeVectorChannel(output, accelerometer)
                writeVectorChannel(output, gyroscope)
                writeRotationChannel(output, orientation)
                writePressureChannel(output, pressure)
            }
        }.toByteArray()
        return encoded(
            raw = raw,
            version = MOTION_ENCODING_VERSION,
            count = sampleCount,
            startedAt = telemetry.startedAt ?: error("Motion telemetry is empty"),
            endedAt = telemetry.endedAt ?: error("Motion telemetry is empty"),
        )
    }

    /** Test/import compatibility encoder for the original acceleration-paced format. */
    fun encodeLegacyMotion(samples: List<MotionSample>): EncodedTelemetry {
        require(samples.isNotEmpty())
        val sorted = samples.sortedBy { it.timestampMillis }
        val raw = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MOTION_MAGIC)
                output.writeByte(LEGACY_MOTION_ENCODING_VERSION)
                writeUnsigned(output, sorted.size.toLong())
                var time = 0L
                sorted.forEach { sample ->
                    writeSigned(output, sample.timestampMillis - time)
                    listOf(
                        sample.accelerationX, sample.accelerationY, sample.accelerationZ,
                        sample.gyroscopeX, sample.gyroscopeY, sample.gyroscopeZ,
                    ).forEach { writeSigned(output, (it * VECTOR_SCALE).roundToInt().toLong()) }
                    time = sample.timestampMillis
                }
            }
        }.toByteArray()
        return encoded(raw, LEGACY_MOTION_ENCODING_VERSION, sorted.size, sorted.first().timestampMillis, sorted.last().timestampMillis)
    }

    fun decodeMotion(payload: ByteArray, checksum: String): MotionTelemetry {
        verify(payload, checksum)
        return DataInputStream(ByteArrayInputStream(inflateLimited(payload))).use { input ->
            require(input.readInt() == MOTION_MAGIC) { "Not FlightLog motion telemetry" }
            when (val version = input.readUnsignedByte()) {
                LEGACY_MOTION_ENCODING_VERSION -> decodeLegacyMotion(input)
                MOTION_ENCODING_VERSION -> decodeMotionV2(input)
                else -> error("Unsupported motion telemetry version $version")
            }.also { require(input.read() == -1) { "Trailing motion telemetry data" } }
        }
    }

    fun checksum(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload).joinToString("") { "%02x".format(it) }

    private fun decodeLegacyMotion(input: DataInputStream): MotionTelemetry {
        val count = readCount(input)
        var time = 0L
        val acceleration = ArrayList<Vector3Sample>(count)
        val gyroscope = ArrayList<Vector3Sample>(count)
        repeat(count) {
            time = nextTimestamp(input, time)
            val values = FloatArray(6) { (readSigned(input) / VECTOR_SCALE).toFloat() }
            acceleration += Vector3Sample(time, values[0], values[1], values[2])
            gyroscope += Vector3Sample(time, values[3], values[4], values[5])
        }
        return MotionTelemetry(LEGACY_MOTION_ENCODING_VERSION, accelerometer = acceleration, gyroscope = gyroscope)
    }

    private fun decodeMotionV2(input: DataInputStream): MotionTelemetry {
        val sourceOrdinal = input.readUnsignedByte()
        require(sourceOrdinal in OrientationSource.entries.indices) { "Invalid orientation source" }
        val accelerometer = readVectorChannel(input, 1_000f, MAX_SAMPLES)
        val gyroscope = readVectorChannel(input, 100f, MAX_SAMPLES - accelerometer.size)
        val orientation = readRotationChannel(input, MAX_SAMPLES - accelerometer.size - gyroscope.size)
        val pressure = readPressureChannel(
            input,
            MAX_SAMPLES - accelerometer.size - gyroscope.size - orientation.size,
        )
        val telemetry = MotionTelemetry(
            encodingVersion = MOTION_ENCODING_VERSION,
            orientationSource = OrientationSource.entries[sourceOrdinal],
            accelerometer = accelerometer,
            gyroscope = gyroscope,
            orientation = orientation,
            pressure = pressure,
        )
        require(telemetry.sampleCount in 1..MAX_SAMPLES) { "Invalid telemetry sample count" }
        return telemetry
    }

    private fun writeVectorChannel(output: DataOutputStream, samples: List<Vector3Sample>) {
        writeUnsigned(output, samples.size.toLong())
        var time = 0L
        samples.forEach { sample ->
            require(sample.x.isFinite() && sample.y.isFinite() && sample.z.isFinite())
            writeSigned(output, sample.timestampMillis - time)
            listOf(sample.x, sample.y, sample.z).forEach { writeSigned(output, (it * VECTOR_SCALE).roundToInt().toLong()) }
            time = sample.timestampMillis
        }
    }

    private fun readVectorChannel(
        input: DataInputStream,
        maximumMagnitude: Float,
        maximumCount: Int,
    ): List<Vector3Sample> {
        val count = readOptionalCount(input, maximumCount)
        var time = 0L
        return List(count) {
            time = nextTimestamp(input, time)
            val values = FloatArray(3) { (readSigned(input) / VECTOR_SCALE).toFloat() }
            require(values.all { it.isFinite() && it in -maximumMagnitude..maximumMagnitude }) { "Invalid motion vector" }
            Vector3Sample(time, values[0], values[1], values[2])
        }
    }

    private fun writeRotationChannel(output: DataOutputStream, samples: List<RotationSample>) {
        writeUnsigned(output, samples.size.toLong())
        var time = 0L
        samples.forEach { sample ->
            require(listOf(sample.x, sample.y, sample.z, sample.w).all(Float::isFinite))
            writeSigned(output, sample.timestampMillis - time)
            listOf(sample.x, sample.y, sample.z, sample.w).forEach {
                writeSigned(output, (it * ROTATION_SCALE).roundToInt().toLong())
            }
            time = sample.timestampMillis
        }
    }

    private fun readRotationChannel(input: DataInputStream, maximumCount: Int): List<RotationSample> {
        val count = readOptionalCount(input, maximumCount)
        var time = 0L
        return List(count) {
            time = nextTimestamp(input, time)
            val values = FloatArray(4) { (readSigned(input) / ROTATION_SCALE).toFloat() }
            require(values.all { it.isFinite() && it in -1.1f..1.1f }) { "Invalid rotation vector" }
            val norm = sqrt(values.sumOf { value -> value.toDouble() * value })
            require(norm in 0.5..1.5) { "Invalid rotation quaternion" }
            RotationSample(time, values[0], values[1], values[2], values[3])
        }
    }

    private fun writePressureChannel(output: DataOutputStream, samples: List<PressureSample>) {
        writeUnsigned(output, samples.size.toLong())
        var time = 0L
        samples.forEach { sample ->
            require(sample.pressureHpa.isFinite() && sample.pressureHpa in 300f..1_100f)
            writeSigned(output, sample.timestampMillis - time)
            writeUnsigned(output, (sample.pressureHpa * PRESSURE_SCALE).roundToInt().toLong())
            time = sample.timestampMillis
        }
    }

    private fun readPressureChannel(input: DataInputStream, maximumCount: Int): List<PressureSample> {
        val count = readOptionalCount(input, maximumCount)
        var time = 0L
        return List(count) {
            time = nextTimestamp(input, time)
            val pressure = (readUnsigned(input) / PRESSURE_SCALE).toFloat()
            require(pressure.isFinite() && pressure in 300f..1_100f) { "Invalid pressure sample" }
            PressureSample(time, pressure)
        }
    }

    private fun encoded(raw: ByteArray, version: Int, count: Int, startedAt: Long, endedAt: Long): EncodedTelemetry {
        val payload = ByteArrayOutputStream().also { bytes ->
            DeflaterOutputStream(bytes, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(raw) }
        }.toByteArray()
        return EncodedTelemetry(version, payload, checksum(payload), count, startedAt, endedAt)
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

    private fun readOptionalCount(input: DataInputStream, maximumCount: Int): Int = readUnsigned(input).also {
        require(it in 0..maximumCount.toLong()) { "Invalid telemetry sample count" }
    }.toInt()

    private fun nextTimestamp(input: DataInputStream, previous: Long): Long {
        val delta = readSigned(input)
        require(delta >= 0 && previous <= Long.MAX_VALUE - delta) { "Invalid telemetry timestamp" }
        return previous + delta
    }

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
