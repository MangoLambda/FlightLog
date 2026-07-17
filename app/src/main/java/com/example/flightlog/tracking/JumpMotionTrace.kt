package com.example.flightlog.tracking

import com.example.flightlog.data.FlightLogDao
import com.example.flightlog.data.JumpEventEntity
import com.example.flightlog.data.JumpMotionTraceEntity

object JumpMotionTrace {
    const val PRE_TAKEOFF_MILLIS = 750L
    const val POST_LANDING_MILLIS = 350L

    fun window(jump: JumpEventEntity): LongRange =
        (jump.takeoffAt - PRE_TAKEOFF_MILLIS)..(jump.landingAt + POST_LANDING_MILLIS)

    fun samples(jump: JumpEventEntity, samples: List<MotionSample>): List<MotionSample> {
        val window = window(jump)
        return samples.asSequence()
            .filter { it.timestampMillis in window }
            .sortedBy { it.timestampMillis }
            .toList()
    }

    fun decode(trace: JumpMotionTraceEntity): List<MotionSample> =
        TelemetryCodec.decodeMotion(trace.payload, trace.checksum)

    fun encode(jumpId: Long, samples: List<MotionSample>): JumpMotionTraceEntity {
        val encoded = TelemetryCodec.encodeMotion(samples)
        return JumpMotionTraceEntity(
            jumpId = jumpId,
            startedAt = encoded.startedAt,
            endedAt = encoded.endedAt,
            encodingVersion = TelemetryCodec.ENCODING_VERSION,
            sampleCount = encoded.sampleCount,
            payload = encoded.payload,
            checksum = encoded.checksum,
        )
    }

    suspend fun loadRaw(dao: FlightLogDao, jump: JumpEventEntity): List<MotionSample> {
        val window = window(jump)
        val decoded = dao.motionChunksInWindow(jump.rideId, window.first, window.last)
            .flatMap { TelemetryCodec.decodeMotion(it.payload, it.checksum) }
        return samples(jump, decoded)
    }
}
