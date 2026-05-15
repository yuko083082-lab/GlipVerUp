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
        val FLOATING_X_KEY = intPreferencesKey("floating_x")
        val FLOATING_Y_KEY = intPreferencesKey("floating_y")
        val WIPEOUT_DETECTION_KEY = booleanPreferencesKey("wipeout_detection")
    }

    val floatingXFlow: Flow<Int?> = context.dataStore.data.map { it[FLOATING_X_KEY] }
    val floatingYFlow: Flow<Int?> = context.dataStore.data.map { it[FLOATING_Y_KEY] }

    val wipeoutDetectionFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[WIPEOUT_DETECTION_KEY] ?: false
    }

    suspend fun updateWipeoutDetection(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WIPEOUT_DETECTION_KEY] = enabled
        }
    }

    suspend fun saveFloatingPosition(x: Int, y: Int) {
        context.dataStore.edit { preferences ->
            preferences[FLOATING_X_KEY] = x
            preferences[FLOATING_Y_KEY] = y
        }
    }

    val bufferTimeFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[BUFFER_TIME_KEY] ?: "6 min"
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
