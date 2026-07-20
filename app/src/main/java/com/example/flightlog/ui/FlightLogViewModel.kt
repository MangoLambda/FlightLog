package com.example.flightlog.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.flightlog.FlightLogApplication
import com.example.flightlog.domain.JumpStatus
import com.example.flightlog.domain.FlightKind
import com.example.flightlog.domain.MountingMode
import com.example.flightlog.export.RideExporter
import com.example.flightlog.export.FlightLogBackup
import android.net.Uri
import com.example.flightlog.data.TrailSectionEntity
import com.example.flightlog.data.TrailPauseZoneEntity
import com.example.flightlog.data.TrailDefinitionDraft
import com.example.flightlog.data.TrailEditImpact
import com.example.flightlog.data.BulkRideDeletePreview
import com.example.flightlog.data.BulkRideDeleteResult
import com.example.flightlog.data.FeatureReviewEvidenceState
import com.example.flightlog.domain.SectionKind
import com.example.flightlog.domain.SectionState
import com.example.flightlog.maps.MapApiKeyStore
import com.example.flightlog.maps.MapStyle
import com.example.flightlog.maps.MapStyleStore
import com.example.flightlog.maps.MapTileCache
import com.example.flightlog.tracking.RecordingSettingsStore
import com.example.flightlog.tracking.TrackingState
import com.example.flightlog.tracking.TrailAnalysis
import com.example.flightlog.tracking.MotionTelemetry
import com.example.flightlog.update.AppUpdateManager
import com.example.flightlog.update.UpdateRelease
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen { RIDE, HISTORY, TRAILS, FEATURES, SETTINGS, REVIEW, JUMP_DETAIL, TRAIL_DETAIL, FEATURE_DETAIL }
sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Working : BackupUiState
    data class Success(val message: String) : BackupUiState
    data class Error(val message: String) : BackupUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class FlightLogViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FlightLogApplication
    private val repository = app.repository
    val rides = repository.rides.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val jumps = repository.jumps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val physicalFeatures = repository.physicalFeatures.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val featureObservations = repository.featureObservations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trails = repository.trails.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sections = repository.sections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val passes = repository.passes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val efforts = repository.efforts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pauseZones = repository.pauseZones.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val stopObservations = repository.stopObservations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val telemetryBytes = repository.telemetryBytes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val motionBytes = repository.motionBytes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val nextMotionExpiry = repository.nextMotionExpiry.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val estimatedProfileBytes = repository.estimatedProfileBytes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val rideStorageBytes = repository.rideStorageBytes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())
    val live = TrackingState.state
    val screen = MutableStateFlow(AppScreen.RIDE)
    val selectedRideId = MutableStateFlow<Long?>(null)
    val selectedJumpId = MutableStateFlow<Long?>(null)
    val selectedTrailId = MutableStateFlow<Long?>(null)
    val selectedFeatureId = MutableStateFlow<Long?>(null)
    val selectedReviewObservationId = MutableStateFlow<Long?>(null)
    val selectedPassAId = MutableStateFlow<Long?>(null)
    val selectedPassBId = MutableStateFlow<Long?>(null)
    val backupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val imperial = MutableStateFlow(
        application.getSharedPreferences("settings", 0).getBoolean("imperial", false),
    )
    val recordingSettings = MutableStateFlow(RecordingSettingsStore.read(application))
    val userMapApiKey = MutableStateFlow(MapApiKeyStore.userKey(application))
    val effectiveMapApiKey = MutableStateFlow(MapApiKeyStore.effectiveKey(application))
    val mapStyle = MutableStateFlow(MapStyleStore.read(application))
    val hasBundledMapApiKey = MapApiKeyStore.hasBundledKey()
    val tileCacheState = MapTileCache.state
    private val updateManager = AppUpdateManager(application)
    val updateState = updateManager.state

    fun checkForUpdate() = viewModelScope.launch { updateManager.check() }

    fun downloadUpdate(release: UpdateRelease) {
        val job = viewModelScope.launch { updateManager.download(release) }
        updateManager.attachDownloadJob(job)
    }

    fun cancelUpdateDownload() = updateManager.cancelDownload()
    fun skipUpdate(release: UpdateRelease) = updateManager.skip(release)
    fun dismissUpdateError() = updateManager.dismissError()
    fun installUpdate(activity: android.app.Activity) = updateManager.install(activity)
    fun resumeUpdateInstall(activity: android.app.Activity) = updateManager.resumeInstall(activity)

    val selectedPoints = selectedRideId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.trackPoints(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedRideJumps = selectedRideId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.jumps(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedJumpMotion = combine(selectedJumpId, selectedRideJumps) { jumpId, rideJumps ->
        rideJumps.firstOrNull { it.id == jumpId }
    }.flatMapLatest { jump ->
        if (jump == null) flowOf(MotionTelemetry.EMPTY) else repository.jumpMotion(jump)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MotionTelemetry.EMPTY)

    val featureReviewEvidence = selectedReviewObservationId.flatMapLatest { observationId ->
        if (observationId == null) flowOf(FeatureReviewEvidenceState.None)
        else flow {
            emit(FeatureReviewEvidenceState.Loading)
            val evidence = repository.featureReviewEvidence(observationId)
            emit(
                evidence?.let(FeatureReviewEvidenceState::Available)
                    ?: FeatureReviewEvidenceState.Unavailable(observationId, "Comparison evidence is no longer available."),
            )
        }.catch {
            emit(FeatureReviewEvidenceState.Unavailable(observationId, "Comparison evidence could not be loaded."))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeatureReviewEvidenceState.None)

    val selectedRidePeakGForces = selectedRideJumps.flatMapLatest { rideJumps ->
        if (rideJumps.isEmpty()) flowOf(emptyMap())
        else combine(rideJumps.map { jump ->
            repository.jumpMotion(jump).map { motion ->
                jump.id to filteredPeakGForce(jump, motion.accelerationFrames())
            }
        }) { peaks -> peaks.filter { it.second != null }.associate { it.first to requireNotNull(it.second) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val selectedRideStops = selectedRideId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.stops(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun comparisonProfiles(passId: MutableStateFlow<Long?>) = combine(passId, passes) { id, all ->
        all.firstOrNull { it.id == id }?.rideId
    }.flatMapLatest { rideId -> if (rideId == null) flowOf(emptyList()) else repository.observeSpatialProfiles(rideId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val comparisonProfilesA = comparisonProfiles(selectedPassAId)
    val comparisonProfilesB = comparisonProfiles(selectedPassBId)

    val selectedTrailProfiles = combine(selectedTrailId, trails) { trailId, allTrails ->
        allTrails.firstOrNull { it.id == trailId }?.canonicalRideId
    }.flatMapLatest { rideId ->
        if (rideId == null) flowOf(emptyList()) else repository.observeSpatialProfiles(rideId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openRide(rideId: Long) {
        selectedRideId.value = rideId
        selectedJumpId.value = null
        screen.value = AppScreen.REVIEW
    }

    fun selectJump(jumpId: Long) {
        selectedJumpId.value = jumpId
    }

    fun openJump(jumpId: Long) {
        selectedJumpId.value = jumpId
        screen.value = AppScreen.JUMP_DETAIL
    }

    fun deleteRide(rideId: Long) = viewModelScope.launch {
        if (repository.deleteRide(rideId)) {
            if (selectedRideId.value == rideId) selectedRideId.value = null
            screen.value = AppScreen.HISTORY
        }
    }

    suspend fun previewBulkRideDeletion(rideIds: Set<Long>): BulkRideDeletePreview =
        repository.previewBulkRideDeletion(rideIds)

    suspend fun deleteRides(preview: BulkRideDeletePreview): BulkRideDeleteResult {
        lateinit var result: BulkRideDeleteResult
        app.database.withTransaction {
            result = repository.applyBulkRideDeletion(preview)
            preview.retainedTrailIdsToRebuild.forEach { app.rideProcessor.rebuildTrail(it) }
        }
        if (selectedRideId.value in preview.rideIds) selectedRideId.value = null
        screen.value = AppScreen.HISTORY
        return result
    }

    fun openTrail(trailId: Long) {
        selectedTrailId.value = trailId
        val trailPasses = passes.value.filter { it.trailId == trailId }
        selectedPassAId.value = trailPasses.firstOrNull()?.id
        selectedPassBId.value = trailPasses.drop(1).firstOrNull()?.id
        screen.value = AppScreen.TRAIL_DETAIL
    }

    fun openFeature(featureId: Long) { selectedFeatureId.value = featureId; screen.value = AppScreen.FEATURE_DETAIL }
    fun selectFeatureReview(observationId: Long?) { selectedReviewObservationId.value = observationId }
    fun renameFeature(featureId: Long, name: String) = viewModelScope.launch { repository.renameFeature(featureId, name) }
    fun assignFeatureObservation(observationId: Long, featureId: Long?) = viewModelScope.launch { repository.assignObservation(observationId, featureId) }
    fun mergeFeatures(retainedId: Long, duplicateId: Long) = viewModelScope.launch { repository.mergeFeatures(retainedId, duplicateId) }
    fun splitFeature(featureId: Long, observationIds: Set<Long>) = viewModelScope.launch { repository.splitFeature(featureId, observationIds) }

    suspend fun trailProfiles(rideId: Long) = repository.spatialProfiles(rideId)

    suspend fun previewTrailDefinition(draft: TrailDefinitionDraft): TrailEditImpact =
        repository.previewTrailDefinition(draft)

    suspend fun saveTrailDefinition(draft: TrailDefinitionDraft, confirmedRemovalKeys: Set<String>): Long {
        var trailId = 0L
        app.database.withTransaction {
            trailId = repository.saveTrailDefinition(draft, confirmedRemovalKeys)
            app.rideProcessor.rebuildTrail(trailId)
        }
        selectedTrailId.value = trailId
        val trailPasses = repository.trailPasses(trailId)
        selectedPassAId.value = trailPasses.firstOrNull()?.id
        selectedPassBId.value = trailPasses.drop(1).firstOrNull()?.id
        screen.value = AppScreen.TRAIL_DETAIL
        return trailId
    }
    fun confirmSection(sectionId: Long) = viewModelScope.launch { repository.confirmSection(sectionId) }
    fun updateSection(sectionId: Long, name: String, startMeters: Double, endMeters: Double) = viewModelScope.launch {
        val trailId = sections.value.firstOrNull { it.id == sectionId }?.trailId
        repository.updateSection(sectionId, name, startMeters, endMeters)
        trailId?.let { app.rideProcessor.rebuildTrail(it) }
    }

    fun addManualSection(trailId: Long, name: String, startMeters: Double, endMeters: Double) = viewModelScope.launch {
        if (name.isBlank() || startMeters < 0 || endMeters <= startMeters) return@launch
        app.database.dao().insertSection(TrailSectionEntity(
            trailId = trailId, name = name.trim().take(80), kind = SectionKind.MANUAL,
            state = SectionState.CONFIRMED, startMeters = startMeters, endMeters = endMeters,
        ))
        app.rideProcessor.rebuildTrail(trailId)
    }

    fun updatePauseZone(zoneId: Long, name: String, startMeters: Double, endMeters: Double) = viewModelScope.launch {
        repository.updatePauseZone(zoneId, name, startMeters, endMeters)?.let { app.rideProcessor.rebuildTrail(it) }
    }

    fun addPauseZone(trailId: Long, startMeters: Double, endMeters: Double) = viewModelScope.launch {
        repository.addPauseZone(trailId, startMeters, endMeters)?.let { app.rideProcessor.rebuildTrail(it) }
    }

    fun dismissPauseZone(zoneId: Long, dismissed: Boolean) = viewModelScope.launch {
        repository.setPauseZoneDismissed(zoneId, dismissed)?.let { app.rideProcessor.rebuildTrail(it) }
    }

    internal fun savePauseZones(trailId: Long, drafts: List<PauseZoneDraft>) = viewModelScope.launch {
        app.database.withTransaction {
            val trail = app.database.dao().allTrails().firstOrNull { it.id == trailId } ?: return@withTransaction
            val existing = app.database.dao().pauseZones(trailId).associateBy { it.id }
            drafts.forEach { draft ->
                if (!draft.startMeters.isFinite() || !draft.endMeters.isFinite() ||
                    draft.startMeters < trail.startMeters || draft.endMeters > trail.endMeters ||
                    draft.endMeters - draft.startMeters < 5.0
                ) return@forEach
                val prior = draft.entityId?.let(existing::get)
                if (prior == null) {
                    app.database.dao().insertPauseZone(TrailPauseZoneEntity(
                        trailId = trailId,
                        name = draft.name.trim().take(80).ifBlank { "Pause area" },
                        startMeters = draft.startMeters,
                        endMeters = draft.endMeters,
                        state = draft.state,
                        supportCount = 0,
                        eligiblePassCount = 0,
                        confidence = 100,
                        medianPauseMillis = 0,
                    ))
                } else {
                    app.database.dao().updatePauseZone(prior.copy(
                        name = draft.name.trim().take(80).ifBlank { prior.name },
                        startMeters = draft.startMeters,
                        endMeters = draft.endMeters,
                        state = draft.state,
                        updatedAt = System.currentTimeMillis(),
                    ))
                }
            }
        }
        app.rideProcessor.rebuildTrail(trailId)
    }

    fun setJumpStatus(jumpId: Long, status: JumpStatus) = viewModelScope.launch {
        repository.setJumpStatus(jumpId, status)
    }

    fun setFlightKind(jumpId: Long, kind: FlightKind?) = viewModelScope.launch {
        repository.setCorrectedFlightKind(jumpId, kind)
    }

    fun setImperial(enabled: Boolean) {
        imperial.value = enabled
        getApplication<Application>().getSharedPreferences("settings", 0)
            .edit().putBoolean("imperial", enabled).apply()
    }

    fun setMountingMode(mode: MountingMode) {
        RecordingSettingsStore.setMountingMode(getApplication(), mode)
        recordingSettings.value = recordingSettings.value.copy(mountingMode = mode)
    }

    fun setMinimumJumpHeight(mode: MountingMode, meters: Float) {
        RecordingSettingsStore.setMinimumHeight(getApplication(), mode, meters)
        recordingSettings.value = when (mode) {
            MountingMode.POCKET -> recordingSettings.value.copy(pocketMinimumHeightMeters = meters)
            MountingMode.BIKE_MOUNTED -> recordingSettings.value.copy(mountedMinimumHeightMeters = meters)
        }
    }

    fun saveThunderforestApiKey(key: String): Boolean {
        val previousKey = effectiveMapApiKey.value
        if (!MapApiKeyStore.saveUserKey(getApplication(), key)) return false
        userMapApiKey.value = MapApiKeyStore.userKey(getApplication())
        effectiveMapApiKey.value = MapApiKeyStore.effectiveKey(getApplication())
        if (previousKey.isNotBlank() && previousKey != effectiveMapApiKey.value) {
            MapTileCache.clear(getApplication())
        }
        return true
    }

    fun clearThunderforestApiKey() {
        val hadUserKey = userMapApiKey.value.isNotBlank()
        MapApiKeyStore.clearUserKey(getApplication())
        userMapApiKey.value = ""
        effectiveMapApiKey.value = MapApiKeyStore.effectiveKey(getApplication())
        if (hadUserKey) MapTileCache.clear(getApplication())
    }

    fun setMapStyle(style: MapStyle) {
        MapStyleStore.save(getApplication(), style)
        mapStyle.value = style
    }

    fun clearMapTileCache() = MapTileCache.clear(getApplication())

    fun setMapTileCacheLimit(megabytes: Int) = MapTileCache.setLimit(getApplication(), megabytes)

    fun refreshMapTileCache() = MapTileCache.refresh(getApplication())

    fun shareRide(rideId: Long) = viewModelScope.launch {
        val ride = repository.ride(rideId) ?: return@launch
        val points = repository.pointSnapshot(rideId)
        val jumps = repository.jumpSnapshot(rideId)
        val exportDir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val gpx = File(exportDir, "flightlog-ride-$rideId.gpx").apply { writeText(RideExporter.gpx(ride, points)) }
        val csv = File(exportDir, "flightlog-ride-$rideId-jumps.csv").apply { writeText(RideExporter.jumpCsv(jumps)) }
        val authority = "${getApplication<Application>().packageName}.files"
        val uris = arrayListOf(
            FileProvider.getUriForFile(getApplication(), authority, gpx),
            FileProvider.getUriForFile(getApplication(), authority, csv),
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(Intent.createChooser(intent, "Export ride").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        backupState.value = BackupUiState.Working
        backupState.value = runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.use { output ->
                FlightLogBackup(getApplication(), app.database).export(output)
            } ?: error("Could not open the selected file")
            BackupUiState.Success("Backup exported")
        }.getOrElse { BackupUiState.Error(it.message ?: "Backup export failed") }
    }

    fun importBackup(uri: Uri) = viewModelScope.launch {
        backupState.value = BackupUiState.Working
        backupState.value = runCatching {
            val result = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                FlightLogBackup(getApplication(), app.database).import(input)
            } ?: error("Could not open the selected file")
            app.database.dao().ridesNeedingProcessing(TrailAnalysis.ANALYSIS_VERSION)
                .forEach { app.rideProcessor.compactAndAnalyze(it.id) }
            app.rideProcessor.rebuildAllTrails()
            BackupUiState.Success("Imported ${result.ridesAdded} rides; ${result.duplicateRides} already present")
        }.getOrElse { BackupUiState.Error(it.message ?: "Backup import failed") }
    }
}
