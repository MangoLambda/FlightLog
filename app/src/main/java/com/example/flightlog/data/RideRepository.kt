package com.example.flightlog.data

import com.example.flightlog.domain.JumpStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RideRepository(private val dao: FlightLogDao) {
    val rides: Flow<List<RideEntity>> = dao.observeRides()
    val jumps: Flow<List<JumpEventEntity>> = dao.observeJumps()
    val trails: Flow<List<TrailEntity>> = dao.observeVisibleTrails()
    val sections: Flow<List<TrailSectionEntity>> = dao.observeSections()
    val passes: Flow<List<TrailPassEntity>> = dao.observePasses()
    val efforts: Flow<List<SectionEffortEntity>> = dao.observeEfforts()
    val telemetryBytes: Flow<Long> = dao.observeTelemetryBytes()
    val motionBytes: Flow<Long> = dao.observeMotionBytes()
    val nextMotionExpiry: Flow<Long?> = dao.observeNextMotionExpiry()
    val estimatedProfileBytes: Flow<Long> = dao.observeEstimatedProfileBytes()

    fun trackPoints(rideId: Long) = dao.observeTrackPoints(rideId).map { points ->
        if (points.isNotEmpty()) points else compactedPoints(rideId)
    }
    fun jumps(rideId: Long) = dao.observeJumpsForRide(rideId)
    suspend fun setJumpStatus(id: Long, status: JumpStatus) = dao.setJumpStatus(id, status)
    suspend fun updateJump(jump: JumpEventEntity) = dao.updateJump(jump)
    suspend fun ride(id: Long) = dao.ride(id)
    suspend fun pointSnapshot(id: Long) = compactedPoints(id)
    suspend fun jumpSnapshot(id: Long) = dao.jumps(id)
    suspend fun compactedPoints(id: Long): List<TrackPointEntity> {
        val active = dao.trackPoints(id)
        if (active.isNotEmpty()) return active
        return dao.telemetryChunks(id)
            .filter { it.kind == com.example.flightlog.domain.TelemetryKind.GPS }
            .flatMap { com.example.flightlog.tracking.TelemetryCodec.decodeGps(id, it.payload, it.checksum) }
            .sortedBy { it.recordedAt }
    }

    suspend fun confirmTrail(id: Long, name: String, startMeters: Double, endMeters: Double) {
        val trail = dao.allTrails().firstOrNull { it.id == id } ?: return
        if (startMeters < 0.0 || startMeters >= endMeters || endMeters > trail.lengthMeters) return
        dao.updateTrail(trail.copy(
            name = name.trim().take(80).ifBlank { trail.name },
            startMeters = startMeters,
            endMeters = endMeters,
            state = com.example.flightlog.domain.TrailState.CONFIRMED,
            updatedAt = System.currentTimeMillis(),
        ))
        dao.sections(id).firstOrNull { it.kind == com.example.flightlog.domain.SectionKind.WHOLE_TRAIL }?.let {
            dao.updateSection(it.copy(startMeters = startMeters, endMeters = endMeters))
        }
    }

    suspend fun confirmSection(id: Long) {
        val section = dao.allSections().firstOrNull { it.id == id } ?: return
        dao.updateSection(section.copy(state = com.example.flightlog.domain.SectionState.CONFIRMED))
    }

    suspend fun updateSection(id: Long, name: String, startMeters: Double, endMeters: Double) {
        val section = dao.allSections().firstOrNull { it.id == id } ?: return
        val trail = dao.allTrails().firstOrNull { it.id == section.trailId } ?: return
        if (startMeters < trail.startMeters || startMeters >= endMeters || endMeters > trail.endMeters) return
        dao.updateSection(section.copy(name = name.trim().take(80).ifBlank { section.name }, startMeters = startMeters, endMeters = endMeters))
    }
}
