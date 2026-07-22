package com.example.flightlog.tracking

import android.content.Context

data class TrailMatchingOptions(
    val coveragePercent: Int = 95,
    val corridorMeters: Int = 15,
    val forwardProgressPercent: Int = 80,
    val continuityGapMeters: Int = 30,
    val minimumPoints: Int = 5,
)

object TrailMatchingOptionsStore {
    private const val FILE = "trail_matching_options"
    fun read(context: Context): TrailMatchingOptions = context.getSharedPreferences(FILE, 0).let { prefs ->
        TrailMatchingOptions(
            coveragePercent = prefs.getInt("coverage", 95), corridorMeters = prefs.getInt("corridor", 15),
            forwardProgressPercent = prefs.getInt("progress", 80), continuityGapMeters = prefs.getInt("gap", 15),
            minimumPoints = prefs.getInt("points", 5),
        )
    }
    fun save(context: Context, value: TrailMatchingOptions) {
        context.getSharedPreferences(FILE, 0).edit()
            .putInt("coverage", value.coveragePercent).putInt("corridor", value.corridorMeters)
            .putInt("progress", value.forwardProgressPercent).putInt("gap", value.continuityGapMeters)
            .putInt("points", value.minimumPoints).apply()
    }
}
