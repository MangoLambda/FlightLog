package com.example.flightlog.tracking

import com.example.flightlog.data.FlightLogDao
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.JumpMotionTraceEntity

object JumpMotionTrace {
    const val PRE_TAKEOFF_MILLIS = 10_000L
    const val POST_LANDING_MILLIS = 10_000L

    fun window(jump: JumpEventEntity): LongRange =
        (jump.takeoffAt - PRE_TAKEOFF_MILLIS)..(jump.landingAt + POST_LANDING_MILLIS)

    fun samples(jump: JumpEventEntity, telemetry: MotionTelemetry): MotionTelemetry {
        val window = window(jump)
        return telemetry.copy(
            accelerometer = telemetry.accelerometer.filter { it.timestampMillis in window }.sortedBy { it.timestampMillis },
            gyroscope = telemetry.gyroscope.filter { it.timestampMillis in window }.sortedBy { it.timestampMillis },
            orientation = telemetry.orientation.filter { it.timestampMillis in window }.sortedBy { it.timestampMillis },
        )
    }

    fun merge(jump: JumpEventEntity, telemetry: Iterable<MotionTelemetry>): MotionTelemetry {
        val values = telemetry.filter { it.hasUsableSamples }
        if (values.isEmpty()) return MotionTelemetry.EMPTY
        return samples(jump, MotionTelemetry(
            encodingVersion = values.maxOf { it.encodingVersion },
            orientationSource = values.firstOrNull { it.orientationSource != OrientationSource.NONE }?.orientationSource
                ?: OrientationSource.NONE,
            accelerometer = values.flatMap { it.accelerometer }.distinctBy { it.timestampMillis }.sortedBy { it.timestampMillis },
            gyroscope = values.flatMap { it.gyroscope }.distinctBy { it.timestampMillis }.sortedBy { it.timestampMillis },
            orientation = values.flatMap { it.orientation }.distinctBy { it.timestampMillis }.sortedBy { it.timestampMillis },
        ))
    }

    fun decode(trace: JumpMotionTraceEntity): MotionTelemetry =
        TelemetryCodec.decodeMotion(trace.payload, trace.checksum)

    fun encode(jumpId: Long, telemetry: MotionTelemetry): JumpMotionTraceEntity {
        val encoded = TelemetryCodec.encodeMotion(telemetry)
        return JumpMotionTraceEntity(
            jumpId = jumpId,
            startedAt = encoded.startedAt,
            endedAt = encoded.endedAt,
            encodingVersion = encoded.encodingVersion,
            sampleCount = encoded.sampleCount,
            payload = encoded.payload,
            checksum = encoded.checksum,
        )
    }

    suspend fun loadRaw(dao: FlightLogDao, jump: JumpEventEntity): MotionTelemetry {
        val window = window(jump)
        val decoded = dao.motionChunksInWindow(jump.rideId, window.first, window.last)
            .map { TelemetryCodec.decodeMotion(it.payload, it.checksum) }
        return samples(jump, MotionTelemetry.merge(decoded))
    }
}
