package com.example.flightlog.tracking

import androidx.room.withTransaction
import com.example.flightlog.data.FlightLogDatabase
import com.example.flightlog.data.SectionEffortEntity
import com.example.flightlog.data.SpatialProfileEntity
import com.example.flightlog.data.StopEventEntity
import com.example.flightlog.data.TelemetryChunkEntity
import com.example.flightlog.data.TrackPointEntity
import com.example.flightlog.data.TrailEntity
import com.example.flightlog.data.TrailPassEntity
import com.example.flightlog.data.TrailStopObservationEntity
import com.example.flightlog.data.TrailPauseZoneEntity
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.domain.EffortInvalidReason
import com.example.flightlog.domain.PauseZoneState
import com.example.flightlog.domain.RoughnessKind
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.SectionState
import com.example.flightlog.domain.TelemetryKind
import com.example.flightlog.domain.TrailState
import java.util.UUID
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

object TrailAnalysis {
    const val ANALYSIS_VERSION = 5
    const val EFFORT_VERSION = 4
    const val BIN_METERS = 5.0
    private const val MAX_GPS_ACCURACY_METERS = 25f
    private const val STATIONARY_SPEED_MPS = 0.8
    internal const val INTERRUPTION_MILLIS = 10_000L
    internal const val GPS_SAMPLE_GAP_MILLIS = 5_000L
    internal const val MAX_BRIDGEABLE_GAP_MILLIS = 15_000L
    private const val MAX_BRIDGED_SPEED_MPS = 30.0
    private const val PAUSE_CLUSTER_METERS = 20.0
    private const val MAX_CONTINUOUS_ROUTE_GAP_METERS = BIN_METERS * 3.0
    private const val MINIMUM_MATCH_POINTS = 5

    fun stopEvents(
        rideId: Long,
        rideUuid: String,
        points: List<TrackPointEntity>,
    ): List<StopEventEntity> {
        val result = mutableListOf<StopEventEntity>()
        var candidate = mutableListOf<TrackPointEntity>()
        fun finishCandidate() {
            if (candidate.size >= 3) {
                val duration = candidate.last().recordedAt - candidate.first().recordedAt
                val latitude = candidate.map { it.latitude }.average()
                val longitude = candidate.map { it.longitude }.average()
                val accuracy = candidate.map { it.accuracyMeters.toDouble() }.average().toFloat()
                val radius = candidate.maxOf { coordinateDistanceMeters(latitude, longitude, it.latitude, it.longitude) }
                if (duration >= INTERRUPTION_MILLIS && radius <= maxOf(15.0, accuracy * 2.0)) {
                    val startedAt = candidate.first().recordedAt
                    result += StopEventEntity(
                        uuid = UUID.nameUUIDFromBytes("$rideUuid:stop:$startedAt".toByteArray(StandardCharsets.UTF_8)).toString(),
                        rideId = rideId,
                        startedAt = startedAt,
                        endedAt = candidate.last().recordedAt,
                        latitude = latitude,
                        longitude = longitude,
                        accuracyMeters = accuracy,
                        durationMillis = duration,
                    )
                }
            }
            candidate = mutableListOf()
        }
        points.sortedBy { it.recordedAt }.forEach { point ->
            val usableStationary = point.accuracyMeters <= MAX_GPS_ACCURACY_METERS && point.speedMps < STATIONARY_SPEED_MPS
            val continuous = candidate.lastOrNull()?.let { point.recordedAt - it.recordedAt <= INTERRUPTION_MILLIS } ?: true
            if (!usableStationary || !continuous) finishCandidate()
            if (usableStationary) candidate += point
        }
        finishCandidate()
        return result
    }

    data class GapAssessment(val distanceMeters: Double, val durationMillis: Long, val bridgeable: Boolean)

    fun gapAssessments(normalized: List<Pair<SpatialProfileEntity, SpatialProfileEntity>>): List<GapAssessment> =
        normalized.sortedBy { it.first.recordedAt }.zipWithNext().mapNotNull { (a, b) ->
            val duration = b.first.maximumSampleGapMillis
            if (duration <= GPS_SAMPLE_GAP_MILLIS) return@mapNotNull null
            val speed = distance(a.first, b.first) / (duration / 1_000.0).coerceAtLeast(.001)
            GapAssessment(
                distanceMeters = b.second.distanceMeters,
                durationMillis = duration,
                bridgeable = duration <= MAX_BRIDGEABLE_GAP_MILLIS &&
                    a.first.accuracyMeters <= MAX_GPS_ACCURACY_METERS &&
                    b.first.accuracyMeters <= MAX_GPS_ACCURACY_METERS &&
                    b.second.distanceMeters >= a.second.distanceMeters - 2.0 &&
                    speed <= MAX_BRIDGED_SPEED_MPS,
            )
        }

    fun mapStopObservations(
        trailId: Long,
        trailUuid: String,
        passId: Long,
        normalized: List<Pair<SpatialProfileEntity, SpatialProfileEntity>>,
        events: List<StopEventEntity>,
    ): List<TrailStopObservationEntity> {
        if (normalized.isEmpty()) return emptyList()
        val sorted = normalized.sortedBy { it.first.recordedAt }
        return events.mapNotNull { event ->
            if (event.endedAt < sorted.first().first.recordedAt || event.startedAt > sorted.last().first.recordedAt) return@mapNotNull null
            val during = sorted.filter { it.first.recordedAt in event.startedAt..event.endedAt }
            val nearest = (during.ifEmpty {
                val midpoint = event.startedAt + event.durationMillis / 2
                listOfNotNull(sorted.minByOrNull { abs(it.first.recordedAt - midpoint) })
            })
            if (nearest.isEmpty()) return@mapNotNull null
            val anchor = nearest[nearest.size / 2]
            val separation = coordinateDistanceMeters(event.latitude, event.longitude, anchor.first.latitude, anchor.first.longitude)
            val corridor = maxOf(15.0, event.accuracyMeters * 2.0, anchor.first.accuracyMeters * 2.0)
            if (separation > corridor) return@mapNotNull null
            val distances = nearest.map { it.second.distanceMeters }
            val center = distances.average()
            TrailStopObservationEntity(
                uuid = UUID.nameUUIDFromBytes("$trailUuid:${event.uuid}".toByteArray(StandardCharsets.UTF_8)).toString(),
                trailId = trailId,
                passId = passId,
                stopEventId = event.id,
                distanceMeters = center,
                startMeters = distances.minOrNull() ?: center,
                endMeters = distances.maxOrNull() ?: center,
                durationMillis = event.durationMillis,
                confidence = (100 - separation * 2.0 - event.accuracyMeters).toInt().coerceIn(0, 100),
            )
        }
    }

    data class PauseZoneCandidate(
        val centerMeters: Double,
        val startMeters: Double,
        val endMeters: Double,
        val supportCount: Int,
        val eligiblePassCount: Int,
        val confidence: Int,
        val medianPauseMillis: Long,
        val active: Boolean,
    )

    fun pauseZoneCandidates(
        trail: TrailEntity,
        observations: List<TrailStopObservationEntity>,
        passes: List<TrailPassEntity>,
    ): List<PauseZoneCandidate> {
        if (observations.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<TrailStopObservationEntity>>()
        observations.sortedBy { it.distanceMeters }.forEach { observation ->
            val cluster = clusters.lastOrNull()
            if (cluster == null || observation.distanceMeters - cluster.map { it.distanceMeters }.average() > PAUSE_CLUSTER_METERS) {
                clusters += mutableListOf(observation)
            } else cluster += observation
        }
        return clusters.mapNotNull { rawCluster ->
            val cluster = rawCluster.groupBy { it.passId }.values.map { passStops ->
                passStops.maxBy { it.durationMillis }
            }
            val center = cluster.map { it.distanceMeters }.median() ?: return@mapNotNull null
            if (center < trail.startMeters + 30.0 || center > trail.endMeters - 30.0) return@mapNotNull null
            val eligible = passes.count {
                it.startMeters <= center - BIN_METERS && it.endMeters >= center + BIN_METERS && !it.hasReversal
            }
            val support = cluster.size
            val required = if (eligible <= 2) 2 else maxOf(2, ceil(eligible * .60).toInt())
            val rawStart = (cluster.map { it.startMeters }.median() ?: center) - 5.0
            val rawEnd = (cluster.map { it.endMeters }.median() ?: center) + 5.0
            val width = (rawEnd - rawStart).coerceIn(10.0, 30.0)
            val start = (center - width / 2.0).coerceAtLeast(trail.startMeters)
            val end = (start + width).coerceAtMost(trail.endMeters)
            val confidence = ((support.toDouble() / eligible.coerceAtLeast(1)) * 70.0 +
                cluster.map { it.confidence }.average() * .30).toInt().coerceIn(0, 100)
            PauseZoneCandidate(
                centerMeters = center,
                startMeters = end - width,
                endMeters = end,
                supportCount = support,
                eligiblePassCount = eligible,
                confidence = confidence,
                medianPauseMillis = cluster.map { it.durationMillis.toDouble() }.median()?.toLong() ?: 0,
                active = eligible >= 2 && support >= required,
            )
        }
    }

    fun spatialProfiles(
        rideId: Long,
        points: List<TrackPointEntity>,
        motion: List<MotionSample>,
        mountingMode: MountingMode?,
    ): List<SpatialProfileEntity> {
        val usable = points.filter { it.accuracyMeters <= MAX_GPS_ACCURACY_METERS }.sortedBy { it.recordedAt }
        if (usable.isEmpty()) return emptyList()
        var distance = 0.0
        var previous: TrackPointEntity? = null
        val grouped = linkedMapOf<Int, MutableList<Triple<Double, TrackPointEntity, Long>>>()
        usable.forEach { point ->
            val prior = previous
            val sampleGapMillis = prior?.let { (point.recordedAt - it.recordedAt).coerceAtLeast(0) } ?: 0L
            if (prior != null) distance += distance(prior, point)
            grouped.getOrPut(floor(distance / BIN_METERS).toInt()) { mutableListOf() }
                .add(Triple(distance, point, sampleGapMillis))
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
                altitudeMeters = entries.mapNotNull { it.second.altitudeMeters }.takeIf { it.isNotEmpty() }?.average(),
                speedMps = entries.map { it.second.speedMps }.average(),
                accuracyMeters = entries.map { it.second.accuracyMeters.toDouble() }.average().toFloat(),
                observedSpanMillis = entries.maxOf { it.second.recordedAt } - entries.minOf { it.second.recordedAt },
                maximumSampleGapMillis = entries.maxOf { it.third },
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

    fun preservePermanentSignals(
        rebuilt: List<SpatialProfileEntity>,
        previous: List<SpatialProfileEntity>,
    ): List<SpatialProfileEntity> {
        val previousByBin = previous.associateBy { it.distanceBin }
        return rebuilt.map { profile ->
            val prior = previousByBin[profile.distanceBin]
            if (profile.roughnessScore != null || prior?.roughnessScore == null) profile else profile.copy(
                roughnessScore = prior.roughnessScore,
                roughnessKind = prior.roughnessKind,
                roughnessConfidence = prior.roughnessConfidence,
            )
        }
    }

    fun match(candidate: List<SpatialProfileEntity>, canonical: List<SpatialProfileEntity>): Match? {
        if (candidate.size < MINIMUM_MATCH_POINTS || canonical.size < MINIMUM_MATCH_POINTS) return null
        val normalized = candidate.mapNotNull { point ->
            canonical.minByOrNull { distance(point, it) }?.let { nearest ->
                val separation = distance(point, nearest)
                val corridor = maxOf(15.0, point.accuracyMeters * 2.0, nearest.accuracyMeters * 2.0)
                if (separation <= corridor) point to nearest else null
            }
        }
        if (normalized.size < MINIMUM_MATCH_POINTS) return null
        val progression = normalized.zipWithNext().count { (a, b) -> b.second.distanceMeters >= a.second.distanceMeters - 2.0 }
            .toDouble() / (normalized.size - 1)
        if (progression < 0.80) return null
        val coveredMeters = normalized.maxOf { it.second.distanceMeters } - normalized.minOf { it.second.distanceMeters }
        val canonicalSpan = canonical.maxOf { it.distanceMeters } - canonical.minOf { it.distanceMeters }
        val coverage = coveredMeters / canonicalSpan.coerceAtLeast(1.0)
        if (coverage < 0.40 && coveredMeters < 300.0) return null
        val meanError = normalized.map { distance(it.first, it.second) }.average()
        val confidence = (progression * 45 + coverage.coerceAtMost(1.0) * 30 +
            (1.0 - meanError / 30.0).coerceIn(0.0, 1.0) * 25).toInt().coerceIn(0, 100)
        return Match(confidence, normalized)
    }

    /**
     * A full run must follow the selected geometry, not merely touch both endpoints.
     * Missing more than three permanent 5 m bins is treated as a different line or
     * an incomplete run; short telemetry gaps are represented by the profile itself.
     */
    fun hasContinuousRangeCoverage(
        normalized: List<Pair<SpatialProfileEntity, SpatialProfileEntity>>,
        startMeters: Double,
        endMeters: Double,
    ): Boolean {
        if (normalized.size < 2 || startMeters >= endMeters) return false
        val segments = mutableListOf<MutableList<Pair<SpatialProfileEntity, SpatialProfileEntity>>>()
        normalized.sortedBy { it.first.recordedAt }.forEach { pair ->
            val current = segments.lastOrNull()
            val previous = current?.lastOrNull()
            if (current == null || previous != null &&
                pair.second.distanceMeters < previous.second.distanceMeters - 20.0
            ) {
                segments += mutableListOf(pair)
            } else {
                current += pair
            }
        }
        return segments.any { segment ->
            val covered = segment.asSequence()
                .map { it.second.distanceMeters }
                .filter { it in (startMeters - BIN_METERS)..(endMeters + BIN_METERS) }
                .distinct()
                .sorted()
                .toList()
            covered.isNotEmpty() &&
                covered.first() <= startMeters + BIN_METERS &&
                covered.last() >= endMeters - BIN_METERS &&
                covered.zipWithNext().all { (a, b) -> b - a <= MAX_CONTINUOUS_ROUTE_GAP_METERS }
        }
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
        stopDistances: List<Double>? = null,
        gaps: List<GapAssessment> = gapAssessments(normalized),
        trailStartMeters: Double = 0.0,
    ): SectionEffortEntity? {
        val selected = normalized.filter { (_, canonical) -> canonical.distanceMeters in section.startMeters..section.endMeters }
            .sortedBy { it.second.distanceMeters }
        if (selected.size < 2 || !hasContinuousRangeCoverage(normalized, section.startMeters, section.endMeters)) return null
        val points = selected.map { it.first }
        val knownStops = stopDistances ?: selected.filter { it.first.observedSpanMillis >= INTERRUPTION_MILLIS }
            .map { it.second.distanceMeters }
        val stopDetected = knownStops.any { it in section.startMeters..section.endMeters }
        val sectionGaps = gaps.filter { it.distanceMeters in section.startMeters..section.endMeters }
        val gpsGapDetected = sectionGaps.any { !it.bridgeable }
        val bridgedGapMillis = sectionGaps.filter { it.bridgeable }.sumOf { it.durationMillis }
        val invalidReason = when {
            stopDetected -> EffortInvalidReason.STOP
            gpsGapDetected -> EffortInvalidReason.GPS_GAP
            else -> null
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
            sampleQuality = (100 - uncertainty * 3 - (bridgedGapMillis / 1_000L * 2).coerceAtMost(30)).toInt().coerceIn(0, 100),
            lateralOffsetMeters = offsets.average(), lateralUncertaintyMeters = uncertainty,
            valid = invalidReason == null,
            invalidReason = invalidReason,
            reachedWithoutPriorStop = knownStops.none { it in trailStartMeters..<section.startMeters },
            estimated = bridgedGapMillis > 0,
            bridgedGapMillis = bridgedGapMillis,
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

    private fun coordinateDistanceMeters(latitudeA: Double, longitudeA: Double, latitudeB: Double, longitudeB: Double): Double =
        RideMath.distanceMeters(
            LocationSample(0, latitudeA, longitudeA, 0f, 0.0),
            LocationSample(0, latitudeB, longitudeB, 0f, 0.0),
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

private fun List<Double>.median(): Double? = if (isEmpty()) null else sorted().let { values ->
    if (values.size % 2 == 1) values[values.size / 2]
    else (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
}

class RideProcessor(private val database: FlightLogDatabase) {
    private val dao = database.dao()

    suspend fun materializeMissingJumpTraces() {
        dao.jumpsMissingMotionTrace().forEach { jump ->
            val telemetry = JumpMotionTrace.loadRaw(dao, jump)
            if (telemetry.hasUsableSamples) dao.insertJumpMotionTrace(JumpMotionTrace.encode(jump.id, telemetry))
        }
    }

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
                dao.deleteStopEventsForRide(rideId)
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
        val retainedJumpTelemetry = dao.jumps(rideId).mapNotNull { jump ->
            dao.jumpMotionTrace(jump.id)?.let { trace -> runCatching { JumpMotionTrace.decode(trace) }.getOrNull() }
        }
        val motionTelemetry = MotionTelemetry.merge(
            motionChunks.map { TelemetryCodec.decodeMotion(it.payload, it.checksum) } + retainedJumpTelemetry,
        )
        val motion = motionTelemetry.accelerationFrames()
        val analyzedJumps = if (
            motionTelemetry.hasUsableSamples &&
            motionTelemetry.encodingVersion >= TelemetryCodec.LEGACY_MULTICHANNEL_MOTION_ENCODING_VERSION
        ) {
            dao.jumps(rideId).map { jump ->
                val analysis = JumpSensorAnalyzer.analyze(jump, JumpMotionTrace.samples(jump, motionTelemetry), ride.mountingMode)
                jump.copy(
                    estimatedHeightMeters = analysis.airtimeHeightMeters,
                    confidence = analysis.estimatedConfidence,
                    estimatedFlightKind = analysis.estimatedFlightKind,
                    flightKindConfidence = analysis.flightKindConfidence,
                )
            }
        } else {
            emptyList()
        }
        val previousProfiles = dao.spatialProfiles(rideId)
        val profiles = TrailAnalysis.preservePermanentSignals(
            rebuilt = TrailAnalysis.spatialProfiles(rideId, points, motion, ride.mountingMode),
            previous = previousProfiles,
        )
        val stopEvents = TrailAnalysis.stopEvents(rideId, ride.uuid, points)
        database.withTransaction {
            if (existingGps.isEmpty()) {
                points.chunked(10_000).forEach { chunk ->
                    val encoded = TelemetryCodec.encodeGps(chunk)
                    dao.insertTelemetryChunk(encoded.toEntity(rideId, TelemetryKind.GPS, expiresAt = null))
                }
            }
            dao.deleteStopEventsForRide(rideId)
            if (stopEvents.isNotEmpty()) dao.insertStopEvents(stopEvents)
            analyzedJumps.forEach { dao.updateJump(it) }
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
        val rides = dao.allRides()
        database.withTransaction {
            dao.deletePassesForTrail(trailId)
            rides.forEach { createMatchedPasses(trail, canonical, it, includeEfforts = false) }
            syncPauseZones(trail)
            syncSplitSections(trail)
            dao.deletePassesForTrail(trailId)
            rides.forEach { createMatchedPasses(trail, canonical, it, includeEfforts = true) }
            dao.updateTrail(trail.copy(supportCount = dao.passes(trailId).map { it.rideId }.distinct().size))
        }
    }

    suspend fun rebuildAllTrails() {
        dao.allTrails().forEach { rebuildTrail(it.id) }
    }

    private suspend fun analyzeTrail(rideId: Long, profiles: List<SpatialProfileEntity>) {
        if (profiles.size < 20) return
        val trails = dao.allTrails()
        val matches = mutableListOf<Triple<TrailEntity, List<SpatialProfileEntity>, TrailAnalysis.Match>>()
        trails.forEach { trail ->
            val canonical = dao.spatialProfiles(trail.canonicalRideId)
            val selectedCanonical = canonical.filter { it.distanceMeters in trail.startMeters..trail.endMeters }
            TrailAnalysis.match(profiles, selectedCanonical)?.let { matches += Triple(trail, canonical, it) }
        }
        val match = matches.maxByOrNull { it.third.confidence }
        if (match == null) {
            val trailId = dao.insertTrail(TrailEntity(
                name = "Trail near ride ${rideId}", canonicalRideId = rideId,
                lengthMeters = profiles.last().distanceMeters, endMeters = profiles.last().distanceMeters,
            ))
            val sections = TrailAnalysis.suggestedSections(trailId, profiles)
            sections.forEach { dao.insertSection(it) }
            rebuildTrail(trailId)
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
            rebuildTrail(trail.id)
        }
    }

    private suspend fun createMatchedPasses(
        trail: TrailEntity,
        canonical: List<SpatialProfileEntity>,
        ride: com.example.flightlog.data.RideEntity,
        includeEfforts: Boolean,
    ) {
        val profiles = dao.spatialProfiles(ride.id)
        val match = if (ride.id == trail.canonicalRideId) {
            TrailAnalysis.Match(100, profiles.map { it to it })
        } else {
            TrailAnalysis.match(profiles, canonical.filter { it.distanceMeters in trail.startMeters..trail.endMeters })
        }
        if (match != null) createPasses(trail, ride, match, includeEfforts)
    }

    private suspend fun createPasses(
        trail: TrailEntity,
        ride: com.example.flightlog.data.RideEntity,
        match: TrailAnalysis.Match,
        includeEfforts: Boolean,
    ) {
        if (match.normalized.isEmpty()) return
        val segments = mutableListOf<MutableList<Pair<SpatialProfileEntity, SpatialProfileEntity>>>()
        match.normalized.sortedBy { it.first.recordedAt }.forEach { pair ->
            val current = segments.lastOrNull()
            val prior = current?.lastOrNull()
            if (current == null || (prior != null && pair.second.distanceMeters < prior.second.distanceMeters - 20.0)) {
                segments += mutableListOf(pair)
            } else current += pair
        }
        segments.filter { it.size >= 10 }.forEach { normalized ->
            createPass(trail, ride, match.confidence, normalized, includeEfforts)
        }
    }

    private suspend fun createPass(
        trail: TrailEntity,
        ride: com.example.flightlog.data.RideEntity,
        confidence: Int,
        normalized: List<Pair<SpatialProfileEntity, SpatialProfileEntity>>,
        includeEfforts: Boolean,
    ) {
        val sorted = normalized.sortedBy { it.first.recordedAt }
        val provisionalStops = TrailAnalysis.mapStopObservations(
            trail.id, trail.uuid, 0, sorted, dao.stopEvents(ride.id),
        ).filter { it.distanceMeters in trail.startMeters..trail.endMeters }
        val gaps = TrailAnalysis.gapAssessments(sorted)
        val hasReversal = sorted.zipWithNext().any { (a, b) -> b.second.distanceMeters < a.second.distanceMeters - 20.0 }
        val completeCoverage = TrailAnalysis.hasContinuousRangeCoverage(
            sorted, trail.startMeters, trail.endMeters,
        )
        val unbridgeableGap = gaps.any { !it.bridgeable && it.distanceMeters in trail.startMeters..trail.endMeters }
        val bridgedGapMillis = gaps.filter { it.bridgeable && it.distanceMeters in trail.startMeters..trail.endMeters }
            .sumOf { it.durationMillis }
        val interrupted = hasReversal || unbridgeableGap
        val passUuid = UUID.nameUUIDFromBytes(
            "${trail.uuid}:${ride.uuid}:${sorted.first().first.recordedAt}".toByteArray(StandardCharsets.UTF_8),
        ).toString()
        val passId = dao.insertPass(TrailPassEntity(
            uuid = passUuid,
            trailId = trail.id, rideId = ride.id,
            startedAt = sorted.first().first.recordedAt, endedAt = sorted.last().first.recordedAt,
            startMeters = sorted.minOf { it.second.distanceMeters },
            endMeters = sorted.maxOf { it.second.distanceMeters },
            matchConfidence = confidence, interrupted = interrupted,
            completeCoverage = completeCoverage,
            stopCount = provisionalStops.size,
            stoppedDurationMillis = provisionalStops.sumOf { it.durationMillis },
            hasReversal = hasReversal,
            bridgedGapMillis = bridgedGapMillis,
            fullRunEligible = completeCoverage && provisionalStops.isEmpty() && !interrupted,
        ))
        val observations = provisionalStops.map { it.copy(passId = passId) }
        if (observations.isNotEmpty()) dao.insertStopObservations(observations)
        if (!includeEfforts) return
        val stopDistances = observations.map { it.distanceMeters }
        val efforts = dao.sections(trail.id).mapNotNull { section ->
            TrailAnalysis.effort(
                passId, section, sorted, stopDistances, gaps, trail.startMeters,
            )?.copy(uuid = UUID.nameUUIDFromBytes(
                "$passUuid:${section.uuid}".toByteArray(StandardCharsets.UTF_8),
            ).toString())
        }
        if (efforts.isNotEmpty()) dao.insertEfforts(efforts)
    }

    private suspend fun syncPauseZones(trail: TrailEntity) {
        val observations = dao.stopObservations(trail.id)
        val passes = dao.passes(trail.id)
        val candidates = TrailAnalysis.pauseZoneCandidates(trail, observations, passes)
            .sortedByDescending { it.confidence }
        val existing = dao.pauseZones(trail.id).toMutableList()
        val activeBounds = existing.filter {
            it.state == PauseZoneState.AUTOMATIC || it.state == PauseZoneState.USER_LOCKED
        }.map { it.startMeters to it.endMeters }.toMutableList()
        candidates.forEach { candidate ->
            val prior = existing.minByOrNull { abs((it.startMeters + it.endMeters) / 2.0 - candidate.centerMeters) }
                ?.takeIf { abs((it.startMeters + it.endMeters) / 2.0 - candidate.centerMeters) <= 20.0 }
            if (prior != null) {
                val conflictsWithOtherActive = activeBounds.any { (start, end) ->
                    val representsPrior = abs(start - prior.startMeters) < .1 && abs(end - prior.endMeters) < .1
                    val gap = maxOf(0.0, maxOf(start, candidate.startMeters) - minOf(end, candidate.endMeters))
                    !representsPrior && gap < 30.0
                }
                val upgradedState = when (prior.state) {
                    PauseZoneState.USER_LOCKED, PauseZoneState.DISMISSED -> prior.state
                    PauseZoneState.AUTOMATIC -> PauseZoneState.AUTOMATIC
                    PauseZoneState.CANDIDATE -> if (candidate.active && !conflictsWithOtherActive) {
                        PauseZoneState.AUTOMATIC
                    } else PauseZoneState.CANDIDATE
                }
                val updated = prior.copy(
                    startMeters = if (prior.state == PauseZoneState.USER_LOCKED) prior.startMeters else candidate.startMeters,
                    endMeters = if (prior.state == PauseZoneState.USER_LOCKED) prior.endMeters else candidate.endMeters,
                    state = upgradedState,
                    supportCount = candidate.supportCount,
                    eligiblePassCount = candidate.eligiblePassCount,
                    confidence = candidate.confidence,
                    medianPauseMillis = candidate.medianPauseMillis,
                    updatedAt = System.currentTimeMillis(),
                )
                dao.updatePauseZone(updated)
                existing[existing.indexOf(prior)] = updated
                if (prior.state == PauseZoneState.CANDIDATE && updated.state == PauseZoneState.AUTOMATIC) {
                    activeBounds += updated.startMeters to updated.endMeters
                }
                return@forEach
            }
            val conflictsWithActive = activeBounds.any { (start, end) ->
                val gap = maxOf(0.0, maxOf(start, candidate.startMeters) - minOf(end, candidate.endMeters))
                gap < 30.0
            }
            val state = if (candidate.active && !conflictsWithActive) PauseZoneState.AUTOMATIC else PauseZoneState.CANDIDATE
            val zone = TrailPauseZoneEntity(
                uuid = UUID.nameUUIDFromBytes(
                    "${trail.uuid}:pause:${(candidate.centerMeters / 5.0).toInt()}".toByteArray(StandardCharsets.UTF_8),
                ).toString(),
                trailId = trail.id,
                name = "Pause ${existing.size + 1}",
                startMeters = candidate.startMeters,
                endMeters = candidate.endMeters,
                state = state,
                supportCount = candidate.supportCount,
                eligiblePassCount = candidate.eligiblePassCount,
                confidence = candidate.confidence,
                medianPauseMillis = candidate.medianPauseMillis,
            )
            val id = dao.insertPauseZone(zone)
            existing += zone.copy(id = id)
            if (state == PauseZoneState.AUTOMATIC) activeBounds += zone.startMeters to zone.endMeters
        }
    }

    private suspend fun syncSplitSections(trail: TrailEntity) {
        val zones = dao.pauseZones(trail.id).filter {
            it.state == PauseZoneState.AUTOMATIC || it.state == PauseZoneState.USER_LOCKED
        }.sortedBy { it.startMeters }
        data class DesiredSplit(val start: Double, val end: Double, val preceding: Long?, val following: Long?)
        val desired = mutableListOf<DesiredSplit>()
        if (zones.isEmpty()) {
            desired += DesiredSplit(trail.startMeters, trail.endMeters, null, null)
        } else {
            var start = trail.startMeters
            var preceding: Long? = null
            zones.forEach { zone ->
                if (zone.startMeters - start >= 5.0) desired += DesiredSplit(start, zone.startMeters, preceding, zone.id)
                start = zone.endMeters
                preceding = zone.id
            }
            if (trail.endMeters - start >= 5.0) desired += DesiredSplit(start, trail.endMeters, preceding, null)
        }
        val existing = dao.sections(trail.id).filter { it.kind == SectionKind.SPLIT }
        val retainedIds = hashSetOf<Long>()
        desired.forEachIndexed { index, split ->
            val prior = existing.firstOrNull {
                it.precedingPauseZoneId == split.preceding && it.followingPauseZoneId == split.following
            }
            if (prior == null) {
                val id = dao.insertSection(TrailSectionEntity(
                    trailId = trail.id,
                    name = "Split ${index + 1}",
                    kind = SectionKind.SPLIT,
                    state = SectionState.CONFIRMED,
                    startMeters = split.start,
                    endMeters = split.end,
                    precedingPauseZoneId = split.preceding,
                    followingPauseZoneId = split.following,
                ))
                retainedIds += id
            } else {
                dao.updateSection(prior.copy(
                    name = if (prior.name.startsWith("Split ")) "Split ${index + 1}" else prior.name,
                    startMeters = split.start,
                    endMeters = split.end,
                ))
                retainedIds += prior.id
            }
        }
        existing.filter { it.id !in retainedIds }.forEach { dao.deleteSection(it.id) }
    }
}

fun EncodedTelemetry.toEntity(
    rideId: Long,
    kind: TelemetryKind,
    expiresAt: Long?,
): TelemetryChunkEntity = TelemetryChunkEntity(
    uuid = UUID.randomUUID().toString(), rideId = rideId, kind = kind,
    startedAt = startedAt, endedAt = endedAt, encodingVersion = encodingVersion,
    sampleCount = sampleCount, payload = payload, checksum = checksum, expiresAt = expiresAt,
)
