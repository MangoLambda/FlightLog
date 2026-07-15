package com.example.flightlog.maps

import android.content.Context
import com.example.flightlog.BuildConfig

enum class MapStyle(
    val displayName: String,
    internal val thunderforestStyle: String,
) {
    OPEN_CYCLE_MAP("OpenCycleMap", "cycle"),
    CLEAN_TERRAIN("Clean terrain", "landscape"),
}

object MapStyleStore {
    private const val PREFERENCES = "map_preferences"
    private const val MAP_STYLE = "map_style"

    fun read(context: Context): MapStyle {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(MAP_STYLE, null)
        return MapStyle.entries.firstOrNull { it.name == stored } ?: MapStyle.OPEN_CYCLE_MAP
    }

    fun save(context: Context, style: MapStyle) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(MAP_STYLE, style.name)
            .apply()
    }
}

sealed interface MapProvider {
    val displayName: String
    fun styleJson(): String

    data object OfflineCanvas : MapProvider {
        override val displayName = "Offline trail canvas"
        override fun styleJson() = OFFLINE_STYLE
    }

    data class Thunderforest(private val apiKey: String, private val mapStyle: MapStyle) : MapProvider {
        override val displayName = mapStyle.displayName

        override fun styleJson(): String {
            require(apiKey.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid Thunderforest API key" }
            return """
                {
                  "version": 8,
                  "name": "FlightLog ${mapStyle.displayName}",
                  "sources": {
                    "thunderforest": {
                      "type": "raster",
                      "tiles": ["https://tile.thunderforest.com/${mapStyle.thunderforestStyle}/{z}/{x}/{y}.png?apikey=$apiKey"],
                      "tileSize": 256,
                      "maxzoom": 22,
                      "attribution": "Maps © Thunderforest, Data © OpenStreetMap contributors"
                    }
                  },
                  "layers": [
                    {"id":"background","type":"background","paint":{"background-color":"#101512"}},
                    {"id":"basemap","type":"raster","source":"thunderforest"}
                  ]
                }
            """.trimIndent()
        }
    }

    companion object {
        fun configured(
            apiKey: String = BuildConfig.THUNDERFOREST_API_KEY,
            mapStyle: MapStyle = MapStyle.OPEN_CYCLE_MAP,
        ): MapProvider {
            val key = apiKey.trim()
            return if (key.matches(Regex("[A-Za-z0-9_-]+"))) Thunderforest(key, mapStyle) else OfflineCanvas
        }

        private val OFFLINE_STYLE = """
            {
              "version": 8,
              "name": "FlightLog Offline",
              "sources": {},
              "layers": [
                {"id":"background","type":"background","paint":{"background-color":"#101512"}}
              ]
            }
        """.trimIndent()
    }
}
