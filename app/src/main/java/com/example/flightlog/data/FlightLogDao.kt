package com.example.flightlog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.FlightKind
import com.example.flightlog.domain.RideState
import kotlinx.coroutines.flow.Flow

@Dao
interface FlightLogDao {
    @Insert suspend fun insertRide(ride: RideEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRideIfAbsent(ride: RideEntity): Long
    @Update suspend fun updateRide(ride: RideEntity)
    @Insert suspend fun insertTrackPoint(point: TrackPointEntity): Long
    @Insert suspend fun insertJump(jump: JumpEventEntity): Long
    @Insert suspend fun insertPhysicalFeature(feature: PhysicalFeatureEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFeatureObservation(observation: FeatureObservationEntity): Long
    @Update suspend fun updatePhysicalFeature(feature: PhysicalFeatureEntity)
    @Update suspend fun updateFeatureObservation(observation: FeatureObservationEntity)
    @Update suspend fun updateJump(jump: JumpEventEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertJumpMotionTrace(trace: JumpMotionTraceEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTelemetryChunk(chunk: TelemetryChunkEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSpatialProfiles(profiles: List<SpatialProfileEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStopEvents(events: List<StopEventEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertStopEventIfAbsent(event: StopEventEntity): Long
    @Insert suspend fun insertTrail(trail: TrailEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTrailIfAbsent(trail: TrailEntity): Long
    @Update suspend fun updateTrail(trail: TrailEntity)
    @Insert suspend fun insertSection(section: TrailSectionEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSectionIfAbsent(section: TrailSectionEntity): Long
    @Update suspend fun updateSection(section: TrailSectionEntity)
    @Insert suspend fun insertPauseZone(zone: TrailPauseZoneEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPauseZoneIfAbsent(zone: TrailPauseZoneEntity): Long
    @Update suspend fun updatePauseZone(zone: TrailPauseZoneEntity)
    @Insert suspend fun insertPass(pass: TrailPassEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertManualTrailAssignment(assignment: ManualTrailAssignmentEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPassIfAbsent(pass: TrailPassEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEfforts(efforts: List<SectionEffortEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEffortIfAbsent(effort: SectionEffortEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStopObservations(observations: List<TrailStopObservationEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertStopObservationIfAbsent(observation: TrailStopObservationEntity): Long

    @Query("SELECT * FROM rides ORDER BY startedAt DESC")
    fun observeRides(): Flow<List<RideEntity>>

    @Query("SELECT * FROM jump_events ORDER BY takeoffAt DESC")
    fun observeJumps(): Flow<List<JumpEventEntity>>

    @Query("SELECT * FROM physical_features ORDER BY updatedAt DESC")
    fun observePhysicalFeatures(): Flow<List<PhysicalFeatureEntity>>

    @Query("SELECT * FROM feature_observations ORDER BY createdAt DESC")
    fun observeFeatureObservations(): Flow<List<FeatureObservationEntity>>

    @Query("SELECT * FROM jump_events WHERE rideId = :rideId ORDER BY takeoffAt")
    fun observeJumpsForRide(rideId: Long): Flow<List<JumpEventEntity>>

    @Query("SELECT * FROM jump_motion_traces WHERE jumpId = :jumpId")
    fun observeJumpMotionTrace(jumpId: Long): Flow<JumpMotionTraceEntity?>

    @Query("SELECT * FROM track_points WHERE rideId = :rideId ORDER BY recordedAt")
    fun observeTrackPoints(rideId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM stop_events WHERE rideId = :rideId ORDER BY startedAt")
    fun observeStopEventsForRide(rideId: Long): Flow<List<StopEventEntity>>

    @Query("SELECT * FROM trails WHERE supportCount >= 2 OR state = 'CONFIRMED' ORDER BY updatedAt DESC")
    fun observeVisibleTrails(): Flow<List<TrailEntity>>

    @Query("SELECT * FROM trails ORDER BY name COLLATE NOCASE, id")
    fun observeAllTrails(): Flow<List<TrailEntity>>

    @Query("SELECT * FROM trail_sections ORDER BY trailId, startMeters")
    fun observeSections(): Flow<List<TrailSectionEntity>>

    @Query("SELECT * FROM trail_passes ORDER BY startedAt DESC")
    fun observePasses(): Flow<List<TrailPassEntity>>

    @Query("SELECT * FROM manual_trail_assignments ORDER BY updatedAt DESC")
    fun observeManualTrailAssignments(): Flow<List<ManualTrailAssignmentEntity>>

    @Query("SELECT * FROM section_efforts ORDER BY id DESC")
    fun observeEfforts(): Flow<List<SectionEffortEntity>>

    @Query("SELECT * FROM trail_pause_zones ORDER BY trailId, startMeters")
    fun observePauseZones(): Flow<List<TrailPauseZoneEntity>>

    @Query("SELECT * FROM trail_stop_observations ORDER BY trailId, distanceMeters")
    fun observeStopObservations(): Flow<List<TrailStopObservationEntity>>

    @Query("SELECT * FROM track_points WHERE rideId = :rideId ORDER BY recordedAt")
    suspend fun trackPoints(rideId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM jump_events WHERE rideId = :rideId ORDER BY takeoffAt")
    suspend fun jumps(rideId: Long): List<JumpEventEntity>

    @Query("SELECT * FROM jump_events WHERE rideId = :rideId AND takeoffAt = :takeoffAt LIMIT 1")
    suspend fun jumpByTakeoff(rideId: Long, takeoffAt: Long): JumpEventEntity?

    @Query("SELECT * FROM jump_motion_traces WHERE jumpId = :jumpId")
    suspend fun jumpMotionTrace(jumpId: Long): JumpMotionTraceEntity?

    @Query("SELECT * FROM jump_motion_traces ORDER BY jumpId")
    suspend fun allJumpMotionTraces(): List<JumpMotionTraceEntity>

    @Query("SELECT jump_events.* FROM jump_events LEFT JOIN jump_motion_traces ON jump_motion_traces.jumpId = jump_events.id WHERE jump_motion_traces.jumpId IS NULL AND EXISTS (SELECT 1 FROM telemetry_chunks WHERE telemetry_chunks.rideId = jump_events.rideId AND telemetry_chunks.kind = 'MOTION' AND telemetry_chunks.endedAt >= jump_events.takeoffAt - 750 AND telemetry_chunks.startedAt <= jump_events.landingAt + 350) ORDER BY jump_events.rideId, jump_events.takeoffAt")
    suspend fun jumpsMissingMotionTrace(): List<JumpEventEntity>

    @Query("SELECT * FROM telemetry_chunks WHERE rideId = :rideId ORDER BY startedAt")
    suspend fun telemetryChunks(rideId: Long): List<TelemetryChunkEntity>

    @Query("SELECT * FROM telemetry_chunks WHERE rideId = :rideId AND kind = 'MOTION' AND endedAt >= :startedAt AND startedAt <= :endedAt ORDER BY startedAt")
    suspend fun motionChunksInWindow(rideId: Long, startedAt: Long, endedAt: Long): List<TelemetryChunkEntity>

    @Query("SELECT * FROM telemetry_chunks ORDER BY rideId, startedAt")
    suspend fun allTelemetryChunks(): List<TelemetryChunkEntity>

    @Query("SELECT * FROM spatial_profiles WHERE rideId = :rideId ORDER BY distanceBin")
    suspend fun spatialProfiles(rideId: Long): List<SpatialProfileEntity>

    @Query("SELECT * FROM spatial_profiles WHERE rideId = :rideId ORDER BY distanceBin")
    fun observeSpatialProfiles(rideId: Long): Flow<List<SpatialProfileEntity>>

    @Query("SELECT * FROM spatial_profiles ORDER BY rideId, distanceBin")
    suspend fun allSpatialProfiles(): List<SpatialProfileEntity>

    @Query("SELECT * FROM stop_events WHERE rideId = :rideId ORDER BY startedAt")
    suspend fun stopEvents(rideId: Long): List<StopEventEntity>

    @Query("SELECT * FROM stop_events ORDER BY rideId, startedAt")
    suspend fun allStopEvents(): List<StopEventEntity>

    @Query("SELECT * FROM stop_events WHERE uuid = :uuid LIMIT 1")
    suspend fun stopEventByUuid(uuid: String): StopEventEntity?

    @Query("SELECT * FROM trails ORDER BY updatedAt DESC")
    suspend fun allTrails(): List<TrailEntity>

    @Query("SELECT * FROM trails WHERE uuid = :uuid LIMIT 1")
    suspend fun trailByUuid(uuid: String): TrailEntity?

    @Query("SELECT * FROM trail_sections WHERE trailId = :trailId ORDER BY startMeters")
    suspend fun sections(trailId: Long): List<TrailSectionEntity>

    @Query("SELECT * FROM trail_sections ORDER BY trailId, startMeters")
    suspend fun allSections(): List<TrailSectionEntity>

    @Query("SELECT * FROM trail_sections WHERE uuid = :uuid LIMIT 1")
    suspend fun sectionByUuid(uuid: String): TrailSectionEntity?

    @Query("SELECT * FROM trail_pause_zones WHERE trailId = :trailId ORDER BY startMeters")
    suspend fun pauseZones(trailId: Long): List<TrailPauseZoneEntity>

    @Query("SELECT * FROM trail_pause_zones ORDER BY trailId, startMeters")
    suspend fun allPauseZones(): List<TrailPauseZoneEntity>

    @Query("SELECT * FROM trail_pause_zones WHERE uuid = :uuid LIMIT 1")
    suspend fun pauseZoneByUuid(uuid: String): TrailPauseZoneEntity?

    @Query("SELECT * FROM trail_stop_observations WHERE trailId = :trailId ORDER BY distanceMeters")
    suspend fun stopObservations(trailId: Long): List<TrailStopObservationEntity>

    @Query("SELECT * FROM trail_stop_observations ORDER BY trailId, distanceMeters")
    suspend fun allStopObservations(): List<TrailStopObservationEntity>

    @Query("SELECT * FROM trail_stop_observations WHERE uuid = :uuid LIMIT 1")
    suspend fun stopObservationByUuid(uuid: String): TrailStopObservationEntity?

    @Query("SELECT * FROM trail_passes WHERE trailId = :trailId ORDER BY startedAt DESC")
    suspend fun passes(trailId: Long): List<TrailPassEntity>

    @Query("SELECT * FROM trail_passes ORDER BY startedAt DESC")
    suspend fun allPasses(): List<TrailPassEntity>

    @Query("SELECT * FROM manual_trail_assignments WHERE rideId = :rideId LIMIT 1")
    suspend fun manualTrailAssignment(rideId: Long): ManualTrailAssignmentEntity?

    @Query("SELECT * FROM manual_trail_assignments")
    suspend fun allManualTrailAssignments(): List<ManualTrailAssignmentEntity>

    @Query("SELECT * FROM trail_passes WHERE uuid = :uuid LIMIT 1")
    suspend fun passByUuid(uuid: String): TrailPassEntity?

    @Query("SELECT * FROM section_efforts WHERE passId IN (:passIds) ORDER BY sectionId")
    suspend fun effortsForPasses(passIds: List<Long>): List<SectionEffortEntity>

    @Query("SELECT * FROM section_efforts ORDER BY id")
    suspend fun allEfforts(): List<SectionEffortEntity>

    @Query("SELECT * FROM rides WHERE id = :rideId")
    suspend fun ride(rideId: Long): RideEntity?

    @Query("SELECT * FROM rides WHERE uuid = :uuid LIMIT 1")
    suspend fun rideByUuid(uuid: String): RideEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM jump_events WHERE rideId = :rideId AND takeoffAt = :takeoffAt)")
    suspend fun jumpExists(rideId: Long, takeoffAt: Long): Boolean

    @Query("SELECT * FROM rides ORDER BY startedAt")
    suspend fun allRides(): List<RideEntity>

    @Query("SELECT * FROM physical_features ORDER BY updatedAt DESC")
    suspend fun allPhysicalFeatures(): List<PhysicalFeatureEntity>

    @Query("SELECT * FROM feature_observations ORDER BY createdAt")
    suspend fun allFeatureObservations(): List<FeatureObservationEntity>

    @Query("SELECT * FROM feature_observations WHERE featureId = :featureId ORDER BY createdAt")
    suspend fun featureObservations(featureId: Long): List<FeatureObservationEntity>

    @Query("SELECT * FROM feature_observations WHERE jumpId = :jumpId LIMIT 1")
    suspend fun featureObservationForJump(jumpId: Long): FeatureObservationEntity?

    @Query("DELETE FROM feature_observations WHERE jumpId = :jumpId")
    suspend fun deleteFeatureObservationForJump(jumpId: Long)

    @Query("DELETE FROM physical_features WHERE id = :featureId")
    suspend fun deletePhysicalFeature(featureId: Long)

    @Query("DELETE FROM physical_features WHERE id NOT IN (SELECT DISTINCT featureId FROM feature_observations WHERE featureId IS NOT NULL)")
    suspend fun deleteEmptyPhysicalFeatures()

    @Query("SELECT * FROM rides WHERE endedAt IS NOT NULL AND (archivedAt IS NULL OR analysisVersion < :analysisVersion) ORDER BY startedAt")
    suspend fun ridesNeedingProcessing(analysisVersion: Int): List<RideEntity>

    @Query("DELETE FROM track_points WHERE rideId = :rideId")
    suspend fun deleteTrackPoints(rideId: Long)

    @Query("DELETE FROM stop_events WHERE rideId = :rideId")
    suspend fun deleteStopEventsForRide(rideId: Long)

    @Query("DELETE FROM trail_passes WHERE trailId = :trailId")
    suspend fun deletePassesForTrail(trailId: Long)

    @Query("DELETE FROM manual_trail_assignments WHERE rideId = :rideId")
    suspend fun deleteManualTrailAssignment(rideId: Long)

    @Query("DELETE FROM trail_sections WHERE trailId = :trailId AND kind NOT IN ('WHOLE_TRAIL', 'MANUAL')")
    suspend fun deleteAutoSections(trailId: Long)

    @Query("DELETE FROM trail_sections WHERE id = :sectionId")
    suspend fun deleteSection(sectionId: Long)

    @Query("DELETE FROM trail_pause_zones WHERE id = :pauseZoneId")
    suspend fun deletePauseZone(pauseZoneId: Long)

    @Query("DELETE FROM trail_pause_zones WHERE trailId = :trailId AND state NOT IN ('USER_LOCKED', 'DISMISSED')")
    suspend fun deleteGeneratedPauseZones(trailId: Long)

    @Query("DELETE FROM rides WHERE id = :rideId AND state NOT IN ('RECORDING', 'PAUSED')")
    suspend fun deleteFinishedRide(rideId: Long): Int

    @Query("DELETE FROM rides WHERE id IN (:rideIds) AND state NOT IN ('RECORDING', 'PAUSED')")
    suspend fun deleteFinishedRides(rideIds: List<Long>): Int

    @Query("DELETE FROM telemetry_chunks WHERE rideId = :rideId AND kind = 'MOTION'")
    suspend fun deleteMotionForRide(rideId: Long): Int

    @Query("DELETE FROM jump_events WHERE rideId = :rideId")
    suspend fun deleteJumpsForRide(rideId: Long): Int

    @Query("DELETE FROM telemetry_chunks WHERE kind = 'MOTION' AND expiresAt IS NOT NULL AND expiresAt < :now AND rideId IN (SELECT DISTINCT rideId FROM spatial_profiles WHERE roughnessScore IS NOT NULL)")
    suspend fun deleteExpiredMotion(now: Long): Int

    @Query("SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM telemetry_chunks")
    fun observeTelemetryBytes(): Flow<Long>

    @Query("SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM telemetry_chunks WHERE kind = 'MOTION'")
    fun observeMotionBytes(): Flow<Long>

    @Query("SELECT MIN(expiresAt) FROM telemetry_chunks WHERE kind = 'MOTION' AND expiresAt IS NOT NULL")
    fun observeNextMotionExpiry(): Flow<Long?>

    @Query("""SELECT
        rides.id AS rideId,
        COALESCE(trackPointTotals.rowCount, 0) AS trackPointCount,
        COALESCE(telemetryTotals.rowCount, 0) AS telemetryChunkCount,
        COALESCE(telemetryTotals.payloadBytes, 0) AS telemetryPayloadBytes,
        COALESCE(jumpTotals.rowCount, 0) AS jumpCount,
        COALESCE(jumpTraceTotals.rowCount, 0) AS jumpTraceCount,
        COALESCE(jumpTraceTotals.payloadBytes, 0) AS jumpTracePayloadBytes,
        COALESCE(profileTotals.rowCount, 0) AS spatialProfileCount,
        COALESCE(stopTotals.rowCount, 0) AS stopEventCount,
        COALESCE(passTotals.rowCount, 0) AS trailPassCount,
        COALESCE(effortTotals.rowCount, 0) AS sectionEffortCount,
        COALESCE(observationTotals.rowCount, 0) AS trailStopObservationCount
    FROM rides
    LEFT JOIN (
        SELECT rideId, COUNT(*) AS rowCount FROM track_points GROUP BY rideId
    ) AS trackPointTotals ON trackPointTotals.rideId = rides.id
    LEFT JOIN (
        SELECT rideId, COUNT(*) AS rowCount, COALESCE(SUM(LENGTH(payload)), 0) AS payloadBytes
        FROM telemetry_chunks GROUP BY rideId
    ) AS telemetryTotals ON telemetryTotals.rideId = rides.id
    LEFT JOIN (
        SELECT rideId, COUNT(*) AS rowCount FROM jump_events GROUP BY rideId
    ) AS jumpTotals ON jumpTotals.rideId = rides.id
    LEFT JOIN (
        SELECT jump_events.rideId AS rideId, COUNT(*) AS rowCount,
            COALESCE(SUM(LENGTH(jump_motion_traces.payload)), 0) AS payloadBytes
        FROM jump_motion_traces
        INNER JOIN jump_events ON jump_events.id = jump_motion_traces.jumpId
        GROUP BY jump_events.rideId
    ) AS jumpTraceTotals ON jumpTraceTotals.rideId = rides.id
    LEFT JOIN (
        SELECT rideId, COUNT(*) AS rowCount FROM spatial_profiles GROUP BY rideId
    ) AS profileTotals ON profileTotals.rideId = rides.id
    LEFT JOIN (
        SELECT rideId, COUNT(*) AS rowCount FROM stop_events GROUP BY rideId
    ) AS stopTotals ON stopTotals.rideId = rides.id
    LEFT JOIN (
        SELECT rideId, COUNT(*) AS rowCount FROM trail_passes GROUP BY rideId
    ) AS passTotals ON passTotals.rideId = rides.id
    LEFT JOIN (
        SELECT trail_passes.rideId AS rideId, COUNT(*) AS rowCount
        FROM section_efforts
        INNER JOIN trail_passes ON trail_passes.id = section_efforts.passId
        GROUP BY trail_passes.rideId
    ) AS effortTotals ON effortTotals.rideId = rides.id
    LEFT JOIN (
        SELECT trail_passes.rideId AS rideId, COUNT(*) AS rowCount
        FROM trail_stop_observations
        INNER JOIN trail_passes ON trail_passes.id = trail_stop_observations.passId
        GROUP BY trail_passes.rideId
    ) AS observationTotals ON observationTotals.rideId = rides.id
    ORDER BY rides.id
    """)
    fun observeRideStorageComponents(): Flow<List<RideStorageComponents>>

    @Query("""SELECT
        (SELECT COUNT(*) * 112 FROM spatial_profiles) +
        (SELECT COUNT(*) * 96 FROM stop_events) +
        (SELECT COUNT(*) * 128 FROM trail_pause_zones) +
        (SELECT COUNT(*) * 96 FROM trail_stop_observations) +
        (SELECT COUNT(*) * 144 FROM section_efforts) +
        (SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM jump_motion_traces)
    """)
    fun observeEstimatedProfileBytes(): Flow<Long>

    @Query("UPDATE jump_events SET status = :status WHERE id = :jumpId")
    suspend fun setJumpStatus(jumpId: Long, status: JumpStatus)

    @Query("UPDATE jump_events SET correctedFlightKind = :kind WHERE id = :jumpId")
    suspend fun setCorrectedFlightKind(jumpId: Long, kind: FlightKind?)

    @Query("UPDATE rides SET state = :interrupted, endedAt = COALESCE(endedAt, :now) WHERE state IN (:recording, :paused)")
    suspend fun interruptStaleRides(
        now: Long,
        interrupted: RideState = RideState.INTERRUPTED,
        recording: RideState = RideState.RECORDING,
        paused: RideState = RideState.PAUSED,
    )
}
