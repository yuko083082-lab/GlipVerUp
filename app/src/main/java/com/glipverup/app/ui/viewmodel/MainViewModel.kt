package com.glipverup.app.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glipverup.app.data.SettingsManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsManager = SettingsManager(application)

    var isRecording by mutableStateOf(false)
        private set

    val quality: StateFlow<String> = settingsManager.qualityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Medium")

    val resolution: StateFlow<String> = settingsManager.resolutionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "720p")

    val fps: StateFlow<Int> = settingsManager.fpsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 30)

    val bitrate: StateFlow<Int> = settingsManager.bitrateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 8)

    val bufferTime: StateFlow<String> = settingsManager.bufferTimeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "7 min")

    fun updateRecordingState(recording: Boolean) {
        isRecording = recording
    }

    fun updateBufferTime(value: String) {
        viewModelScope.launch {
            if (resolution.value == "4K") {
                if (value == "30 sec" || value == "15 sec") {
                    settingsManager.updateBufferTime(value)
                }
            } else {
                settingsManager.updateBufferTime(value)
            }
        }
    }

    fun updateQuality(value: String) {
        viewModelScope.launch { settingsManager.updateQuality(value) }
    }

    fun updateResolution(value: String) {
        viewModelScope.launch {
            settingsManager.updateResolution(value)
            if (value == "4K") {
                val current = bufferTime.value
                if (current != "30 sec" && current != "15 sec") {
                    settingsManager.updateBufferTime("30 sec")
                }
            }
        }
    }

    fun updateFps(value: Int) {
        viewModelScope.launch { settingsManager.updateFps(value) }
    }

    fun updateBitrate(value: Int) {
        viewModelScope.launch { settingsManager.updateBitrate(value) }
    }
}
