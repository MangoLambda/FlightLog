package com.example.flightlog.tracking

import android.content.Context
import com.example.flightlog.domain.MountingMode

data class RecordingSettings(
    val mountingMode: MountingMode = MountingMode.POCKET,
    val pocketMinimumHeightMeters: Float = DEFAULT_POCKET_MINIMUM_HEIGHT_METERS,
    val mountedMinimumHeightMeters: Float = DEFAULT_MOUNTED_MINIMUM_HEIGHT_METERS,
) {
    val activeMinimumHeightMeters: Float
        get() = when (mountingMode) {
            MountingMode.POCKET -> pocketMinimumHeightMeters
            MountingMode.BIKE_MOUNTED -> mountedMinimumHeightMeters
        }

    companion object {
        const val MINIMUM_HEIGHT_METERS = 0.05f
        const val MAXIMUM_HEIGHT_METERS = 1.0f
        const val DEFAULT_POCKET_MINIMUM_HEIGHT_METERS = 0.20f
        const val DEFAULT_MOUNTED_MINIMUM_HEIGHT_METERS = 0.15f
    }
}

object RecordingSettingsStore {
    private const val PREFERENCES = "settings"
    private const val MOUNTING_MODE = "mounting_mode"
    private const val POCKET_MINIMUM_HEIGHT = "pocket_minimum_jump_height_meters"
    private const val MOUNTED_MINIMUM_HEIGHT = "mounted_minimum_jump_height_meters"

    fun read(context: Context): RecordingSettings {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val mode = runCatching {
            MountingMode.valueOf(preferences.getString(MOUNTING_MODE, null) ?: MountingMode.POCKET.name)
        }.getOrDefault(MountingMode.POCKET)
        return RecordingSettings(
            mountingMode = mode,
            pocketMinimumHeightMeters = preferences.getFloat(
                POCKET_MINIMUM_HEIGHT,
                RecordingSettings.DEFAULT_POCKET_MINIMUM_HEIGHT_METERS,
            ).coerceIn(RecordingSettings.MINIMUM_HEIGHT_METERS, RecordingSettings.MAXIMUM_HEIGHT_METERS),
            mountedMinimumHeightMeters = preferences.getFloat(
                MOUNTED_MINIMUM_HEIGHT,
                RecordingSettings.DEFAULT_MOUNTED_MINIMUM_HEIGHT_METERS,
            ).coerceIn(RecordingSettings.MINIMUM_HEIGHT_METERS, RecordingSettings.MAXIMUM_HEIGHT_METERS),
        )
    }

    fun setMountingMode(context: Context, mode: MountingMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(MOUNTING_MODE, mode.name)
            .apply()
    }

    fun setMinimumHeight(context: Context, mode: MountingMode, meters: Float) {
        val key = when (mode) {
            MountingMode.POCKET -> POCKET_MINIMUM_HEIGHT
            MountingMode.BIKE_MOUNTED -> MOUNTED_MINIMUM_HEIGHT
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putFloat(key, meters.coerceIn(RecordingSettings.MINIMUM_HEIGHT_METERS, RecordingSettings.MAXIMUM_HEIGHT_METERS))
            .apply()
    }
}
