package com.example.flightlog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.RideState
import kotlinx.coroutines.flow.Flow

@Dao
interface FlightLogDao {
    @Insert suspend fun insertRide(ride: RideEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRideIfAbsent(ride: RideEntity): Long
    @Update suspend fun updateRide(ride: RideEntity)
    @Insert suspend fun insertTrackPoint(point: TrackPointEntity): Long
    @Insert suspend fun insertJump(jump: JumpEventEntity): Long
    @Update suspend fun updateJump(jump: JumpEventEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTelemetryChunk(chunk: TelemetryChunkEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSpatialProfiles(profiles: List<SpatialProfileEntity>)
    @Insert suspend fun insertTrail(trail: TrailEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTrailIfAbsent(trail: TrailEntity): Long
    @Update suspend fun updateTrail(trail: TrailEntity)
    @Insert suspend fun insertSection(section: TrailSectionEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSectionIfAbsent(section: TrailSectionEntity): Long
    @Update suspend fun updateSection(section: TrailSectionEntity)
    @Insert suspend fun insertPass(pass: TrailPassEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPassIfAbsent(pass: TrailPassEntity): Long
    @Insert suspend fun insertEfforts(efforts: List<SectionEffortEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertEffortIfAbsent(effort: SectionEffortEntity): Long

    @Query("SELECT * FROM rides ORDER BY startedAt DESC")
    fun observeRides(): Flow<List<RideEntity>>

    @Query("SELECT * FROM jump_events ORDER BY takeoffAt DESC")
    fun observeJumps(): Flow<List<JumpEventEntity>>

    @Query("SELECT * FROM jump_events WHERE rideId = :rideId ORDER BY takeoffAt")
    fun observeJumpsForRide(rideId: Long): Flow<List<JumpEventEntity>>

    @Query("SELECT * FROM track_points WHERE rideId = :rideId ORDER BY recordedAt")
    fun observeTrackPoints(rideId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM trails WHERE supportCount >= 2 OR state = 'CONFIRMED' ORDER BY updatedAt DESC")
    fun observeVisibleTrails(): Flow<List<TrailEntity>>

    @Query("SELECT * FROM trail_sections ORDER BY trailId, startMeters")
    fun observeSections(): Flow<List<TrailSectionEntity>>

    @Query("SELECT * FROM trail_passes ORDER BY startedAt DESC")
    fun observePasses(): Flow<List<TrailPassEntity>>

    @Query("SELECT * FROM section_efforts ORDER BY id DESC")
    fun observeEfforts(): Flow<List<SectionEffortEntity>>

    @Query("SELECT * FROM track_points WHERE rideId = :rideId ORDER BY recordedAt")
    suspend fun trackPoints(rideId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM jump_events WHERE rideId = :rideId ORDER BY takeoffAt")
    suspend fun jumps(rideId: Long): List<JumpEventEntity>

    @Query("SELECT * FROM telemetry_chunks WHERE rideId = :rideId ORDER BY startedAt")
    suspend fun telemetryChunks(rideId: Long): List<TelemetryChunkEntity>

    @Query("SELECT * FROM telemetry_chunks ORDER BY rideId, startedAt")
    suspend fun allTelemetryChunks(): List<TelemetryChunkEntity>

    @Query("SELECT * FROM spatial_profiles WHERE rideId = :rideId ORDER BY distanceBin")
    suspend fun spatialProfiles(rideId: Long): List<SpatialProfileEntity>

    @Query("SELECT * FROM spatial_profiles ORDER BY rideId, distanceBin")
    suspend fun allSpatialProfiles(): List<SpatialProfileEntity>

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

    @Query("SELECT * FROM trail_passes WHERE trailId = :trailId ORDER BY startedAt DESC")
    suspend fun passes(trailId: Long): List<TrailPassEntity>

    @Query("SELECT * FROM trail_passes ORDER BY startedAt DESC")
    suspend fun allPasses(): List<TrailPassEntity>

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

    @Query("SELECT * FROM rides WHERE endedAt IS NOT NULL AND archivedAt IS NULL ORDER BY startedAt")
    suspend fun ridesNeedingProcessing(): List<RideEntity>

    @Query("DELETE FROM track_points WHERE rideId = :rideId")
    suspend fun deleteTrackPoints(rideId: Long)

    @Query("DELETE FROM trail_passes WHERE trailId = :trailId")
    suspend fun deletePassesForTrail(trailId: Long)

    @Query("DELETE FROM trail_sections WHERE trailId = :trailId AND kind NOT IN ('WHOLE_TRAIL', 'MANUAL')")
    suspend fun deleteAutoSections(trailId: Long)

    @Query("DELETE FROM telemetry_chunks WHERE kind = 'MOTION' AND expiresAt IS NOT NULL AND expiresAt < :now AND rideId IN (SELECT DISTINCT rideId FROM spatial_profiles WHERE roughnessScore IS NOT NULL)")
    suspend fun deleteExpiredMotion(now: Long): Int

    @Query("SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM telemetry_chunks")
    fun observeTelemetryBytes(): Flow<Long>

    @Query("SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM telemetry_chunks WHERE kind = 'MOTION'")
    fun observeMotionBytes(): Flow<Long>

    @Query("SELECT MIN(expiresAt) FROM telemetry_chunks WHERE kind = 'MOTION' AND expiresAt IS NOT NULL")
    fun observeNextMotionExpiry(): Flow<Long?>

    @Query("SELECT COUNT(*) * 96 FROM spatial_profiles")
    fun observeEstimatedProfileBytes(): Flow<Long>

    @Query("UPDATE jump_events SET status = :status WHERE id = :jumpId")
    suspend fun setJumpStatus(jumpId: Long, status: JumpStatus)

    @Query("UPDATE rides SET state = :interrupted, endedAt = COALESCE(endedAt, :now) WHERE state IN (:recording, :paused)")
    suspend fun interruptStaleRides(
        now: Long,
        interrupted: RideState = RideState.INTERRUPTED,
        recording: RideState = RideState.RECORDING,
        paused: RideState = RideState.PAUSED,
    )
}
