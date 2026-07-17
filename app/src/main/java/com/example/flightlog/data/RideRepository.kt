package com.example.flightlog.data

import com.example.flightlog.domain.JumpStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import com.example.flightlog.tracking.JumpMotionTrace
import com.example.flightlog.tracking.MotionSample
import com.example.flightlog.tracking.TrailAnalysis
import androidx.room.withTransaction
import com.example.flightlog.domain.PauseZoneState
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.TrailState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RideRepository(private val database: FlightLogDatabase) {
    private val dao = database.dao()
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
    fun jumpMotion(jump: JumpEventEntity): Flow<List<MotionSample>> = dao.observeJumpMotionTrace(jump.id)
        .map { trace -> trace?.let(JumpMotionTrace::decode) ?: JumpMotionTrace.loadRaw(dao, jump) }
        .catch { emit(emptyList()) }
    fun stops(rideId: Long) = dao.observeStopEventsForRide(rideId)
    suspend fun setJumpStatus(id: Long, status: JumpStatus) = dao.setJumpStatus(id, status)
    suspend fun updateJump(jump: JumpEventEntity) = dao.updateJump(jump)
    suspend fun ride(id: Long) = dao.ride(id)
    suspend fun pointSnapshot(id: Long) = compactedPoints(id)
    suspend fun spatialProfiles(rideId: Long) = dao.spatialProfiles(rideId)
    suspend fun trailPasses(trailId: Long) = dao.passes(trailId)
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

    suspend fun previewTrailDefinition(draft: TrailDefinitionDraft): TrailEditImpact {
        val validated = validateTrailDraft(draft)
        val trail = validated.trailId?.let { id -> dao.allTrails().firstOrNull { it.id == id } }
        require(validated.trailId == null || trail != null) { "Trail no longer exists" }
        val rides = dao.allRides()
        val profilesByRide = rides.associate { it.id to dao.spatialProfiles(it.id) }
        val (matched, complete) = withContext(Dispatchers.Default) {
            projectedTrailCoverage(rides, profilesByRide, validated)
        }
        val priorPasses = trail?.let { dao.passes(it.id) }.orEmpty()
        val remapped = if (trail == null) {
            RemappedTrailItems(emptyList(), emptyList())
        } else {
            remapTrailItems(
                validated,
                trail.canonicalRideId,
                profilesByRide[trail.canonicalRideId].orEmpty(),
                profilesByRide[validated.referenceRideId].orEmpty(),
                dao.sections(trail.id),
                dao.pauseZones(trail.id),
            )
        }
        return TrailEditImpact(
            draft = validated,
            previousMatchedRideCount = priorPasses.map { it.rideId }.distinct().size,
            previousCompleteRideCount = priorPasses.filter { it.completeCoverage }.map { it.rideId }.distinct().size,
            matchedRideCount = matched,
            completeRideCount = complete,
            preservedItems = remapped.preserved,
            removedItems = remapped.removed,
        )
    }

    suspend fun saveTrailDefinition(draft: TrailDefinitionDraft, confirmedRemovalKeys: Set<String>): Long {
        val impact = previewTrailDefinition(draft)
        val requiredRemovals = impact.removedItems.mapTo(hashSetOf()) { it.key }
        require(requiredRemovals.all(confirmedRemovalKeys::contains)) { "Review affected custom items before saving" }
        val canonical = dao.spatialProfiles(impact.draft.referenceRideId)
        require(canonical.size >= 2) { "The reference ride route is no longer available" }
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val trailId = impact.draft.trailId
            if (trailId == null) {
                val id = dao.insertTrail(TrailEntity(
                    name = impact.draft.name,
                    state = TrailState.CONFIRMED,
                    canonicalRideId = impact.draft.referenceRideId,
                    lengthMeters = canonical.last().distanceMeters,
                    startMeters = impact.draft.startMeters,
                    endMeters = impact.draft.endMeters,
                    supportCount = impact.matchedRideCount.coerceAtLeast(1),
                    updatedAt = now,
                ))
                TrailAnalysis.suggestedSections(id, canonical.filter {
                    it.distanceMeters in impact.draft.startMeters..impact.draft.endMeters
                }).forEach { dao.insertSection(it) }
                id
            } else {
                val trail = dao.allTrails().firstOrNull { it.id == trailId }
                    ?: error("Trail no longer exists")
                dao.updateTrail(trail.copy(
                    name = impact.draft.name,
                    state = TrailState.CONFIRMED,
                    canonicalRideId = impact.draft.referenceRideId,
                    lengthMeters = canonical.last().distanceMeters,
                    startMeters = impact.draft.startMeters,
                    endMeters = impact.draft.endMeters,
                    supportCount = impact.matchedRideCount.coerceAtLeast(1),
                    updatedAt = now,
                ))
                val sections = dao.sections(trailId)
                val pauseZones = dao.pauseZones(trailId)
                sections.firstOrNull { it.kind == SectionKind.WHOLE_TRAIL }?.let {
                    dao.updateSection(it.copy(startMeters = impact.draft.startMeters, endMeters = impact.draft.endMeters))
                } ?: dao.insertSection(TrailSectionEntity(
                    trailId = trailId,
                    name = "Whole trail",
                    kind = SectionKind.WHOLE_TRAIL,
                    state = com.example.flightlog.domain.SectionState.CONFIRMED,
                    startMeters = impact.draft.startMeters,
                    endMeters = impact.draft.endMeters,
                ))
                impact.preservedItems.forEach { item ->
                    when (item.kind) {
                        TrailCustomItemKind.SECTION -> sections.firstOrNull { "section:${it.id}" == item.key }?.let {
                            dao.updateSection(it.copy(startMeters = item.startMeters, endMeters = item.endMeters))
                        }
                        TrailCustomItemKind.PAUSE_ZONE -> pauseZones.firstOrNull { "pause:${it.id}" == item.key }?.let {
                                dao.updatePauseZone(it.copy(
                                    startMeters = item.startMeters,
                                    endMeters = item.endMeters,
                                    updatedAt = now,
                                ))
                            }
                    }
                }
                impact.removedItems.forEach { item ->
                    val id = item.key.substringAfter(':').toLong()
                    when (item.kind) {
                        TrailCustomItemKind.SECTION -> dao.deleteSection(id)
                        TrailCustomItemKind.PAUSE_ZONE -> dao.deletePauseZone(id)
                    }
                }
                dao.deleteAutoSections(trailId)
                dao.deleteGeneratedPauseZones(trailId)
                TrailAnalysis.suggestedSections(trailId, canonical.filter {
                    it.distanceMeters in impact.draft.startMeters..impact.draft.endMeters
                }).filter { it.kind != SectionKind.WHOLE_TRAIL }.forEach { dao.insertSection(it) }
                trailId
            }
        }
    }

    private suspend fun validateTrailDraft(draft: TrailDefinitionDraft): TrailDefinitionDraft {
        val profiles = dao.spatialProfiles(draft.referenceRideId).sortedBy { it.distanceMeters }
        require(profiles.size >= 2) { "This ride has not finished processing its GPS route" }
        val start = draft.startMeters
        val end = draft.endMeters
        require(start.isFinite() && end.isFinite() && start >= profiles.first().distanceMeters &&
            end <= profiles.last().distanceMeters && end - start >= 10.0
        ) { "Choose trail boundaries at least 10 m apart" }
        return draft.copy(name = draft.name.trim().take(80).ifBlank { "New trail" })
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
