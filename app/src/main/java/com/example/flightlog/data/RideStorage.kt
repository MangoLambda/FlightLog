package com.example.flightlog.data

data class RideStorageComponents(
    val rideId: Long,
    val trackPointCount: Long,
    val telemetryChunkCount: Long,
    val telemetryPayloadBytes: Long,
    val jumpCount: Long,
    val jumpTraceCount: Long,
    val jumpTracePayloadBytes: Long,
    val spatialProfileCount: Long,
    val stopEventCount: Long,
    val trailPassCount: Long,
    val sectionEffortCount: Long,
    val trailStopObservationCount: Long,
)

internal object RideStorageEstimate {
    private const val RIDE_BYTES = 128L
    private const val TRACK_POINT_BYTES = 72L
    private const val TELEMETRY_CHUNK_BYTES = 128L
    private const val JUMP_EVENT_BYTES = 128L
    private const val JUMP_TRACE_BYTES = 96L
    private const val SPATIAL_PROFILE_BYTES = 112L
    private const val STOP_EVENT_BYTES = 96L
    private const val TRAIL_PASS_BYTES = 144L
    private const val SECTION_EFFORT_BYTES = 144L
    private const val TRAIL_STOP_OBSERVATION_BYTES = 96L

    fun bytes(components: RideStorageComponents): Long = RIDE_BYTES +
        components.trackPointCount * TRACK_POINT_BYTES +
        components.telemetryChunkCount * TELEMETRY_CHUNK_BYTES +
        components.telemetryPayloadBytes +
        components.jumpCount * JUMP_EVENT_BYTES +
        components.jumpTraceCount * JUMP_TRACE_BYTES +
        components.jumpTracePayloadBytes +
        components.spatialProfileCount * SPATIAL_PROFILE_BYTES +
        components.stopEventCount * STOP_EVENT_BYTES +
        components.trailPassCount * TRAIL_PASS_BYTES +
        components.sectionEffortCount * SECTION_EFFORT_BYTES +
        components.trailStopObservationCount * TRAIL_STOP_OBSERVATION_BYTES
}
