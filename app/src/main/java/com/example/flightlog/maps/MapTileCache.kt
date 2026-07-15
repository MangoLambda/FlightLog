package com.example.flightlog.maps

import android.content.Context
import java.io.File
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource

enum class TileCacheStatus { CONFIGURING, READY, CLEARING, CLEARED, ERROR }

data class TileCacheState(
    val status: TileCacheStatus = TileCacheStatus.CONFIGURING,
    val sizeBytes: Long = 0,
    val maxCacheMegabytes: Int = MapTileCache.DEFAULT_CACHE_MEGABYTES,
    val downloadedTilesThisMonth: Int = 0,
    val monthLabel: String = MapTileCache.monthLabel(),
    val errorMessage: String? = null,
)

object MapTileCache {
    const val DEFAULT_CACHE_MEGABYTES = 250
    const val MIN_CACHE_MEGABYTES = 50
    const val MAX_CACHE_MEGABYTES = 1_000
    const val CACHE_LIMIT_STEP_MEGABYTES = 50
    private const val BYTES_PER_MEGABYTE = 1_024L * 1_024L
    private const val PREFERENCES = "map_tile_cache"
    private const val CACHE_LIMIT = "ambient_cache_limit_megabytes"
    private const val DOWNLOADS_PREFIX = "downloaded_tiles_"
    private const val SIZE_REFRESH_INTERVAL_MILLIS = 2_000L
    private val mutableState = MutableStateFlow(TileCacheState())
    val state = mutableState.asStateFlow()
    private var lastSizeRefreshAt = 0L

    fun configure(context: Context) {
        val appContext = context.applicationContext
        val limit = readLimit(appContext)
        mutableState.value = snapshot(appContext, TileCacheStatus.CONFIGURING, limit)
        OfflineManager.getInstance(appContext).setMaximumAmbientCacheSize(
            limit * BYTES_PER_MEGABYTE,
            callback(appContext, TileCacheStatus.READY),
        )
    }

    fun setLimit(context: Context, megabytes: Int) {
        val normalized = normalizeLimit(megabytes)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putInt(CACHE_LIMIT, normalized)
            .apply()
        configure(context)
    }

    fun clear(context: Context) {
        if (mutableState.value.status == TileCacheStatus.CLEARING) return
        val appContext = context.applicationContext
        mutableState.value = snapshot(appContext, TileCacheStatus.CLEARING, readLimit(appContext))
        OfflineManager.getInstance(appContext).clearAmbientCache(
            callback(appContext, TileCacheStatus.CLEARED),
        )
    }

    @Synchronized
    fun recordDownloadedTile(context: Context) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val key = DOWNLOADS_PREFIX + monthKey()
        val count = preferences.getInt(key, 0) + 1
        preferences.edit().putInt(key, count).apply()
        val now = System.currentTimeMillis()
        mutableState.value = if (now - lastSizeRefreshAt >= SIZE_REFRESH_INTERVAL_MILLIS) {
            lastSizeRefreshAt = now
            snapshot(appContext, TileCacheStatus.READY, readLimit(appContext))
        } else {
            mutableState.value.copy(
                status = TileCacheStatus.READY,
                downloadedTilesThisMonth = count,
                monthLabel = monthLabel(),
            )
        }
    }

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        mutableState.value = snapshot(
            appContext,
            mutableState.value.status,
            readLimit(appContext),
            mutableState.value.errorMessage,
        )
    }

    fun normalizeLimit(megabytes: Int): Int {
        val clamped = megabytes.coerceIn(MIN_CACHE_MEGABYTES, MAX_CACHE_MEGABYTES)
        return ((clamped + CACHE_LIMIT_STEP_MEGABYTES / 2) / CACHE_LIMIT_STEP_MEGABYTES) * CACHE_LIMIT_STEP_MEGABYTES
    }

    fun monthKey(month: YearMonth = YearMonth.now()): String = month.toString()

    fun monthLabel(month: YearMonth = YearMonth.now()): String = month.format(
        DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()),
    )

    private fun readLimit(context: Context): Int = normalizeLimit(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getInt(CACHE_LIMIT, DEFAULT_CACHE_MEGABYTES),
    )

    private fun snapshot(
        context: Context,
        status: TileCacheStatus,
        limit: Int,
        errorMessage: String? = null,
    ): TileCacheState {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return TileCacheState(
            status = status,
            sizeBytes = cacheSizeBytes(context),
            maxCacheMegabytes = limit,
            downloadedTilesThisMonth = preferences.getInt(DOWNLOADS_PREFIX + monthKey(), 0),
            monthLabel = monthLabel(),
            errorMessage = errorMessage,
        )
    }

    private fun cacheSizeBytes(context: Context): Long {
        val directory = File(FileSource.getResourcesCachePath(context))
        return listOf("mbgl-offline.db", "mbgl-offline.db-wal", "mbgl-offline.db-shm")
            .sumOf { File(directory, it).takeIf(File::isFile)?.length() ?: 0L }
    }

    private fun callback(context: Context, successStatus: TileCacheStatus) = object : OfflineManager.FileSourceCallback {
        override fun onSuccess() {
            lastSizeRefreshAt = System.currentTimeMillis()
            mutableState.value = snapshot(context, successStatus, readLimit(context))
        }

        override fun onError(message: String) {
            mutableState.value = snapshot(
                context = context,
                status = TileCacheStatus.ERROR,
                limit = readLimit(context),
                errorMessage = message.take(160),
            )
        }
    }
}
