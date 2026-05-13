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
        viewModelScope.launch { settingsManager.updateBufferTime(value) }
    }

    fun updateQuality(value: String) {
        viewModelScope.launch { settingsManager.updateQuality(value) }
    }

    fun updateResolution(value: String) {
        viewModelScope.launch { settingsManager.updateResolution(value) }
    }

    fun updateFps(value: Int) {
        viewModelScope.launch { settingsManager.updateFps(value) }
    }

    fun updateBitrate(value: Int) {
        viewModelScope.launch { settingsManager.updateBitrate(value) }
    }
}
