package com.glipverup.app.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.projection.MediaProjection
import android.os.Build
import com.glipverup.app.permissions.PermissionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val audioEncoderProvider: () -> MediaCodec?,
    private val muxerManagerProvider: () -> MuxerManager?
) {
    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    private var audioSampleCount = 0L

    @SuppressLint("MissingPermission")
    suspend fun initialize(projection: MediaProjection) {
        android.util.Log.d("AudioRecorder", "initialize: START")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && audioRecord == null) {
            if (PermissionHandler.hasAudioPermission(context)) {
                try {
                    delay(500)
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    var focusRequest: AudioFocusRequest? = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        android.util.Log.d("AudioRecorder", "initialize: Requesting AudioFocus")
                        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                            .build()
                        am.requestAudioFocus(focusRequest)
                        delay(200)
                    }

                    android.util.Log.d("AudioRecorder", "initialize: Setting up AudioPlaybackCaptureConfiguration")
                    val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                        .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()

                    val sampleRate = 48000
                    val minBufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                    val bufferSize = maxOf(minBufSize, 4096 * 8)

                    android.util.Log.d("AudioRecorder", "initialize: Building AudioRecord (sampleRate=$sampleRate, bufferSize=$bufferSize)")
                    val record = try {
                        val builder = AudioRecord.Builder()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            builder.setContext(context)
                        }
                        builder.setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                            .setBufferSizeInBytes(bufferSize)
                            .setAudioPlaybackCaptureConfig(config)
                            .build()
                    } catch (e: Exception) {
                        android.util.Log.e("AudioRecorder", "initialize: Failed to build AudioRecord", e)
                        null
                    }

                    if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord = record
                        delay(500)
                        if (record.state == AudioRecord.STATE_INITIALIZED) {
                            try {
                                android.util.Log.d("AudioRecorder", "initialize: Starting recording")
                                record.startRecording()
                                android.util.Log.d("AudioRecorder", "initialize: SUCCESS")
                            } catch (e: Exception) {
                                android.util.Log.e("AudioRecorder", "initialize: Failed to start recording", e)
                            }
                        } else {
                            android.util.Log.e("AudioRecorder", "initialize: record state became uninitialized after delay")
                            record.release()
                        }
                    } else {
                        android.util.Log.e("AudioRecorder", "initialize: record is null or not initialized (state=${record?.state})")
                        record?.release()
                    }

                    focusRequest?.let { am.abandonAudioFocusRequest(it) }

                } catch (e: Exception) {
                    android.util.Log.e("AudioRecorder", "initialize: CRITICAL ERROR", e)
                }
            } else {
                android.util.Log.e("AudioRecorder", "initialize: No audio permission")
            }
        } else {
            android.util.Log.d("AudioRecorder", "initialize: SKIPPED (SDK_INT=${Build.VERSION.SDK_INT}, audioRecordExisting=${audioRecord != null})")
        }
    }

    fun startLoop(isRecording: AtomicBoolean, pendingRotationRestart: () -> Boolean) {
        android.util.Log.d("AudioRecorder", "startLoop: START")
        audioSampleCount = 0L
        audioJob = scope.launch(Dispatchers.IO) {
            val audioPCMBuffer = ByteBuffer.allocateDirect(4096)
            var totalRead = 0L
            while (isRecording.get() && !pendingRotationRestart() && isActive) {
                val record = audioRecord ?: run { 
                    android.util.Log.w("AudioRecorder", "startLoop: audioRecord is null, exiting")
                    delay(100); return@launch 
                }
                val encoder = audioEncoderProvider() ?: run { delay(10); continue }

                audioPCMBuffer.clear()
                val read = try { record.read(audioPCMBuffer, audioPCMBuffer.capacity()) } catch (e: Exception) { -1 }
                if (read > 0) {
                    try {
                        val inputIndex = encoder.dequeueInputBuffer(1000)
                        if (inputIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inputIndex)
                            inputBuffer?.clear()
                            audioPCMBuffer.limit(read)
                            inputBuffer?.put(audioPCMBuffer)

                            val manager = muxerManagerProvider()
                            if (manager != null && manager.audioTimelineOffsetUs == -1L) {
                                manager.audioTimelineOffsetUs = System.nanoTime() / 1000
                                android.util.Log.d("AudioRecorder", "startLoop: Set audioTimelineOffsetUs=${manager.audioTimelineOffsetUs}")
                            }

                            val ptsUs = audioSampleCount * 1_000_000L / 48000L
                            encoder.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                            audioSampleCount += (read / 4)
                            totalRead += read
                            if (totalRead % (48000 * 4 * 5) == 0L) { // 5秒おきにログ
                                android.util.Log.d("AudioRecorder", "startLoop: Audio data flowing, totalRead=$totalRead")
                            }
                        }
                    } catch (e: Exception) {
                        if (isRecording.get()) {
                            android.util.Log.w("AudioRecorder", "Audio encoder error: ${e.message}")
                        }
                    }
                } else if (read < 0) {
                    android.util.Log.e("AudioRecorder", "startLoop: read error ($read)")
                    if (isRecording.get() && !pendingRotationRestart()) {
                        delay(100)
                        break
                    }
                }
            }
            android.util.Log.d("AudioRecorder", "startLoop: END, totalRead=$totalRead")
        }
    }

    fun stop() {
        audioJob?.cancel()
        audioJob = null
        audioRecord?.let {
            try {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            } catch (e: Exception) {}
            try { it.release() } catch (e: Exception) {}
        }
        audioRecord = null
    }
    
    fun isInitialized() = audioRecord != null
}
