package com.example.flightlog.export

import android.content.Context
import androidx.room.withTransaction
import com.example.flightlog.data.*
import com.example.flightlog.domain.*
import com.example.flightlog.tracking.TelemetryCodec
import com.example.flightlog.tracking.TrailAnalysis
import com.example.flightlog.tracking.toEntity
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

data class BackupImportResult(val ridesAdded: Int, val duplicateRides: Int)
private data class TelemetryMetadata(val version: Int, val sampleCount: Int, val startedAt: Long, val endedAt: Long)

class FlightLogBackup(
    private val context: Context,
    private val database: FlightLogDatabase,
) {
    private val dao = database.dao()

    suspend fun export(output: OutputStream) {
        val rides = dao.allRides()
        val rideUuids = rides.associate { it.id to it.uuid }
        val stopEvents = dao.allStopEvents()
        val stopEventUuids = stopEvents.associate { it.id to it.uuid }
        val trails = dao.allTrails()
        val trailUuids = trails.associate { it.id to it.uuid }
        val pauseZones = dao.allPauseZones()
        val pauseZoneUuids = pauseZones.associate { it.id to it.uuid }
        val sections = dao.allSections()
        val sectionUuids = sections.associate { it.id to it.uuid }
        val passes = dao.allPasses()
        val passUuids = passes.associate { it.id to it.uuid }
        val jumps = rides.flatMap { ride -> dao.jumps(ride.id) }
        val jumpsById = jumps.associateBy { it.id }
        val jumpTraces = dao.allJumpMotionTraces()
        val chunks = dao.allTelemetryChunks().toMutableList()
        rides.filter { ride -> chunks.none { it.rideId == ride.id && it.kind == TelemetryKind.GPS } }.forEach { ride ->
            dao.trackPoints(ride.id).chunked(10_000).filter { it.isNotEmpty() }.forEach { points ->
                chunks += TelemetryCodec.encodeGps(points).toEntity(ride.id, TelemetryKind.GPS, expiresAt = null)
            }
        }
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.json("manifest.json", JSONObject()
                .put("format", FORMAT_VERSION).put("analysisVersion", TrailAnalysis.ANALYSIS_VERSION)
                .put("createdAt", System.currentTimeMillis()).toString())
            zip.jsonLines("rides.jsonl", rides.map { it.json() })
            zip.jsonLines("stops.jsonl", stopEvents.map { it.json(rideUuids.getValue(it.rideId)) })
            zip.jsonLines("jumps.jsonl", jumps.map { it.json(rideUuids.getValue(it.rideId)) })
            zip.jsonLines("jump-traces.jsonl", jumpTraces.map { trace ->
                val jump = jumpsById.getValue(trace.jumpId)
                trace.json(rideUuids.getValue(jump.rideId), jump.takeoffAt)
            })
            zip.jsonLines("chunks.jsonl", chunks.map { it.json(rideUuids.getValue(it.rideId)) })
            zip.jsonLines("profiles.jsonl", dao.allSpatialProfiles().map { it.json(rideUuids.getValue(it.rideId)) })
            zip.jsonLines("trails.jsonl", trails.map { it.json(rideUuids.getValue(it.canonicalRideId)) })
            zip.jsonLines("pause-zones.jsonl", pauseZones.map { it.json(trailUuids.getValue(it.trailId)) })
            zip.jsonLines("sections.jsonl", sections.map {
                it.json(
                    trailUuids.getValue(it.trailId),
                    it.precedingPauseZoneId?.let(pauseZoneUuids::get),
                    it.followingPauseZoneId?.let(pauseZoneUuids::get),
                )
            })
            zip.jsonLines("passes.jsonl", passes.map { it.json(trailUuids.getValue(it.trailId), rideUuids.getValue(it.rideId)) })
            zip.jsonLines("stop-observations.jsonl", dao.allStopObservations().map {
                it.json(trailUuids.getValue(it.trailId), passUuids.getValue(it.passId), stopEventUuids.getValue(it.stopEventId))
            })
            zip.jsonLines("efforts.jsonl", dao.allEfforts().map { it.json(passUuids.getValue(it.passId), sectionUuids.getValue(it.sectionId)) })
            chunks.forEach { chunk ->
                zip.putNextEntry(ZipEntry("telemetry/${chunk.uuid}.bin"))
                zip.write(chunk.payload)
                zip.closeEntry()
            }
            jumpTraces.forEach { trace ->
                val jump = jumpsById.getValue(trace.jumpId)
                val rideUuid = rideUuids.getValue(jump.rideId)
                zip.putNextEntry(ZipEntry(jumpTracePath(rideUuid, jump.takeoffAt)))
                zip.write(trace.payload)
                zip.closeEntry()
            }
            rides.forEach { ride ->
                val points = loadPoints(ride)
                zip.json("tracks/${ride.uuid}.gpx", RideExporter.gpx(ride, points))
            }
        }
    }

    suspend fun import(input: InputStream): BackupImportResult {
        val directory = File(context.cacheDir, "backup-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            extractValidated(input, directory)
            val manifest = JSONObject(File(directory, "manifest.json").readText())
            val format = manifest.getInt("format")
            require(format in 1..FORMAT_VERSION) { "Unsupported FlightLog backup version" }
            if (format >= 2) require(V2_METADATA.all { File(directory, it).isFile }) { "Backup is incomplete" }
            if (format >= 3) require(V3_METADATA.all { File(directory, it).isFile }) { "Backup is incomplete" }
            return database.withTransaction { restore(directory) }
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun restore(directory: File): BackupImportResult {
        val rideIds = mutableMapOf<String, Long>()
        var added = 0
        var duplicates = 0
        directory.forEachJson("rides.jsonl") { json ->
            val uuid = json.requiredUuid("uuid")
            val existing = dao.rideByUuid(uuid)
            val ride = RideEntity(
                startedAt = json.getLong("startedAt"), endedAt = json.nullableLong("endedAt"),
                state = RideState.valueOf(json.getString("state")), distanceMeters = json.getDouble("distanceMeters"),
                movingTimeMillis = json.getLong("movingTimeMillis"), maxSpeedMps = json.getDouble("maxSpeedMps"),
                uuid = uuid, mountingMode = json.nullableString("mountingMode")?.let(MountingMode::valueOf),
                archivedAt = json.nullableLong("archivedAt"), analysisVersion = json.optInt("analysisVersion", 0),
            ).also { requireRideRanges(it) }
            val id = existing?.id ?: dao.insertRideIfAbsent(ride).also { require(it > 0) }
            if (existing == null) added++ else duplicates++
            rideIds[uuid] = id
        }
        val stopEventIds = mutableMapOf<String, Long>()
        directory.forEachJsonIfPresent("stops.jsonl") { json ->
            val uuid = json.requiredUuid("uuid")
            val existing = dao.stopEventByUuid(uuid)
            val startedAt = json.getLong("startedAt")
            val endedAt = json.getLong("endedAt").also { require(it >= startedAt) }
            val entity = StopEventEntity(
                uuid = uuid,
                rideId = rideIds[json.requiredUuid("rideUuid")] ?: error("Stop references unknown ride"),
                startedAt = startedAt,
                endedAt = endedAt,
                latitude = json.getDouble("latitude").also { require(it in -90.0..90.0) },
                longitude = json.getDouble("longitude").also { require(it in -180.0..180.0) },
                accuracyMeters = json.getDouble("accuracyMeters").toFloat().also { require(it in 0f..1_000f) },
                durationMillis = json.getLong("durationMillis").also { require(it >= 0) },
            )
            stopEventIds[uuid] = existing?.id ?: dao.insertStopEventIfAbsent(entity).also { require(it > 0) }
        }
        directory.forEachJson("jumps.jsonl") { json ->
            val rideId = rideIds[json.requiredUuid("rideUuid")] ?: error("Jump references unknown ride")
            val takeoffAt = json.getLong("takeoffAt")
            if (!dao.jumpExists(rideId, takeoffAt)) dao.insertJump(JumpEventEntity(
                rideId = rideId, takeoffAt = takeoffAt, landingAt = json.getLong("landingAt"),
                estimatedFlightSeconds = json.getDouble("estimatedFlightSeconds"),
                estimatedHeightMeters = json.getDouble("estimatedHeightMeters"),
                estimatedDistanceMeters = json.getDouble("estimatedDistanceMeters"),
                correctedFlightSeconds = json.nullableDouble("correctedFlightSeconds"),
                correctedHeightMeters = json.nullableDouble("correctedHeightMeters"),
                correctedDistanceMeters = json.nullableDouble("correctedDistanceMeters"),
                confidence = json.getInt("confidence").also { require(it in 0..100) },
                status = JumpStatus.valueOf(json.getString("status")), sensorQuality = SensorQuality.valueOf(json.getString("sensorQuality")),
                estimatedFlightKind = json.optString("estimatedFlightKind", FlightKind.UNCERTAIN.name).let(FlightKind::valueOf),
                correctedFlightKind = if (!json.has("correctedFlightKind") || json.isNull("correctedFlightKind")) null
                    else FlightKind.valueOf(json.getString("correctedFlightKind")),
                flightKindConfidence = json.optInt("flightKindConfidence", 0).also { require(it in 0..100) },
                latitude = json.nullableDouble("latitude"), longitude = json.nullableDouble("longitude"),
            ))
        }
        directory.forEachJsonIfPresent("jump-traces.jsonl") { json ->
            val rideUuid = json.requiredUuid("rideUuid")
            val rideId = rideIds[rideUuid] ?: error("Jump trace references unknown ride")
            val takeoffAt = json.getLong("takeoffAt")
            val jump = dao.jumpByTakeoff(rideId, takeoffAt) ?: error("Jump trace references unknown jump")
            val payload = File(directory, jumpTracePath(rideUuid, takeoffAt)).readBytes()
            val checksum = json.getString("checksum")
            val telemetry = TelemetryCodec.decodeMotion(payload, checksum)
            val startedAt = json.getLong("startedAt")
            val endedAt = json.getLong("endedAt")
            val sampleCount = json.getInt("sampleCount")
            require(sampleCount in 1..MAX_JUMP_TRACE_SAMPLES && telemetry.sampleCount == sampleCount)
            require(startedAt == telemetry.startedAt && endedAt == telemetry.endedAt)
            val window = com.example.flightlog.tracking.JumpMotionTrace.window(jump)
            require(startedAt >= window.first && endedAt <= window.last)
            dao.insertJumpMotionTrace(JumpMotionTraceEntity(
                jumpId = jump.id,
                startedAt = startedAt,
                endedAt = endedAt,
                encodingVersion = json.getInt("encodingVersion").also {
                    require(it == telemetry.encodingVersion)
                },
                sampleCount = sampleCount,
                payload = payload,
                checksum = checksum,
            ))
        }
        directory.forEachJson("chunks.jsonl") { json ->
            val uuid = json.requiredUuid("uuid")
            val rideId = rideIds[json.requiredUuid("rideUuid")] ?: error("Chunk references unknown ride")
            val payload = File(directory, "telemetry/$uuid.bin").readBytes()
            val checksum = json.getString("checksum")
            require(TelemetryCodec.checksum(payload) == checksum) { "Telemetry checksum mismatch" }
            val kind = TelemetryKind.valueOf(json.getString("kind"))
            val decoded = if (kind == TelemetryKind.GPS) {
                val points = TelemetryCodec.decodeGps(rideId, payload, checksum)
                TelemetryMetadata(
                    TelemetryCodec.GPS_ENCODING_VERSION,
                    points.size,
                    points.first().recordedAt,
                    points.last().recordedAt,
                )
            } else {
                val telemetry = TelemetryCodec.decodeMotion(payload, checksum)
                TelemetryMetadata(
                    telemetry.encodingVersion,
                    telemetry.sampleCount,
                    telemetry.startedAt ?: error("Motion telemetry is empty"),
                    telemetry.endedAt ?: error("Motion telemetry is empty"),
                )
            }
            val startedAt = json.getLong("startedAt")
            val endedAt = json.getLong("endedAt")
            val sampleCount = json.getInt("sampleCount")
            require(startedAt == decoded.startedAt && endedAt == decoded.endedAt && sampleCount == decoded.sampleCount) {
                "Telemetry metadata does not match its payload"
            }
            dao.insertTelemetryChunk(TelemetryChunkEntity(
                uuid = uuid, rideId = rideId, kind = kind,
                startedAt = startedAt, endedAt = endedAt,
                encodingVersion = json.getInt("encodingVersion").also { require(it == decoded.version) },
                sampleCount = sampleCount,
                payload = payload, checksum = checksum, expiresAt = json.nullableLong("expiresAt"),
            ))
        }
        val profileBatch = mutableListOf<SpatialProfileEntity>()
        directory.forEachJson("profiles.jsonl") { json ->
            profileBatch += SpatialProfileEntity(
                rideId = rideIds[json.requiredUuid("rideUuid")] ?: error("Profile references unknown ride"),
                distanceBin = json.getInt("distanceBin"), distanceMeters = json.getDouble("distanceMeters"),
                recordedAt = json.getLong("recordedAt"), latitude = json.getDouble("latitude").also { require(it in -90.0..90.0) },
                longitude = json.getDouble("longitude").also { require(it in -180.0..180.0) },
                altitudeMeters = json.nullableDouble("altitudeMeters")?.also { require(it in -1_000.0..20_000.0) },
                speedMps = json.getDouble("speedMps").also { require(it in 0.0..100.0) },
                accuracyMeters = json.getDouble("accuracyMeters").toFloat().also { require(it in 0f..1_000f) },
                observedSpanMillis = json.optLong("observedSpanMillis", 0).also { require(it >= 0) },
                maximumSampleGapMillis = json.optLong("maximumSampleGapMillis", 0).also { require(it >= 0) },
                roughnessScore = json.nullableDouble("roughnessScore"),
                roughnessKind = json.nullableString("roughnessKind")?.let(RoughnessKind::valueOf),
                roughnessConfidence = json.nullableInt("roughnessConfidence"),
            )
            if (profileBatch.size == 1_000) { dao.insertSpatialProfiles(profileBatch.toList()); profileBatch.clear() }
        }
        if (profileBatch.isNotEmpty()) dao.insertSpatialProfiles(profileBatch)

        val trailIds = mutableMapOf<String, Long>()
        directory.forEachJson("trails.jsonl") { json ->
            val uuid = json.requiredUuid("uuid")
            val existing = dao.trailByUuid(uuid)
            val entity = TrailEntity(
                uuid = uuid, name = json.getString("name").take(80), state = TrailState.valueOf(json.getString("state")),
                canonicalRideId = rideIds[json.requiredUuid("canonicalRideUuid")] ?: error("Trail references unknown ride"),
                lengthMeters = json.getDouble("lengthMeters"), supportCount = json.getInt("supportCount"),
                startMeters = json.optDouble("startMeters", 0.0), endMeters = json.optDouble("endMeters", json.getDouble("lengthMeters")),
                createdAt = json.getLong("createdAt"), updatedAt = json.getLong("updatedAt"),
            )
            trailIds[uuid] = existing?.id ?: dao.insertTrailIfAbsent(entity).also { require(it > 0) }
        }
        val sectionIds = mutableMapOf<String, Long>()
        val pauseZoneIds = mutableMapOf<String, Long>()
        directory.forEachJsonIfPresent("pause-zones.jsonl") { json ->
            val uuid = json.requiredUuid("uuid")
            val existing = dao.pauseZoneByUuid(uuid)
            val start = json.getDouble("startMeters")
            val end = json.getDouble("endMeters").also { require(it > start) }
            val entity = TrailPauseZoneEntity(
                uuid = uuid,
                trailId = trailIds[json.requiredUuid("trailUuid")] ?: error("Pause zone references unknown trail"),
                name = json.getString("name").take(80),
                startMeters = start,
                endMeters = end,
                state = PauseZoneState.valueOf(json.getString("state")),
                supportCount = json.getInt("supportCount").also { require(it >= 0) },
                eligiblePassCount = json.getInt("eligiblePassCount").also { require(it >= 0) },
                confidence = json.getInt("confidence").also { require(it in 0..100) },
                medianPauseMillis = json.getLong("medianPauseMillis").also { require(it >= 0) },
                createdAt = json.getLong("createdAt"),
                updatedAt = json.getLong("updatedAt"),
            )
            pauseZoneIds[uuid] = existing?.id ?: dao.insertPauseZoneIfAbsent(entity).also { require(it > 0) }
        }
        directory.forEachJson("sections.jsonl") { json ->
            val uuid = json.requiredUuid("uuid")
            val existing = dao.sectionByUuid(uuid)
            val start = json.getDouble("startMeters")
            val end = json.getDouble("endMeters").also { require(it > start) }
            val entity = TrailSectionEntity(
                uuid = uuid, trailId = trailIds[json.requiredUuid("trailUuid")] ?: error("Section references unknown trail"),
                name = json.getString("name").take(80), kind = SectionKind.valueOf(json.getString("kind")),
                state = SectionState.valueOf(json.getString("state")), startMeters = start, endMeters = end,
                precedingPauseZoneId = json.nullableString("precedingPauseZoneUuid")?.let {
                    pauseZoneIds[it] ?: error("Section references unknown preceding pause zone")
                },
                followingPauseZoneId = json.nullableString("followingPauseZoneUuid")?.let {
                    pauseZoneIds[it] ?: error("Section references unknown following pause zone")
                },
            )
            sectionIds[uuid] = existing?.id ?: dao.insertSectionIfAbsent(entity).also { require(it > 0) }
        }
        val passIds = mutableMapOf<String, Long>()
        directory.forEachJson("passes.jsonl") { json ->
            val uuid = json.requiredUuid("uuid")
            val existing = dao.passByUuid(uuid)
            val entity = TrailPassEntity(
                uuid = uuid, trailId = trailIds[json.requiredUuid("trailUuid")] ?: error("Pass references unknown trail"),
                rideId = rideIds[json.requiredUuid("rideUuid")] ?: error("Pass references unknown ride"),
                startedAt = json.getLong("startedAt"), endedAt = json.getLong("endedAt"),
                startMeters = json.getDouble("startMeters"), endMeters = json.getDouble("endMeters"),
                matchConfidence = json.getInt("matchConfidence").also { require(it in 0..100) },
                interrupted = json.getBoolean("interrupted"),
                completeCoverage = json.optBoolean("completeCoverage", false),
                stopCount = json.optInt("stopCount", 0).also { require(it >= 0) },
                stoppedDurationMillis = json.optLong("stoppedDurationMillis", 0).also { require(it >= 0) },
                hasReversal = json.optBoolean("hasReversal", false),
                bridgedGapMillis = json.optLong("bridgedGapMillis", 0).also { require(it >= 0) },
                fullRunEligible = json.optBoolean("fullRunEligible", false),
            )
            passIds[uuid] = existing?.id ?: dao.insertPassIfAbsent(entity).also { require(it > 0) }
        }
        directory.forEachJsonIfPresent("stop-observations.jsonl") { json ->
            dao.insertStopObservationIfAbsent(TrailStopObservationEntity(
                uuid = json.requiredUuid("uuid"),
                trailId = trailIds[json.requiredUuid("trailUuid")] ?: error("Stop observation references unknown trail"),
                passId = passIds[json.requiredUuid("passUuid")] ?: error("Stop observation references unknown pass"),
                stopEventId = stopEventIds[json.requiredUuid("stopEventUuid")] ?: error("Stop observation references unknown stop"),
                distanceMeters = json.getDouble("distanceMeters"),
                startMeters = json.getDouble("startMeters"),
                endMeters = json.getDouble("endMeters"),
                durationMillis = json.getLong("durationMillis").also { require(it >= 0) },
                confidence = json.getInt("confidence").also { require(it in 0..100) },
            ))
        }
        directory.forEachJson("efforts.jsonl") { json ->
            dao.insertEffortIfAbsent(SectionEffortEntity(
                uuid = json.requiredUuid("uuid"),
                passId = passIds[json.requiredUuid("passUuid")] ?: error("Effort references unknown pass"),
                sectionId = sectionIds[json.requiredUuid("sectionUuid")] ?: error("Effort references unknown section"),
                elapsedMillis = json.getLong("elapsedMillis"), entrySpeedMps = json.getDouble("entrySpeedMps"),
                minimumSpeedMps = json.getDouble("minimumSpeedMps"), averageSpeedMps = json.getDouble("averageSpeedMps"),
                exitSpeedMps = json.getDouble("exitSpeedMps"), maximumSpeedMps = json.getDouble("maximumSpeedMps"),
                roughnessScore = json.nullableDouble("roughnessScore"),
                roughnessKind = json.nullableString("roughnessKind")?.let(RoughnessKind::valueOf),
                sampleQuality = json.getInt("sampleQuality").also { require(it in 0..100) },
                lateralOffsetMeters = json.getDouble("lateralOffsetMeters"),
                lateralUncertaintyMeters = json.getDouble("lateralUncertaintyMeters"), valid = json.getBoolean("valid"),
                invalidReason = json.nullableString("invalidReason")?.let(EffortInvalidReason::valueOf),
                reachedWithoutPriorStop = json.optBoolean("reachedWithoutPriorStop", false),
                estimated = json.optBoolean("estimated", false),
                bridgedGapMillis = json.optLong("bridgedGapMillis", 0).also { require(it >= 0) },
            ))
        }
        return BackupImportResult(added, duplicates)
    }

    private suspend fun loadPoints(ride: RideEntity): List<TrackPointEntity> {
        val active = dao.trackPoints(ride.id)
        if (active.isNotEmpty()) return active
        return dao.telemetryChunks(ride.id).filter { it.kind == TelemetryKind.GPS }
            .flatMap { TelemetryCodec.decodeGps(ride.id, it.payload, it.checksum) }.sortedBy { it.recordedAt }
    }

    private fun extractValidated(input: InputStream, directory: File) {
        var total = 0L
        var first = true
        val seen = hashSetOf<String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory && name.length <= 180 && !name.startsWith('/') && '\\' !in name &&
                    name.split('/').none { it == ".." || it.isBlank() }) { "Unsafe backup entry" }
                require(seen.add(name)) { "Duplicate backup entry" }
                if (first) require(name == "manifest.json") { "Backup manifest must be first" }
                first = false
                val allowed = name in METADATA || name.startsWith("telemetry/") ||
                    name.startsWith("jump-traces/") || name.startsWith("tracks/")
                require(allowed) { "Unknown backup entry" }
                val target = File(directory, name)
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    val buffer = ByteArray(8_192)
                    var entryBytes = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        total += read
                        require(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) { "Backup exceeds size limit" }
                        output.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
        require(REQUIRED_METADATA.all { File(directory, it).isFile }) { "Backup is incomplete" }
    }

    private fun requireRideRanges(ride: RideEntity) {
        require(ride.startedAt > 0 && (ride.endedAt == null || ride.endedAt >= ride.startedAt))
        require(ride.distanceMeters in 0.0..10_000_000.0 && ride.movingTimeMillis >= 0 && ride.maxSpeedMps in 0.0..100.0)
    }

    companion object {
        const val MIME_TYPE = "application/zip"
        private const val FORMAT_VERSION = 5
        private const val MAX_JUMP_TRACE_SAMPLES = 10_000
        private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        private val REQUIRED_METADATA = setOf(
            "manifest.json", "rides.jsonl", "jumps.jsonl", "chunks.jsonl", "profiles.jsonl",
            "trails.jsonl", "sections.jsonl", "passes.jsonl", "efforts.jsonl",
        )
        private val V2_METADATA = setOf("stops.jsonl", "pause-zones.jsonl", "stop-observations.jsonl")
        private val V3_METADATA = setOf("jump-traces.jsonl")
        private val METADATA = REQUIRED_METADATA + V2_METADATA + V3_METADATA
    }
}

private fun jumpTracePath(rideUuid: String, takeoffAt: Long) = "jump-traces/$rideUuid-$takeoffAt.bin"

private fun ZipOutputStream.json(name: String, value: String) {
    putNextEntry(ZipEntry(name)); write(value.toByteArray(Charsets.UTF_8)); closeEntry()
}

private fun ZipOutputStream.jsonLines(name: String, values: Iterable<JSONObject>) =
    json(name, values.joinToString(separator = "\n", postfix = "\n") { it.toString() })

private suspend fun File.forEachJson(name: String, action: suspend (JSONObject) -> Unit) {
    File(this, name).bufferedReader().use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isNotBlank()) action(JSONObject(line))
        }
    }
}

private suspend fun File.forEachJsonIfPresent(name: String, action: suspend (JSONObject) -> Unit) {
    if (File(this, name).isFile) forEachJson(name, action)
}

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)
private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)
private fun JSONObject.nullableLong(name: String): Long? = if (isNull(name)) null else getLong(name)
private fun JSONObject.nullableInt(name: String): Int? = if (isNull(name)) null else getInt(name)
private fun JSONObject.nullableDouble(name: String): Double? = if (isNull(name)) null else getDouble(name)
private fun JSONObject.requiredUuid(name: String): String = getString(name).also { UUID.fromString(it) }

private fun RideEntity.json() = JSONObject().put("uuid", uuid).put("startedAt", startedAt).putNullable("endedAt", endedAt)
    .put("state", state.name).put("distanceMeters", distanceMeters).put("movingTimeMillis", movingTimeMillis)
    .put("maxSpeedMps", maxSpeedMps).putNullable("mountingMode", mountingMode?.name)
    .putNullable("archivedAt", archivedAt).put("analysisVersion", analysisVersion)
private fun StopEventEntity.json(rideUuid: String) = JSONObject().put("uuid", uuid).put("rideUuid", rideUuid)
    .put("startedAt", startedAt).put("endedAt", endedAt).put("latitude", latitude).put("longitude", longitude)
    .put("accuracyMeters", accuracyMeters).put("durationMillis", durationMillis)
private fun JumpEventEntity.json(rideUuid: String) = JSONObject().put("rideUuid", rideUuid).put("takeoffAt", takeoffAt).put("landingAt", landingAt)
    .put("estimatedFlightSeconds", estimatedFlightSeconds).put("estimatedHeightMeters", estimatedHeightMeters).put("estimatedDistanceMeters", estimatedDistanceMeters)
    .putNullable("correctedFlightSeconds", correctedFlightSeconds).putNullable("correctedHeightMeters", correctedHeightMeters).putNullable("correctedDistanceMeters", correctedDistanceMeters)
    .put("confidence", confidence).put("status", status.name).put("sensorQuality", sensorQuality.name)
    .put("estimatedFlightKind", estimatedFlightKind.name).putNullable("correctedFlightKind", correctedFlightKind?.name)
    .put("flightKindConfidence", flightKindConfidence).putNullable("latitude", latitude).putNullable("longitude", longitude)
private fun JumpMotionTraceEntity.json(rideUuid: String, takeoffAt: Long) = JSONObject()
    .put("rideUuid", rideUuid).put("takeoffAt", takeoffAt)
    .put("startedAt", startedAt).put("endedAt", endedAt).put("encodingVersion", encodingVersion)
    .put("sampleCount", sampleCount).put("checksum", checksum)
private fun TelemetryChunkEntity.json(rideUuid: String) = JSONObject().put("uuid", uuid).put("rideUuid", rideUuid).put("kind", kind.name)
    .put("startedAt", startedAt).put("endedAt", endedAt).put("encodingVersion", encodingVersion).put("sampleCount", sampleCount)
    .put("checksum", checksum).putNullable("expiresAt", expiresAt)
private fun SpatialProfileEntity.json(rideUuid: String) = JSONObject().put("rideUuid", rideUuid).put("distanceBin", distanceBin).put("distanceMeters", distanceMeters)
    .put("recordedAt", recordedAt).put("latitude", latitude).put("longitude", longitude).putNullable("altitudeMeters", altitudeMeters)
    .put("speedMps", speedMps).put("accuracyMeters", accuracyMeters)
    .put("observedSpanMillis", observedSpanMillis)
    .put("maximumSampleGapMillis", maximumSampleGapMillis)
    .putNullable("roughnessScore", roughnessScore).putNullable("roughnessKind", roughnessKind?.name).putNullable("roughnessConfidence", roughnessConfidence)
private fun TrailEntity.json(canonicalRideUuid: String) = JSONObject().put("uuid", uuid).put("name", name).put("state", state.name)
    .put("canonicalRideUuid", canonicalRideUuid).put("lengthMeters", lengthMeters).put("startMeters", startMeters).put("endMeters", endMeters)
    .put("supportCount", supportCount).put("createdAt", createdAt).put("updatedAt", updatedAt)
private fun TrailPauseZoneEntity.json(trailUuid: String) = JSONObject().put("uuid", uuid).put("trailUuid", trailUuid)
    .put("name", name).put("startMeters", startMeters).put("endMeters", endMeters).put("state", state.name)
    .put("supportCount", supportCount).put("eligiblePassCount", eligiblePassCount).put("confidence", confidence)
    .put("medianPauseMillis", medianPauseMillis).put("createdAt", createdAt).put("updatedAt", updatedAt)
private fun TrailSectionEntity.json(
    trailUuid: String,
    precedingPauseZoneUuid: String?,
    followingPauseZoneUuid: String?,
) = JSONObject().put("uuid", uuid).put("trailUuid", trailUuid).put("name", name)
    .put("kind", kind.name).put("state", state.name).put("startMeters", startMeters).put("endMeters", endMeters)
    .putNullable("precedingPauseZoneUuid", precedingPauseZoneUuid).putNullable("followingPauseZoneUuid", followingPauseZoneUuid)
private fun TrailPassEntity.json(trailUuid: String, rideUuid: String) = JSONObject().put("uuid", uuid).put("trailUuid", trailUuid).put("rideUuid", rideUuid)
    .put("startedAt", startedAt).put("endedAt", endedAt).put("startMeters", startMeters).put("endMeters", endMeters)
    .put("matchConfidence", matchConfidence).put("interrupted", interrupted).put("completeCoverage", completeCoverage)
    .put("stopCount", stopCount).put("stoppedDurationMillis", stoppedDurationMillis).put("hasReversal", hasReversal)
    .put("bridgedGapMillis", bridgedGapMillis).put("fullRunEligible", fullRunEligible)
private fun TrailStopObservationEntity.json(trailUuid: String, passUuid: String, stopEventUuid: String) = JSONObject()
    .put("uuid", uuid).put("trailUuid", trailUuid).put("passUuid", passUuid).put("stopEventUuid", stopEventUuid)
    .put("distanceMeters", distanceMeters).put("startMeters", startMeters).put("endMeters", endMeters)
    .put("durationMillis", durationMillis).put("confidence", confidence)
private fun SectionEffortEntity.json(passUuid: String, sectionUuid: String) = JSONObject().put("uuid", uuid).put("passUuid", passUuid).put("sectionUuid", sectionUuid)
    .put("elapsedMillis", elapsedMillis).put("entrySpeedMps", entrySpeedMps).put("minimumSpeedMps", minimumSpeedMps).put("averageSpeedMps", averageSpeedMps)
    .put("exitSpeedMps", exitSpeedMps).put("maximumSpeedMps", maximumSpeedMps).putNullable("roughnessScore", roughnessScore)
    .putNullable("roughnessKind", roughnessKind?.name).put("sampleQuality", sampleQuality).put("lateralOffsetMeters", lateralOffsetMeters)
    .put("lateralUncertaintyMeters", lateralUncertaintyMeters).put("valid", valid).putNullable("invalidReason", invalidReason?.name)
    .put("reachedWithoutPriorStop", reachedWithoutPriorStop).put("estimated", estimated).put("bridgedGapMillis", bridgedGapMillis)
