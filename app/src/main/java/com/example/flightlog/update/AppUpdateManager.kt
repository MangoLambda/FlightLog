package com.example.flightlog.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.flightlog.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateRelease(
    val tag: String,
    val version: String,
    val title: String,
    val notes: String,
    val assetName: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String,
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val release: UpdateRelease) : UpdateUiState
    data class Downloading(val release: UpdateRelease, val bytesRead: Long, val totalBytes: Long) : UpdateUiState
    data class AwaitingInstallPermission(val release: UpdateRelease) : UpdateUiState
    data class ReadyToInstall(val release: UpdateRelease) : UpdateUiState
    data class Error(val message: String, val release: UpdateRelease? = null) : UpdateUiState
}

object GitHubReleaseSource {
    fun latestReleaseApiUrl(baseUrl: String): String? {
        val match = Regex("^https://github\\.com/([^/]+)/([^/]+)/?$").matchEntire(baseUrl.trim()) ?: return null
        return "https://api.github.com/repos/${match.groupValues[1]}/${match.groupValues[2]}/releases/latest"
    }

    fun compareVersions(left: String, right: String): Int {
        fun parts(value: String): List<Int> {
            val values = value.removePrefix("v").split('.').map { it.toIntOrNull() }
            return if (values.any { it == null }) emptyList() else values.filterNotNull()
        }
        val a = parts(left)
        val b = parts(right)
        if (a.isEmpty() || b.isEmpty()) return 0
        return (0 until maxOf(a.size, b.size))
            .map { (a.getOrElse(it) { 0 }).compareTo(b.getOrElse(it) { 0 }) }
            .firstOrNull { it != 0 } ?: 0
    }

    fun parse(json: String, supportedAbis: List<String>): UpdateRelease? {
        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val tag = root.getString("tag_name")
        val version = tag.removePrefix("v")
        val assets = root.getJSONArray("assets")
        val byName = buildMap {
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                put(asset.getString("name"), asset)
            }
        }
        val prefixes = supportedAbis.map { "FlightLog-v$version-$it.apk" } + "FlightLog-v$version-universal.apk"
        val asset = prefixes.firstNotNullOfOrNull(byName::get) ?: return null
        val digest = asset.optString("digest")
        if (!digest.startsWith("sha256:") || digest.length != 71) return null
        return UpdateRelease(
            tag = tag,
            version = version,
            title = root.optString("name").ifBlank { "FlightLog $tag" },
            notes = root.optString("body"),
            assetName = asset.getString("name"),
            downloadUrl = asset.getString("browser_download_url"),
            size = asset.optLong("size", -1L),
            sha256 = digest.removePrefix("sha256:").lowercase(),
        )
    }
}

class AppUpdateManager(private val context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()
    private var downloadJob: Job? = null
    private var downloadedFile: File? = null
    private var downloadedRelease: UpdateRelease? = null

    suspend fun check() = withContext(Dispatchers.IO) {
        val apiUrl = GitHubReleaseSource.latestReleaseApiUrl(BuildConfig.FLIGHTLOG_UPDATE_BASE_URL) ?: return@withContext
        mutableState.value = UpdateUiState.Checking
        runCatching {
            val json = request(apiUrl)
            val release = GitHubReleaseSource.parse(json, Build.SUPPORTED_ABIS.toList()) ?: return@runCatching null
            if (GitHubReleaseSource.compareVersions(release.version, BuildConfig.VERSION_NAME) <= 0) return@runCatching null
            if (preferences.getString(SKIPPED_RELEASE, null) == release.tag) return@runCatching null
            release
        }.onSuccess { mutableState.value = it?.let(UpdateUiState::Available) ?: UpdateUiState.Idle }
            .onFailure { mutableState.value = UpdateUiState.Idle }
    }

    suspend fun download(release: UpdateRelease) = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        updateDir.listFiles()?.forEach(File::delete)
        val destination = File(updateDir, release.assetName)
        mutableState.value = UpdateUiState.Downloading(release, 0, release.size)
        try {
            val connection = open(release.downloadUrl)
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    var copied = 0L
                    while (input.read(buffer).also { read = it } >= 0) {
                        output.write(buffer, 0, read)
                        copied += read
                        mutableState.value = UpdateUiState.Downloading(release, copied, total)
                    }
                }
            }
            require(sha256(destination) == release.sha256) { "The downloaded APK failed its checksum check." }
            validateApk(destination)
            downloadedFile = destination
            downloadedRelease = release
            mutableState.value = UpdateUiState.ReadyToInstall(release)
        } catch (cancelled: CancellationException) {
            destination.delete()
            mutableState.value = UpdateUiState.Available(release)
            throw cancelled
        } catch (error: Exception) {
            destination.delete()
            mutableState.value = UpdateUiState.Error(error.message ?: "The update could not be downloaded.", release)
        }
    }

    fun attachDownloadJob(job: Job) { downloadJob = job }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun skip(release: UpdateRelease) {
        preferences.edit().putString(SKIPPED_RELEASE, release.tag).apply()
        mutableState.value = UpdateUiState.Idle
    }

    fun dismissError() { mutableState.value = UpdateUiState.Idle }

    fun install(activity: Activity) {
        val release = downloadedRelease ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            mutableState.value = UpdateUiState.AwaitingInstallPermission(release)
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        launchInstaller(activity)
    }

    fun resumeInstall(activity: Activity) {
        if (mutableState.value is UpdateUiState.AwaitingInstallPermission && activity.packageManager.canRequestPackageInstalls()) {
            launchInstaller(activity)
        }
    }

    private fun launchInstaller(activity: Activity) {
        val file = downloadedFile?.takeIf(File::exists) ?: return
        val release = downloadedRelease ?: return
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        mutableState.value = UpdateUiState.Idle
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File) {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags)) { "The downloaded file is not a valid APK." }
        require(archive.packageName == context.packageName) { "The update belongs to a different app." }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(archive.longVersionCode > installed.longVersionCode) { "The downloaded APK is not newer than this app." }
        fun signerDigests(signatures: Array<android.content.pm.Signature>) = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toList()
        }.toSet()
        val installedSigners = signerDigests(requireNotNull(installed.signingInfo).apkContentsSigners)
        val archiveSigners = signerDigests(requireNotNull(archive.signingInfo).apkContentsSigners)
        require(installedSigners == archiveSigners) { "The update was signed with a different key." }
    }

    private fun request(url: String): String = open(url).inputStream.bufferedReader().use { it.readText() }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "FlightLog/${BuildConfig.VERSION_NAME}")
        require(responseCode in 200..299) { "Update server returned HTTP $responseCode." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object { const val SKIPPED_RELEASE = "skipped_update_release" }
}
