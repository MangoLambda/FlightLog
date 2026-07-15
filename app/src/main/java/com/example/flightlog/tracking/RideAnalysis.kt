package com.example.flightlog.tracking

import androidx.room.withTransaction
import com.example.flightlog.data.FlightLogDatabase
import com.example.flightlog.data.SectionEffortEntity
import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.TelemetryChunkEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.data.TrailEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.RoughnessKind
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.SectionState
import com.example.flightlog.domain.TelemetryKind
import com.example.flightlog.domain.TrailState
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

object TrailAnalysis {
    const val ANALYSIS_VERSION = 1
    const val BIN_METERS = 5.0
    private const val MAX_GPS_ACCURACY_METERS = 25f
    private const val STATIONARY_SPEED_MPS = 0.8
    private const val INTERRUPTION_MILLIS = 10_000L

    fun spatialProfiles(
        rideId: Long,
        points: List<TrackPointEntity>,
        motion: List<MotionSample>,
        mountingMode: MountingMode?,
    ): List<SpatialProfileEntity> {
        val usable = points.filter { it.accuracyMeters <= MAX_GPS_ACCURACY_METERS }.sortedBy { it.recordedAt }
        if (usable.isEmpty()) return emptyList()
        var distance = 0.0
        var previous = usable.first()
        val grouped = linkedMapOf<Int, MutableList<Pair<Double, TrackPointEntity>>>()
        usable.forEach { point ->
            if (point !== usable.first()) distance += distance(previous, point)
            grouped.getOrPut(floor(distance / BIN_METERS).toInt()) { mutableListOf() }.add(distance to point)
            previous = point
        }
        val base = grouped.map { (bin, entries) ->
            SpatialProfileEntity(
                rideId = rideId,
                distanceBin = bin,
                distanceMeters = entries.map { it.first }.average(),
                recordedAt = entries.map { it.second.recordedAt }.average().toLong(),
                latitude = entries.map { it.second.latitude }.average(),
                longitude = entries.map { it.second.longitude }.average(),
                speedMps = entries.map { it.second.speedMps }.average(),
                accuracyMeters = entries.map { it.second.accuracyMeters.toDouble() }.average().toFloat(),
                observedSpanMillis = entries.maxOf { it.second.recordedAt } - entries.minOf { it.second.recordedAt },
            )
        }
        if (motion.isEmpty() || mountingMode == null) return base
        val motionByBin = HashMap<Int, MutableList<Double>>()
        var previousMagnitude: Double? = null
        motion.sortedBy { it.timestampMillis }.forEach { sample ->
            val nearestIndex = base.binarySearchBy(sample.timestampMillis) { it.recordedAt }
                .let { if (it >= 0) it else (-it - 1).coerceIn(0, base.lastIndex) }
            val magnitude = sqrt(
                sample.accelerationX.toDouble().pow(2) + sample.accelerationY.toDouble().pow(2) +
                    sample.accelerationZ.toDouble().pow(2),
            )
            val gyro = sqrt(
                sample.gyroscopeX.toDouble().pow(2) + sample.gyroscopeY.toDouble().pow(2) +
                    sample.gyroscopeZ.toDouble().pow(2),
            )
            previousMagnitude?.let { prior ->
                val highFrequencyEnergy = abs(magnitude - prior).coerceAtMost(20.0) * (1.0 + gyro.coerceAtMost(10.0) / 20.0)
                motionByBin.getOrPut(base[nearestIndex].distanceBin) { mutableListOf() }.add(highFrequencyEnergy)
            }
            previousMagnitude = magnitude
        }
        val kind = if (mountingMode == MountingMode.BIKE_MOUNTED) RoughnessKind.BIKE_ROUGHNESS else RoughnessKind.RIDER_DISTURBANCE
        return base.map { profile ->
            val energy = motionByBin[profile.distanceBin].orEmpty()
            if (energy.size < 3) profile else profile.copy(
                roughnessScore = energy.map { it * it }.average().let(::sqrt) *
                    if (kind == RoughnessKind.RIDER_DISTURBANCE) 0.65 else 1.0,
                roughnessKind = kind,
                roughnessConfidence = if (kind == RoughnessKind.BIKE_ROUGHNESS) 80 else 45,
            )
        }
    }

    data class Match(val confidence: Int, val normalized: List<Pair<SpatialProfileEntity, SpatialProfileEntity>>)

    fun match(candidate: List<SpatialProfileEntity>, canonical: List<SpatialProfileEntity>): Match? {
        if (candidate.size < 20 || canonical.size < 20) return null
        val normalized = candidate.mapNotNull { point ->
            canonical.minByOrNull { distance(point, it) }?.let { nearest ->
                val separation = distance(point, nearest)
                val corridor = maxOf(15.0, point.accuracyMeters * 2.0, nearest.accuracyMeters * 2.0)
                if (separation <= corridor) point to nearest else null
            }
        }
        if (normalized.size < 20) return null
        val progression = normalized.zipWithNext().count { (a, b) -> b.second.distanceMeters >= a.second.distanceMeters - 2.0 }
            .toDouble() / (normalized.size - 1)
        if (progression < 0.80) return null
        val coveredMeters = normalized.maxOf { it.second.distanceMeters } - normalized.minOf { it.second.distanceMeters }
        val coverage = coveredMeters / canonical.last().distanceMeters.coerceAtLeast(1.0)
        if (coverage < 0.40 && coveredMeters < 300.0) return null
        val meanError = normalized.map { distance(it.first, it.second) }.average()
        val confidence = (progression * 45 + coverage.coerceAtMost(1.0) * 30 +
            (1.0 - meanError / 30.0).coerceIn(0.0, 1.0) * 25).toInt().coerceIn(0, 100)
        return Match(confidence, normalized)
    }

    fun suggestedSections(trailId: Long, canonical: List<SpatialProfileEntity>): List<TrailSectionEntity> {
        if (canonical.isEmpty()) return emptyList()
        val result = mutableListOf(
            TrailSectionEntity(
                trailId = trailId, name = "Whole trail", kind = SectionKind.WHOLE_TRAIL,
                state = SectionState.CONFIRMED, startMeters = canonical.first().distanceMeters,
                endMeters = canonical.last().distanceMeters,
            ),
        )
        var lastTurnEnd = Double.NEGATIVE_INFINITY
        canonical.indices.drop(4).dropLast(4).forEach { index ->
            val before = bearing(canonical[index - 4], canonical[index])
            val after = bearing(canonical[index], canonical[index + 4])
            val change = angularDifference(before, after)
            val center = canonical[index].distanceMeters
            if (change >= 35.0 && center - lastTurnEnd >= 30.0) {
                val start = (center - 20).coerceAtLeast(canonical.first().distanceMeters)
                val end = (center + 20).coerceAtMost(canonical.last().distanceMeters)
                result += TrailSectionEntity(
                    trailId = trailId, name = "Turn ${result.count { it.kind == SectionKind.TURN } + 1}",
                    kind = SectionKind.TURN, startMeters = start, endMeters = end,
                )
                lastTurnEnd = end
            }
        }
        val roughValues = canonical.mapNotNull { it.roughnessScore }.sorted()
        val roughThreshold = if (roughValues.size >= 10) {
            roughValues[(roughValues.size * 0.85).toInt().coerceAtMost(roughValues.lastIndex)]
        } else null
        if (roughThreshold != null) {
            canonical.filter { (it.roughnessScore ?: 0.0) >= roughThreshold }
                .groupBy { floor(it.distanceMeters / 30.0).toInt() }
                .values.forEachIndexed { index, values ->
                    result += TrailSectionEntity(
                        trailId = trailId, name = "Rough area ${index + 1}", kind = SectionKind.ROUGH,
                        startMeters = (values.minOf { it.distanceMeters } - 5).coerceAtLeast(0.0),
                        endMeters = values.maxOf { it.distanceMeters } + 5,
                    )
                }
        }
        return result
    }

    fun effort(
        passId: Long,
        section: TrailSectionEntity,
        normalized: List<Pair<SpatialProfileEntity, SpatialProfileEntity>>,
    ): SectionEffortEntity? {
        val selected = normalized.filter { (_, canonical) -> canonical.distanceMeters in section.startMeters..section.endMeters }
            .sortedBy { it.second.distanceMeters }
        if (selected.size < 2 || selected.first().second.distanceMeters > section.startMeters + BIN_METERS ||
            selected.last().second.distanceMeters < section.endMeters - BIN_METERS) return null
        val points = selected.map { it.first }
        var stationaryMillis = 0L
        var interrupted = points.any { it.observedSpanMillis > INTERRUPTION_MILLIS }
        points.zipWithNext().forEach { (a, b) ->
            val gap = b.recordedAt - a.recordedAt
            if (gap > 5_000) interrupted = true
            stationaryMillis = if (b.speedMps < STATIONARY_SPEED_MPS) stationaryMillis + gap.coerceAtLeast(0) else 0
            if (stationaryMillis > INTERRUPTION_MILLIS) interrupted = true
        }
        val rough = points.mapNotNull { it.roughnessScore }
        val uncertainty = points.map { it.accuracyMeters.toDouble() }.average()
        val offsets = selected.map { distance(it.first, it.second) }
        return SectionEffortEntity(
            passId = passId, sectionId = section.id,
            elapsedMillis = (points.last().recordedAt - points.first().recordedAt).coerceAtLeast(0),
            entrySpeedMps = points.first().speedMps,
            minimumSpeedMps = points.minOf { it.speedMps },
            averageSpeedMps = points.map { it.speedMps }.average(),
            exitSpeedMps = points.last().speedMps,
            maximumSpeedMps = points.maxOf { it.speedMps },
            roughnessScore = rough.takeIf { it.isNotEmpty() }?.average(),
            roughnessKind = points.mapNotNull { it.roughnessKind }.firstOrNull(),
            sampleQuality = (100 - uncertainty * 3).toInt().coerceIn(0, 100),
            lateralOffsetMeters = offsets.average(), lateralUncertaintyMeters = uncertainty,
            valid = !interrupted,
        )
    }

    fun lineDifferenceIsReliable(a: SectionEffortEntity, b: SectionEffortEntity): Boolean =
        abs(a.lateralOffsetMeters - b.lateralOffsetMeters) > a.lateralUncertaintyMeters + b.lateralUncertaintyMeters

    private fun distance(a: TrackPointEntity, b: TrackPointEntity): Double = RideMath.distanceMeters(
        LocationSample(a.recordedAt, a.latitude, a.longitude, a.accuracyMeters, a.speedMps),
        LocationSample(b.recordedAt, b.latitude, b.longitude, b.accuracyMeters, b.speedMps),
    )

    private fun distance(a: SpatialProfileEntity, b: SpatialProfileEntity): Double = RideMath.distanceMeters(
        LocationSample(a.recordedAt, a.latitude, a.longitude, a.accuracyMeters, a.speedMps),
        LocationSample(b.recordedAt, b.latitude, b.longitude, b.accuracyMeters, b.speedMps),
    )

    private fun bearing(a: SpatialProfileEntity, b: SpatialProfileEntity): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val longitude = Math.toRadians(b.longitude - a.longitude)
        return Math.toDegrees(atan2(
            kotlin.math.sin(longitude) * kotlin.math.cos(lat2),
            kotlin.math.cos(lat1) * kotlin.math.sin(lat2) - kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(longitude),
        ))
    }

    private fun angularDifference(a: Double, b: Double): Double = abs((b - a + 540) % 360 - 180)
}

class RideProcessor(private val database: FlightLogDatabase) {
    private val dao = database.dao()

    suspend fun compactAndAnalyze(rideId: Long) {
        val ride = dao.ride(rideId) ?: return
        if (ride.archivedAt != null && ride.analysisVersion >= TrailAnalysis.ANALYSIS_VERSION) return
        val activePoints = dao.trackPoints(rideId)
        val existingGps = dao.telemetryChunks(rideId).filter { it.kind == TelemetryKind.GPS }
        val points = if (activePoints.isNotEmpty()) activePoints else existingGps.flatMap {
            TelemetryCodec.decodeGps(rideId, it.payload, it.checksum)
        }.sortedBy { it.recordedAt }
        if (points.isEmpty()) {
            database.withTransaction {
                dao.deleteMotionForRide(rideId)
                dao.deleteJumpsForRide(rideId)
                dao.updateRide(ride.copy(
                    archivedAt = System.currentTimeMillis(),
                    analysisVersion = TrailAnalysis.ANALYSIS_VERSION,
                ))
            }
            return
        }
        val motionChunks = dao.telemetryChunks(rideId).filter { it.kind == TelemetryKind.MOTION }
        val motion = motionChunks.flatMap { TelemetryCodec.decodeMotion(it.payload, it.checksum) }
        val profiles = TrailAnalysis.spatialProfiles(rideId, points, motion, ride.mountingMode)
        database.withTransaction {
            if (existingGps.isEmpty()) {
                points.chunked(10_000).forEach { chunk ->
                    val encoded = TelemetryCodec.encodeGps(chunk)
                    dao.insertTelemetryChunk(encoded.toEntity(rideId, TelemetryKind.GPS, expiresAt = null))
                }
            }
            dao.insertSpatialProfiles(profiles)
            dao.updateRide(ride.copy(archivedAt = System.currentTimeMillis(), analysisVersion = TrailAnalysis.ANALYSIS_VERSION))
            dao.deleteTrackPoints(rideId)
        }
        analyzeTrail(rideId, profiles)
    }

    suspend fun cleanupExpiredMotion(now: Long = System.currentTimeMillis()): Int = dao.deleteExpiredMotion(now)

    suspend fun rebuildTrail(trailId: Long) {
        val trail = dao.allTrails().firstOrNull { it.id == trailId } ?: return
        val canonical = dao.spatialProfiles(trail.canonicalRideId)
        if (canonical.isEmpty()) return
        database.withTransaction {
            dao.deletePassesForTrail(trailId)
            dao.allRides().forEach { ride ->
                val profiles = dao.spatialProfiles(ride.id)
                val match = if (ride.id == trail.canonicalRideId) {
                    TrailAnalysis.Match(100, profiles.map { it to it })
                } else TrailAnalysis.match(profiles, canonical)
                if (match != null) createPasses(trailId, ride.id, match)
            }
        }
    }

    private suspend fun analyzeTrail(rideId: Long, profiles: List<SpatialProfileEntity>) {
        if (profiles.size < 20) return
        val trails = dao.allTrails()
        val matches = mutableListOf<Triple<TrailEntity, List<SpatialProfileEntity>, TrailAnalysis.Match>>()
        trails.forEach { trail ->
            val canonical = dao.spatialProfiles(trail.canonicalRideId)
            TrailAnalysis.match(profiles, canonical)?.let { matches += Triple(trail, canonical, it) }
        }
        val match = matches.maxByOrNull { it.third.confidence }
        if (match == null) {
            val trailId = dao.insertTrail(TrailEntity(
                name = "Trail near ride ${rideId}", canonicalRideId = rideId,
                lengthMeters = profiles.last().distanceMeters, endMeters = profiles.last().distanceMeters,
            ))
            val sections = TrailAnalysis.suggestedSections(trailId, profiles)
            sections.forEach { dao.insertSection(it) }
            createPasses(trailId, rideId, TrailAnalysis.Match(100, profiles.map { it to it }))
            return
        }
        val (trail, canonical, trailMatch) = match
        if (dao.passes(trail.id).any { it.rideId == rideId }) return
        val updatedCount = trail.supportCount + 1
        val becomingSuggested = trail.state == TrailState.CANDIDATE && updatedCount >= 2
        val commonStart = if (becomingSuggested) trailMatch.normalized.minOf { it.second.distanceMeters } else trail.startMeters
        val commonEnd = if (becomingSuggested) trailMatch.normalized.maxOf { it.second.distanceMeters } else trail.endMeters
        dao.updateTrail(trail.copy(
            supportCount = updatedCount,
            state = if (becomingSuggested) TrailState.SUGGESTED else trail.state,
            startMeters = commonStart, endMeters = commonEnd,
            updatedAt = System.currentTimeMillis(),
        ))
        if (becomingSuggested) {
            dao.sections(trail.id).firstOrNull { it.kind == SectionKind.WHOLE_TRAIL }?.let {
                dao.updateSection(it.copy(startMeters = commonStart, endMeters = commonEnd))
            }
            dao.deleteAutoSections(trail.id)
            val repeatedRoughness = trailMatch.normalized.groupBy { it.second.distanceBin }
            val sectionProfile = canonical.filter { it.distanceMeters in commonStart..commonEnd }.map { canonicalPoint ->
                val repeated = repeatedRoughness[canonicalPoint.distanceBin].orEmpty().map { it.first }
                    .filter { it.roughnessKind == canonicalPoint.roughnessKind }.mapNotNull { it.roughnessScore }
                canonicalPoint.copy(
                    roughnessScore = if (canonicalPoint.roughnessScore != null && repeated.isNotEmpty()) {
                        (repeated.average() + canonicalPoint.roughnessScore) / 2
                    } else null,
                )
            }
            TrailAnalysis.suggestedSections(trail.id, sectionProfile)
                .filter { it.kind != SectionKind.WHOLE_TRAIL }
                .forEach { dao.insertSection(it) }
            rebuildTrail(trail.id)
        } else {
            createPasses(trail.id, rideId, trailMatch)
        }
    }

    private suspend fun createPasses(trailId: Long, rideId: Long, match: TrailAnalysis.Match) {
        if (match.normalized.isEmpty()) return
        val segments = mutableListOf<MutableList<Pair<SpatialProfileEntity, SpatialProfileEntity>>>()
        match.normalized.sortedBy { it.first.recordedAt }.forEach { pair ->
            val current = segments.lastOrNull()
            val prior = current?.lastOrNull()
            if (current == null || (prior != null && pair.second.distanceMeters < prior.second.distanceMeters - 20.0)) {
                segments += mutableListOf(pair)
            } else current += pair
        }
        segments.filter { it.size >= 10 }.forEach { normalized -> createPass(trailId, rideId, match.confidence, normalized) }
    }

    private suspend fun createPass(
        trailId: Long,
        rideId: Long,
        confidence: Int,
        normalized: List<Pair<SpatialProfileEntity, SpatialProfileEntity>>,
    ) {
        val interrupted = normalized.zipWithNext().any { (a, b) -> b.first.recordedAt - a.first.recordedAt > 5_000 }
        val passId = dao.insertPass(TrailPassEntity(
            trailId = trailId, rideId = rideId,
            startedAt = normalized.first().first.recordedAt, endedAt = normalized.last().first.recordedAt,
            startMeters = normalized.minOf { it.second.distanceMeters },
            endMeters = normalized.maxOf { it.second.distanceMeters },
            matchConfidence = confidence, interrupted = interrupted,
        ))
        val efforts = dao.sections(trailId).mapNotNull { TrailAnalysis.effort(passId, it, normalized) }
        if (efforts.isNotEmpty()) dao.insertEfforts(efforts)
    }
}

fun EncodedTelemetry.toEntity(
    rideId: Long,
    kind: TelemetryKind,
    expiresAt: Long?,
): TelemetryChunkEntity = TelemetryChunkEntity(
    uuid = UUID.randomUUID().toString(), rideId = rideId, kind = kind,
    startedAt = startedAt, endedAt = endedAt, encodingVersion = TelemetryCodec.ENCODING_VERSION,
    sampleCount = sampleCount, payload = payload, checksum = checksum, expiresAt = expiresAt,
)
