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
    val pauseZones: Flow<List<TrailPauseZoneEntity>> = dao.observePauseZones()
    val stopObservations: Flow<List<TrailStopObservationEntity>> = dao.observeStopObservations()
    val telemetryBytes: Flow<Long> = dao.observeTelemetryBytes()
    val motionBytes: Flow<Long> = dao.observeMotionBytes()
    val nextMotionExpiry: Flow<Long?> = dao.observeNextMotionExpiry()
    val estimatedProfileBytes: Flow<Long> = dao.observeEstimatedProfileBytes()

    fun trackPoints(rideId: Long) = dao.observeTrackPoints(rideId).map { points ->
        if (points.isNotEmpty()) points else compactedPoints(rideId)
    }
    fun jumps(rideId: Long) = dao.observeJumpsForRide(rideId)
    fun stops(rideId: Long) = dao.observeStopEventsForRide(rideId)
    suspend fun setJumpStatus(id: Long, status: JumpStatus) = dao.setJumpStatus(id, status)
    suspend fun updateJump(jump: JumpEventEntity) = dao.updateJump(jump)
    suspend fun ride(id: Long) = dao.ride(id)
    suspend fun pointSnapshot(id: Long) = compactedPoints(id)
    suspend fun spatialProfiles(rideId: Long) = dao.spatialProfiles(rideId)
    fun observeSpatialProfiles(rideId: Long) = dao.observeSpatialProfiles(rideId)
    suspend fun jumpSnapshot(id: Long) = dao.jumps(id)
    suspend fun deleteRide(id: Long): Boolean = dao.deleteFinishedRide(id) > 0

    suspend fun updatePauseZone(id: Long, name: String, startMeters: Double, endMeters: Double): Long? {
        val zone = dao.allPauseZones().firstOrNull { it.id == id } ?: return null
        val trail = dao.allTrails().firstOrNull { it.id == zone.trailId } ?: return null
        if (startMeters < trail.startMeters || endMeters > trail.endMeters || endMeters - startMeters < 5.0) return null
        dao.updatePauseZone(zone.copy(
            name = name.trim().take(80).ifBlank { zone.name },
            startMeters = startMeters,
            endMeters = endMeters,
            state = com.example.flightlog.domain.PauseZoneState.USER_LOCKED,
            updatedAt = System.currentTimeMillis(),
        ))
        return trail.id
    }

    suspend fun addPauseZone(trailId: Long, startMeters: Double, endMeters: Double): Long? {
        val trail = dao.allTrails().firstOrNull { it.id == trailId } ?: return null
        if (startMeters < trail.startMeters || endMeters > trail.endMeters || endMeters - startMeters < 5.0) return null
        dao.insertPauseZone(TrailPauseZoneEntity(
            trailId = trailId,
            name = "Pause ${dao.pauseZones(trailId).size + 1}",
            startMeters = startMeters,
            endMeters = endMeters,
            state = com.example.flightlog.domain.PauseZoneState.USER_LOCKED,
            supportCount = 0,
            eligiblePassCount = 0,
            confidence = 100,
            medianPauseMillis = 0,
        ))
        return trailId
    }

    suspend fun setPauseZoneDismissed(id: Long, dismissed: Boolean): Long? {
        val zone = dao.allPauseZones().firstOrNull { it.id == id } ?: return null
        dao.updatePauseZone(zone.copy(
            state = if (dismissed) com.example.flightlog.domain.PauseZoneState.DISMISSED
            else com.example.flightlog.domain.PauseZoneState.USER_LOCKED,
            updatedAt = System.currentTimeMillis(),
        ))
        return zone.trailId
    }
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
        val minimum = if (section.kind == com.example.flightlog.domain.SectionKind.WHOLE_TRAIL) 0.0 else trail.startMeters
        val maximum = if (section.kind == com.example.flightlog.domain.SectionKind.WHOLE_TRAIL) trail.lengthMeters else trail.endMeters
        if (startMeters < minimum || startMeters >= endMeters || endMeters > maximum) return
        dao.updateSection(section.copy(name = name.trim().take(80).ifBlank { section.name }, startMeters = startMeters, endMeters = endMeters))
        if (section.kind == com.example.flightlog.domain.SectionKind.WHOLE_TRAIL) {
            dao.updateTrail(trail.copy(startMeters = startMeters, endMeters = endMeters, updatedAt = System.currentTimeMillis()))
        }
    }
}
