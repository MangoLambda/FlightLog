package com.example.flightlog.maps

import android.content.Context
import com.example.flightlog.BuildConfig

object MapApiKeyStore {
    private const val PREFERENCES = "map_credentials"
    private const val THUNDERFOREST_API_KEY = "thunderforest_api_key"
    private val validKey = Regex("[A-Za-z0-9_-]{1,256}")

    fun isValid(key: String): Boolean = key.trim().matches(validKey)

    fun userKey(context: Context): String = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(THUNDERFOREST_API_KEY, "")
        .orEmpty()
        .takeIf(::isValid)
        .orEmpty()

    fun effectiveKey(context: Context): String = userKey(context)
        .ifBlank { BuildConfig.THUNDERFOREST_API_KEY.trim().takeIf(::isValid).orEmpty() }

    fun hasBundledKey(): Boolean = isValid(BuildConfig.THUNDERFOREST_API_KEY)

    fun saveUserKey(context: Context, key: String): Boolean {
        val normalized = key.trim()
        if (!isValid(normalized)) return false
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(THUNDERFOREST_API_KEY, normalized)
            .apply()
        return true
    }

    fun clearUserKey(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(THUNDERFOREST_API_KEY)
            .apply()
    }
}
