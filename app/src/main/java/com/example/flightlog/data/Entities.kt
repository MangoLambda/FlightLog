package com.example.flightlog.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.RideState
import com.example.flightlog.domain.SensorQuality
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.RoughnessKind
import com.example.flightlog.domain.EffortInvalidReason
import com.example.flightlog.domain.PauseZoneState
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.SectionState
import com.example.flightlog.domain.TelemetryKind
import com.example.flightlog.domain.TrailState
import java.util.UUID

@Entity(tableName = "rides", indices = [Index(value = ["uuid"], unique = true)])
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val state: RideState = RideState.RECORDING,
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0,
    val maxSpeedMps: Double = 0.0,
    @ColumnInfo(defaultValue = "''") val uuid: String = UUID.randomUUID().toString(),
    val mountingMode: MountingMode? = null,
    val archivedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val analysisVersion: Int = 0,
)

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(
        entity = RideEntity::class,
        parentColumns = ["id"],
        childColumns = ["rideId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("rideId"), Index(value = ["rideId", "recordedAt"])],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val recordedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedMps: Double,
    val bearingDegrees: Float?,
    val accuracyMeters: Float,
)

@Entity(
    tableName = "jump_events",
    foreignKeys = [ForeignKey(
        entity = RideEntity::class,
        parentColumns = ["id"],
        childColumns = ["rideId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("rideId"), Index(value = ["rideId", "takeoffAt"])],
)
data class JumpEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rideId: Long,
    val takeoffAt: Long,
    val landingAt: Long,
    val estimatedFlightSeconds: Double,
    val estimatedHeightMeters: Double,
    val estimatedDistanceMeters: Double,
    val correctedFlightSeconds: Double? = null,
    val correctedHeightMeters: Double? = null,
    val correctedDistanceMeters: Double? = null,
    val confidence: Int,
    val status: JumpStatus = JumpStatus.CONFIRMED,
    val sensorQuality: SensorQuality,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val displayFlightSeconds get() = correctedFlightSeconds ?: estimatedFlightSeconds
    val displayHeightMeters get() = correctedHeightMeters ?: estimatedHeightMeters
    val displayDistanceMeters get() = correctedDistanceMeters ?: estimatedDistanceMeters
}

@Entity(
    tableName = "jump_motion_traces",
    foreignKeys = [ForeignKey(
        entity = JumpEventEntity::class,
        parentColumns = ["id"],
        childColumns = ["jumpId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class JumpMotionTraceEntity(
    @PrimaryKey val jumpId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val encodingVersion: Int,
    val sampleCount: Int,
    val payload: ByteArray,
    val checksum: String,
)

@Entity(
    tableName = "telemetry_chunks",
    foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("rideId"), Index(value = ["rideId", "kind", "startedAt"]), Index("expiresAt")],
)
data class TelemetryChunkEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    val rideId: Long,
    val kind: TelemetryKind,
    val startedAt: Long,
    val endedAt: Long,
    val encodingVersion: Int,
    val sampleCount: Int,
    val payload: ByteArray,
    val checksum: String,
    val expiresAt: Long? = null,
)

@Entity(
    tableName = "spatial_profiles", primaryKeys = ["rideId", "distanceBin"],
    foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("rideId")],
)
data class SpatialProfileEntity(
    val rideId: Long,
    val distanceBin: Int,
    val distanceMeters: Double,
    val recordedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val speedMps: Double,
    val accuracyMeters: Float,
    @ColumnInfo(defaultValue = "0") val observedSpanMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val maximumSampleGapMillis: Long = 0,
    val roughnessScore: Double? = null,
    val roughnessKind: RoughnessKind? = null,
    val roughnessConfidence: Int? = null,
)

@Entity(
    tableName = "trails",
    foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["canonicalRideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["uuid"], unique = true), Index("canonicalRideId"), Index("state")],
)
data class TrailEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val name: String,
    val state: TrailState = TrailState.CANDIDATE,
    val canonicalRideId: Long,
    val lengthMeters: Double,
    @ColumnInfo(defaultValue = "0") val startMeters: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val endMeters: Double = 0.0,
    val supportCount: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "stop_events",
    foreignKeys = [ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["uuid"], unique = true), Index("rideId"), Index(value = ["rideId", "startedAt"])],
)
data class StopEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val rideId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val durationMillis: Long,
)

@Entity(
    tableName = "trail_pause_zones",
    foreignKeys = [ForeignKey(entity = TrailEntity::class, parentColumns = ["id"], childColumns = ["trailId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["uuid"], unique = true), Index("trailId"), Index(value = ["trailId", "startMeters"])],
)
data class TrailPauseZoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val trailId: Long,
    val name: String,
    val startMeters: Double,
    val endMeters: Double,
    val state: PauseZoneState = PauseZoneState.AUTOMATIC,
    val supportCount: Int,
    val eligiblePassCount: Int,
    val confidence: Int,
    val medianPauseMillis: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "trail_sections",
    foreignKeys = [ForeignKey(entity = TrailEntity::class, parentColumns = ["id"], childColumns = ["trailId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["uuid"], unique = true), Index("trailId")],
)
data class TrailSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val trailId: Long,
    val name: String,
    val kind: SectionKind,
    val state: SectionState = SectionState.SUGGESTED,
    val startMeters: Double,
    val endMeters: Double,
    val precedingPauseZoneId: Long? = null,
    val followingPauseZoneId: Long? = null,
)

@Entity(
    tableName = "trail_passes",
    foreignKeys = [
        ForeignKey(entity = TrailEntity::class, parentColumns = ["id"], childColumns = ["trailId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = RideEntity::class, parentColumns = ["id"], childColumns = ["rideId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["uuid"], unique = true), Index("trailId"), Index("rideId"), Index(value = ["trailId", "startedAt"])],
)
data class TrailPassEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val trailId: Long,
    val rideId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val startMeters: Double,
    val endMeters: Double,
    val matchConfidence: Int,
    val interrupted: Boolean,
    @ColumnInfo(defaultValue = "0") val completeCoverage: Boolean = false,
    @ColumnInfo(defaultValue = "0") val stopCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val stoppedDurationMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val hasReversal: Boolean = false,
    @ColumnInfo(defaultValue = "0") val bridgedGapMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val fullRunEligible: Boolean = false,
)

@Entity(
    tableName = "trail_stop_observations",
    foreignKeys = [
        ForeignKey(entity = TrailEntity::class, parentColumns = ["id"], childColumns = ["trailId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrailPassEntity::class, parentColumns = ["id"], childColumns = ["passId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = StopEventEntity::class, parentColumns = ["id"], childColumns = ["stopEventId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["uuid"], unique = true), Index("trailId"), Index("passId"), Index("stopEventId"),
        Index(value = ["trailId", "distanceMeters"]),
    ],
)
data class TrailStopObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val trailId: Long,
    val passId: Long,
    val stopEventId: Long,
    val distanceMeters: Double,
    val startMeters: Double,
    val endMeters: Double,
    val durationMillis: Long,
    val confidence: Int,
)

@Entity(
    tableName = "section_efforts",
    foreignKeys = [
        ForeignKey(entity = TrailPassEntity::class, parentColumns = ["id"], childColumns = ["passId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrailSectionEntity::class, parentColumns = ["id"], childColumns = ["sectionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["uuid"], unique = true), Index("passId"), Index("sectionId")],
)
data class SectionEffortEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val passId: Long,
    val sectionId: Long,
    val elapsedMillis: Long,
    val entrySpeedMps: Double,
    val minimumSpeedMps: Double,
    val averageSpeedMps: Double,
    val exitSpeedMps: Double,
    val maximumSpeedMps: Double,
    val roughnessScore: Double? = null,
    val roughnessKind: RoughnessKind? = null,
    val sampleQuality: Int,
    val lateralOffsetMeters: Double,
    val lateralUncertaintyMeters: Double,
    val valid: Boolean,
    val invalidReason: EffortInvalidReason? = null,
    @ColumnInfo(defaultValue = "0") val reachedWithoutPriorStop: Boolean = false,
    @ColumnInfo(defaultValue = "0") val estimated: Boolean = false,
    @ColumnInfo(defaultValue = "0") val bridgedGapMillis: Long = 0,
)
