package com.glipverup.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    companion object {
        val QUALITY_KEY = stringPreferencesKey("quality")
        val RESOLUTION_KEY = stringPreferencesKey("resolution")
        val FPS_KEY = intPreferencesKey("fps")
        val BITRATE_KEY = intPreferencesKey("bitrate")
        val BUFFER_TIME_KEY = stringPreferencesKey("buffer_time")
    }

    val bufferTimeFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[BUFFER_TIME_KEY] ?: "7 min"
    }

    suspend fun updateBufferTime(bufferTime: String) {
        context.dataStore.edit { preferences ->
            preferences[BUFFER_TIME_KEY] = bufferTime
        }
    }

    val qualityFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[QUALITY_KEY] ?: "Medium"
    }

    val resolutionFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[RESOLUTION_KEY] ?: "720p"
    }

    val fpsFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[FPS_KEY] ?: 30
    }

    val bitrateFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[BITRATE_KEY] ?: 8
    }

    suspend fun updateQuality(quality: String) {
        context.dataStore.edit { preferences ->
            preferences[QUALITY_KEY] = quality
        }
    }

    suspend fun updateResolution(resolution: String) {
        context.dataStore.edit { preferences ->
            preferences[RESOLUTION_KEY] = resolution
        }
    }

    suspend fun updateFps(fps: Int) {
        context.dataStore.edit { preferences ->
            preferences[FPS_KEY] = fps
        }
    }

    suspend fun updateBitrate(bitrate: Int) {
        context.dataStore.edit { preferences ->
            preferences[BITRATE_KEY] = bitrate
        }
    }
}
