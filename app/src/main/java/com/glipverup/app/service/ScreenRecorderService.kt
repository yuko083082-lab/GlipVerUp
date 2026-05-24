package com.glipverup.app.service

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.content.res.Configuration
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.glipverup.app.R
import com.glipverup.app.data.SettingsManager
import com.google.android.gms.ads.AdRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class ScreenRecorderService : Service() {

    override fun attachBaseContext(newBase: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            super.attachBaseContext(newBase.createAttributionContext("glip_recorder"))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun getAttributionTag(): String? {
        return "glip_recorder"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager
    private var audioManager: AudioManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var settingsManager: SettingsManager
    private var floatingView: View? = null
    private var videoEncoderSurface: Surface? = null

    private var captureWidth = 1280
    private var captureHeight = 720

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val CHANNEL_ID = "ZZZGlipChannel"
    private val NOTIFICATION_ID = 1001

    private val segmentDurationMs = 30000L
    private val segments = ConcurrentLinkedDeque<File>()
    private var sessionStartTimeMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val isRecording = AtomicBoolean(false)
    private val isSaving = AtomicBoolean(false)
    private val isAutoSavePending = AtomicBoolean(false)
    private var currentBufferTime = "6 min"
    private var isWipeoutDetectionEnabled = false

    // 状態管理
    private var lastFloatingX = 0
    private var lastFloatingY = 0

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    private var detectionJob: Job? = null
    private var lastDiagSaveTimeMs = 0L

    private var muxer: MediaMuxer? = null
    private val muxerLock = Any()
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var lastMuxerRotationTimeMs = 0L
    private var muxerStarted = false
    private var samplesWrittenToCurrentMuxer = false
    private var rotateMuxerNextLoop = false
    private var pendingRotationRestart = false

    private var persistedVideoFormat: MediaFormat? = null
    private var persistedAudioFormat: MediaFormat? = null

    private var audioSampleCount = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("ZZZGlip", "Service.onCreate. Context tag: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this.attributionTag else "N/A"}")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        settingsManager = SettingsManager(this)
        createNotificationChannel()

        serviceScope.launch {
            settingsManager.bufferTimeFlow.collectLatest { time ->
                currentBufferTime = time
                if (isRecording.get()) updateNotification()
            }
        }
        serviceScope.launch {
            settingsManager.wipeoutDetectionFlow.collectLatest { enabled ->
                isWipeoutDetectionEnabled = enabled
            }
        }

        serviceScope.launch {
            settingsManager.floatingXFlow.collectLatest { lastFloatingX = it ?: 0 }
        }
        serviceScope.launch {
            settingsManager.floatingYFlow.collectLatest { lastFloatingY = it ?: 0 }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_RECORDING" -> handleStartRecording(intent)
            "STOP_SERVICE" -> {
                stopRecording()
                sendBroadcast(Intent("com.glipverup.app.RECORDING_STOPPED").apply { setPackage(packageName) })
                stopSelf() // 録画停止時にサービスを完全に終了させる
            }
            "SAVE_BUFFER" -> saveLastMinutes()
            "CHANGE_TIME" -> {
                val newTime = intent.getStringExtra("selected_time")
                if (newTime != null) {
                    serviceScope.launch {
                        settingsManager.updateBufferTime(newTime)
                        // 設定変更後、通知を即座に更新
                        currentBufferTime = newTime
                        updateNotification()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun handleStartRecording(intent: Intent) {
        // 以前の録画が動いていれば完全に停止・解放を待つ
        if (isRecording.get()) {
            stopRecording()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, createNotification(currentBufferTime), type)
        } else {
            startForeground(NOTIFICATION_ID, createNotification(currentBufferTime))
        }

        // 非同期で初期化を進める (Android 14対策)
        serviceScope.launch(Dispatchers.Main) {
            delay(1000) // 前回プロセスの残骸や、直前の停止処理が完了するのを待機

            cleanLegacyFiles()
            sessionStartTimeMs = System.currentTimeMillis()

            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("data")
            } ?: return@launch

            try {
                Log.d("ZZZGlip", "Starting recording flow. Context tag: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this@ScreenRecorderService.attributionTag else "N/A"}")

                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

                // 前回セッションのMediaProjectionが残っている可能性を排除
                mediaProjection?.stop()
                mediaProjection = null

                val projection = projectionManager.getMediaProjection(resultCode, data)
                if (projection == null) {
                    Log.e("ZZZGlip", "Failed to get MediaProjection")
                    stopSelf()
                    return@launch
                }

                mediaProjection = projection

                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d("ZZZGlip", "MediaProjection stopped by system")
                        stopRecording()
                        sendBroadcast(Intent("com.glipverup.app.RECORDING_STOPPED").apply { setPackage(packageName) })
                        stopSelf()
                    }
                }, handler)

                // 1. 録画中フラグを立てる
                isRecording.set(true)

                // 2. AudioRecordを先行して初期化 (Android 14対策: 一度だけ作成)
                initializeAudioRecord(projection)

                // 3. フローティングボタンを表示
                showFloatingButton()

                // 4. 録画メインループを開始
                startRecordingMainLoop()
            } catch (e: Exception) {
                Log.e("ZZZGlip", "Failed to get projection", e)
                stopSelf()
            }
        }
    }

    private suspend fun startRecordingMainLoop() {
        withContext(Dispatchers.Default) {
            try {
                while (isRecording.get()) {
                    val currentProjection = mediaProjection ?: break
                    prepareAndStartRecording(currentProjection)

                    if (isRecording.get()) {
                        // 画面回転時は最小限の停止時間でエンコーダを再起動
                        stopEncoderOnly()
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                if (isRecording.get()) {
                    Log.e("ZZZGlip", "Recording outer loop failed", e)
                    withContext(Dispatchers.Main) {
                        stopRecording()
                        stopSelf()
                    }
                }
            }
        }
    }

    private suspend fun initializeAudioRecord(projection: MediaProjection) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && audioRecord == null) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    delay(500)
                    Log.d("ZZZGlip", "Initializing AudioRecord. Context tag: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this.attributionTag else "N/A"}")

                    // Audio Focus (GAIN for game audio capture)
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    var focusRequest: AudioFocusRequest? = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                            .build()
                        Log.d("ZZZGlip", "Requesting audio focus with GAIN")
                        am.requestAudioFocus(focusRequest)
                        delay(200)
                    }

                    // キャプチャ設定（Samsungデバイス互換性のためUSAGEを拡張）
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

                    val record = try {
                        val builder = AudioRecord.Builder()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            builder.setContext(this)
                            Log.d("ZZZGlip", "AudioRecord.Builder: Context set for Android 12+")
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
                        Log.e("ZZZGlip", "AudioRecord primary build failed: ${e.message}")
                        e.printStackTrace()
                        null
                    }

                    if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord = record
                        Log.d("ZZZGlip", "AudioRecord initialized successfully. Buffer size: $bufferSize")
                        delay(500)
                        if (record.state == AudioRecord.STATE_INITIALIZED) {
                            try {
                                record.startRecording()
                                Log.d("ZZZGlip", "AudioRecord started. state: ${record.recordingState}, session ID: ${record.audioSessionId}")
                            } catch (e: Exception) {
                                Log.e("ZZZGlip", "AudioRecord.startRecording failed", e)
                                e.printStackTrace()
                            }
                        } else {
                            Log.e("ZZZGlip", "AudioRecord state changed after delay: ${record.state}")
                        }
                    } else {
                        Log.e("ZZZGlip", "AudioRecord failed to initialize or state invalid. State: ${record?.state}, Null: ${record == null}")
                        record?.release()
                    }

                    // フォーカス解放
                    focusRequest?.let { am.abandonAudioFocusRequest(it) }

                } catch (e: Exception) {
                    Log.e("ZZZGlip", "AudioRecord initialization total error", e)
                }
            }
        }
    }

    private fun cleanLegacyFiles() {
        try {
            val files = cacheDir.listFiles()
            files?.forEach { if (it.name.startsWith("seg_")) it.delete() }
            segments.clear()
            persistedVideoFormat = null
            persistedAudioFormat = null
            Log.d("ZZZGlip", "Legacy files cleaned")
        } catch (e: Exception) { Log.e("ZZZGlip", "Clean failed", e) }
    }

    private suspend fun prepareAndStartRecording(projection: MediaProjection) {
        val resStr = settingsManager.resolutionFlow.first()
        val fps = settingsManager.fpsFlow.first()
        val bitrate = settingsManager.bitrateFlow.first()

        Log.d("ZZZGlip", "Preparing recording: Res=$resStr, FPS=$fps, Bitrate=$bitrate")

        val metrics = DisplayMetrics()
        withContext(Dispatchers.Main) {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }

        // 現在の画面の向きに合わせて解像度を決定
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val baseRes = when(resStr) {
            "480p" -> Pair(848, 480)
            "1080p" -> Pair(1920, 1080)
            "1440p" -> Pair(2560, 1440)
            else -> Pair(1280, 720)
        }

        val vW = if (isLandscape) maxOf(baseRes.first, baseRes.second) else minOf(baseRes.first, baseRes.second)
        val vH = if (isLandscape) minOf(baseRes.first, baseRes.second) else maxOf(baseRes.first, baseRes.second)

        captureWidth = vW
        captureHeight = vH

        // Video Encoder
        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vW, vH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1024 * 1024)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder?.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoEncoderSurface = videoEncoder?.createInputSurface()
        videoEncoder?.start()

        // Audio Encoder
        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder?.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder?.start()

        // VirtualDisplayの管理
        if (virtualDisplay == null) {
            virtualDisplay = projection.createVirtualDisplay("ZZZGlipCapture", vW, vH, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, videoEncoderSurface, null, null)
            Log.d("ZZZGlip", "VirtualDisplay created: ${vW}x${vH}")
        } else {
            virtualDisplay?.resize(vW, vH, metrics.densityDpi)
            virtualDisplay?.surface = videoEncoderSurface
            Log.d("ZZZGlip", "VirtualDisplay resized: ${vW}x${vH}")
        }

        if (isWipeoutDetectionEnabled) {
            setupWipeoutDetection()
        }

        rotateMuxer()
        lastMuxerRotationTimeMs = System.currentTimeMillis()

        // Wipeout検知のループを開始 (録画ループとは別に起動 / 二重起動防止)
        if (isWipeoutDetectionEnabled && (detectionJob == null || detectionJob?.isActive == false)) {
            detectionJob = serviceScope.launch(Dispatchers.Default) {
                startDetectionLoop()
            }
        }

        recordingLoop()
    }

    private fun rotateMuxer() {
        synchronized(muxerLock) {
            try {
                if (muxerStarted && samplesWrittenToCurrentMuxer) {
                    try {
                        muxer?.stop()
                    } catch (e: Exception) {
                        // 静かに失敗させる
                    }
                }
                muxer?.release()
            } catch (e: Exception) {
                // 静かに失敗させる
            }

            // 向きをファイル名に含める (_L: Landscape, _P: Portrait)
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val suffix = if (isLandscape) "L" else "P"
            val file = File(cacheDir, "seg_${System.currentTimeMillis()}_$suffix.mp4")
            segments.add(file)

            while (segments.size > 20) {
                val oldest = segments.pollFirst()
                if (oldest != null && oldest.exists()) oldest.delete()
            }

            try {
                if (pendingRotationRestart) {
                    persistedVideoFormat = null
                    persistedAudioFormat = null
                    pendingRotationRestart = false
                    videoTimelineOffsetUs = -1L
                    audioTimelineOffsetUs = -1L
                }

                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                videoTrackIndex = -1
                audioTrackIndex = -1
                muxerStarted = false
                samplesWrittenToCurrentMuxer = false
                segmentFirstPtsUs = -1L
                rotateMuxerNextLoop = false

                // 以前のセグメントのフォーマットがあれば即座にトラック追加してスタート可能にする
                persistedVideoFormat?.let { videoTrackIndex = muxer?.addTrack(it) ?: -1 }
                persistedAudioFormat?.let { audioTrackIndex = muxer?.addTrack(it) ?: -1 }
                checkMuxerStart()

                // バッファの切り替わりタイミングで1行だけログを出す（現在は30秒ごと）
                Log.d("ZZZGlip", "Buffer rotated: ${file.name} (Segments: ${segments.size})")
            } catch (e: Exception) {
                Log.e("ZZZGlip", "Failed to create new muxer", e)
            }
        }
    }

    private fun checkMuxerStart() {
        if (!muxerStarted && videoTrackIndex >= 0 && (audioTrackIndex >= 0 || audioRecord == null)) {
            try {
                muxer?.start()
                muxerStarted = true
            } catch (e: Exception) {
                Log.e("ZZZGlip", "Failed to start muxer", e)
            }
        }
    }

    private var videoTimelineOffsetUs = -1L
    private var audioTimelineOffsetUs = -1L
    private var segmentFirstPtsUs = -1L

    private suspend fun audioRecordingLoop(audioPCMBuffer: ByteBuffer) {
        Log.d("ZZZGlip", "Audio thread started. Recording: ${isRecording.get()}")
        var consecutiveSilentCount = 0

        audioLoop@while (isRecording.get() && !pendingRotationRestart) {
            val record = audioRecord ?: run { delay(100); break@audioLoop }

            audioPCMBuffer.clear()
            val read = try { record.read(audioPCMBuffer, audioPCMBuffer.capacity()) } catch (e: Exception) {
                Log.e("ZZZGlip", "AudioRecord.read EXCEPTION", e)
                -1
            }
            if (read > 0) {
                var maxVal = 0
                audioPCMBuffer.position(0)
                val shorts = audioPCMBuffer.asShortBuffer()
                for (i in 0 until (read / 2)) {
                    if (i < shorts.limit()) {
                        val v = abs(shorts.get(i).toInt())
                        if (v > maxVal) maxVal = v
                    }
                }
                audioPCMBuffer.position(0)

                if (maxVal > 0) {
                    consecutiveSilentCount = 0
                } else {
                    consecutiveSilentCount++
                }

                if (audioSampleCount % 100 == 0L) {
                    val state = if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) "RECORDING" else "STOPPED"
                    Log.d("ZZZGlip_Audio", "Read: $read bytes, Max amp: $maxVal, State: $state, Silent: $consecutiveSilentCount")
                }

                val inputIndex = try { audioEncoder?.dequeueInputBuffer(1000) ?: -1 } catch (e: Exception) {
                    Log.e("ZZZGlip_Audio", "Encoder dequeue error", e)
                    -1
                }
                if (inputIndex >= 0) {
                    val inputBuffer = audioEncoder?.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    audioPCMBuffer.limit(read)
                    inputBuffer?.put(audioPCMBuffer)

                    if (audioTimelineOffsetUs == -1L) {
                        audioTimelineOffsetUs = System.nanoTime() / 1000
                        Log.d("ZZZGlip", "Audio timeline offset set: $audioTimelineOffsetUs")
                    }

                    // サンプル数に基づいた精緻なPTS計算 (Drift排除)
                    val ptsUs = audioSampleCount * 1_000_000L / 48000L
                    audioEncoder?.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                    audioSampleCount += (read / 4)
                }
            } else if (read < 0) {
                Log.e("ZZZGlip", "AudioRecord.read returned error: $read")
                if (isRecording.get() && !pendingRotationRestart) {
                    delay(100)
                    currentCoroutineContext().cancel()
                    break@audioLoop
                }
            }
        }
        Log.d("ZZZGlip", "Audio thread finishing")
    }

    private suspend fun recordingLoop() {
        val vBufferInfo = MediaCodec.BufferInfo()
        val aBufferInfo = MediaCodec.BufferInfo()
        val audioPCMBuffer = ByteBuffer.allocateDirect(4096)

        val lastOrientation = resources.configuration.orientation
        segmentFirstPtsUs = -1L
        audioSampleCount = 0L
        videoTimelineOffsetUs = -1L
        audioTimelineOffsetUs = -1L
        lastMuxerRotationTimeMs = System.currentTimeMillis()
        pendingRotationRestart = false // リセット

        // 音声処理を別スレッドで開始
        audioJob = serviceScope.launch(Dispatchers.IO) {
            audioRecordingLoop(audioPCMBuffer)
        }

        while (isRecording.get() && !pendingRotationRestart) {
            val currentOrientation = resources.configuration.orientation
            if (currentOrientation != lastOrientation) {
                pendingRotationRestart = true
                rotateMuxerNextLoop = true
                break
            }

            if (System.currentTimeMillis() - lastMuxerRotationTimeMs > segmentDurationMs) {
                rotateMuxerNextLoop = true
                rotateMuxer()
                lastMuxerRotationTimeMs = System.currentTimeMillis()
                rotateMuxerNextLoop = false
            }

            // Video encoding
            videoEncoder?.let { encoder ->
                val outIdx = try { encoder.dequeueOutputBuffer(vBufferInfo, 1000) } catch (_: Exception) { -1 }
                if (outIdx >= 0) {
                    val buffer = encoder.getOutputBuffer(outIdx)
                    if (buffer != null) {
                        val nowUs = System.nanoTime() / 1000
                        if (videoTimelineOffsetUs == -1L) {
                            videoTimelineOffsetUs = nowUs - vBufferInfo.presentationTimeUs
                        }
                        val absoluteVideoPts = vBufferInfo.presentationTimeUs + videoTimelineOffsetUs

                        if (rotateMuxerNextLoop && (vBufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)) {
                            rotateMuxer()
                            lastMuxerRotationTimeMs = System.currentTimeMillis()
                            rotateMuxerNextLoop = false
                        }

                        synchronized(muxerLock) {
                            if (videoTrackIndex >= 0 && muxerStarted) {
                                if (segmentFirstPtsUs == -1L) {
                                    segmentFirstPtsUs = absoluteVideoPts
                                }
                                try {
                                    if ((vBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        return@let
                                    }
                                    val pts = absoluteVideoPts - segmentFirstPtsUs
                                    if (pts >= 0) {
                                        vBufferInfo.presentationTimeUs = pts
                                        muxer?.writeSampleData(videoTrackIndex, buffer, vBufferInfo)
                                        samplesWrittenToCurrentMuxer = true
                                    }
                                } catch (e: Exception) { Log.e("ZZZGlip", "Video write error", e) }
                            }
                        }
                    }
                    try { encoder.releaseOutputBuffer(outIdx, false) } catch (e: Exception) {}
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(muxerLock) {
                        persistedVideoFormat = encoder.outputFormat
                        if (videoTrackIndex < 0) {
                            videoTrackIndex = muxer?.addTrack(persistedVideoFormat!!) ?: -1
                            checkMuxerStart()
                        }
                    }
                }
            }

            // Audio encoding
            audioEncoder?.let { encoder ->
                val outIdx = try { encoder.dequeueOutputBuffer(aBufferInfo, 1000) } catch (_: Exception) { -1 }
                if (outIdx >= 0) {
                    val buffer = encoder.getOutputBuffer(outIdx)
                    if (buffer != null) {
                        val absoluteAudioPts = aBufferInfo.presentationTimeUs + audioTimelineOffsetUs
                        synchronized(muxerLock) {
                            if (audioTrackIndex >= 0 && muxerStarted) {
                                if (segmentFirstPtsUs == -1L) {
                                    segmentFirstPtsUs = absoluteAudioPts
                                }
                                try {
                                    if ((aBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        return@let
                                    }
                                    val pts = absoluteAudioPts - segmentFirstPtsUs
                                    if (pts >= 0) {
                                        aBufferInfo.presentationTimeUs = pts
                                        muxer?.writeSampleData(audioTrackIndex, buffer, aBufferInfo)
                                        samplesWrittenToCurrentMuxer = true
                                    }
                                } catch (e: Exception) { Log.e("ZZZGlip", "Audio write error", e) }
                            }
                        }
                    }
                    try { encoder.releaseOutputBuffer(outIdx, false) } catch (e: Exception) {}
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(muxerLock) {
                        persistedAudioFormat = encoder.outputFormat
                        if (audioTrackIndex < 0) {
                            audioTrackIndex = muxer?.addTrack(persistedAudioFormat!!) ?: -1
                            checkMuxerStart()
                        }
                    }
                }
            }
        }
        audioJob?.cancel()
        try { audioJob?.join() } catch (e: Exception) {}
        audioJob = null
    }

    private fun saveLastMinutes() {
        val targetMs = when(currentBufferTime) {
            "6 min" -> 360000L
            "5 min" -> 300000L
            "3 min" -> 180000L
            "1 min" -> 60000L
            "30 sec" -> 30000L
            "15 sec" -> 15000L
            else -> 15000L
        }
        saveBufferInternal(targetMs, isAutoSave = false)
    }

    private fun saveBufferInternal(targetMs: Long, isAutoSave: Boolean) {
        if (isSaving.getAndSet(true)) return
        Log.d("ZZZGlip_Save", "Save triggered. Target: $targetMs ms, AutoSave: $isAutoSave")

        serviceScope.launch(Dispatchers.Default) {
            try {
                // UI表示更新をメインスレッドで行う
                withContext(Dispatchers.Main) {
                    floatingView?.let { view ->
                        view.findViewById<View>(R.id.save_progress)?.visibility = View.VISIBLE
                        val saveBtn = view.findViewById<android.widget.TextView>(R.id.btn_save)
                        if (isAutoSave) {
                            if (saveBtn?.text != "WO!") saveBtn?.text = "WO!"
                        }
                        if (view.alpha != 0.4f) view.alpha = 0.4f
                    }
                }

                rotateMuxerNextLoop = true
                var waitCount = 0
                // このループは Dispatchers.Default で実行されるためメインスレッドを止めない
                while (rotateMuxerNextLoop && waitCount < 20) {
                    delay(100)
                    waitCount++
                }
                if (rotateMuxerNextLoop) {
                    rotateMuxer()
                    lastMuxerRotationTimeMs = System.currentTimeMillis()
                    rotateMuxerNextLoop = false
                }

                val landscapeCount = segments.count { it.name.endsWith("_L.mp4") }
                val portraitCount = segments.count { it.name.endsWith("_P.mp4") }
                val targetSuffix = if (landscapeCount >= portraitCount) "_L.mp4" else "_P.mp4"

                val available = segments.filter { it.exists() && it.name.endsWith(targetSuffix) && it.lastModified() >= (sessionStartTimeMs - 2000) }.toList()

                if (available.isEmpty()) {
                    Log.w("ZZZGlip_Save", "No segments available for merging. TargetSuffix: $targetSuffix, Total segments in list: ${segments.size}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ScreenRecorderService, "Wait a few seconds...", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                Log.d("ZZZGlip_Save", "Merging ${available.size} files for target: $targetMs ms")

                withContext(Dispatchers.IO) {
                    val fileName = "clip_${System.currentTimeMillis()}.mp4"
                    val pfd: ParcelFileDescriptor?
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/ZZZGlip")
                        }
                        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                        pfd = uri?.let { contentResolver.openFileDescriptor(it, "w") }
                    } else {
                        val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                        val appDir = File(dcimDir, "ZZZGlip").apply { if (!exists()) mkdirs() }
                        val outFile = File(appDir, fileName)
                        pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
                    }

                    if (pfd != null) {
                        fastMergeFiles(available, targetMs, pfd)
                        pfd.close()
                    }
                }
            } catch (e: Exception) {
                Log.e("ZZZGlip", "Save/Merge process failed", e)
            } finally {
                // 何が起きても必ず保存フラグをリセットし、UIを元に戻す
                isSaving.set(false)
                withContext(Dispatchers.Main) {
                    floatingView?.findViewById<View>(R.id.save_progress)?.visibility = View.GONE
                    floatingView?.findViewById<android.widget.TextView>(R.id.btn_save)?.text = "SAVE"
                    floatingView?.alpha = 0.6f
                }
            }
        }
    }

    private fun fastMergeFiles(files: List<File>, targetMs: Long, pfd: ParcelFileDescriptor) {
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val durations = files.associateWith { getFileDuration(it) }
        var accumulated = 0L
        val toMerge = mutableListOf<File>()
        for (f in files.reversed()) {
            val d = durations[f] ?: 0L
            if (d == 0L) continue
            toMerge.add(0, f)
            accumulated += d
            if (accumulated >= targetMs) break
        }
        if (toMerge.isEmpty()) { muxer.release(); return }

        var videoFmt: MediaFormat? = null
        var audioFmt: MediaFormat? = null
        for (f in toMerge) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(f.absolutePath)
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (videoFmt == null && mime.startsWith("video/")) videoFmt = fmt
                    if (audioFmt == null && mime.startsWith("audio/")) audioFmt = fmt
                }
            } catch (_: Exception) {} finally { ex.release() }
            if (videoFmt != null && audioFmt != null) break
        }

        if (videoFmt == null) { muxer.release(); return }
        val vTIdx = muxer.addTrack(videoFmt)
        val aTIdx = if (audioFmt != null) muxer.addTrack(audioFmt) else -1
        try { muxer.start() } catch (e: Exception) { muxer.release(); return }

        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var globalFileOffsetUs = 0L
        val startClipUs = if (accumulated > targetMs) (accumulated - targetMs) * 1000 else 0L
        var firstFramePtsInSession = -1L
        var samplesWritten = false

        for (i in toMerge.indices) {
            val f = toMerge[i]
            val e = MediaExtractor()
            try {
                e.setDataSource(f.absolutePath)
                val vi = (0 until e.trackCount).firstOrNull { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                val ai = (0 until e.trackCount).firstOrNull { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                var fileMinPtsUs = Long.MAX_VALUE
                if (vi != null) { e.selectTrack(vi); if (i == 0) e.seekTo(startClipUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC); if (e.sampleTime != -1L) fileMinPtsUs = minOf(fileMinPtsUs, e.sampleTime); e.unselectTrack(vi) }
                if (ai != null) { e.selectTrack(ai); if (i == 0) e.seekTo(startClipUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC); if (e.sampleTime != -1L) fileMinPtsUs = minOf(fileMinPtsUs, e.sampleTime); e.unselectTrack(ai) }
                if (fileMinPtsUs == Long.MAX_VALUE) fileMinPtsUs = 0L
                if (vi != null) e.selectTrack(vi)
                if (ai != null && aTIdx != -1) e.selectTrack(ai)
                if (i == 0) e.seekTo(startClipUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                else e.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var maxPtsInFile = 0L
                while (true) {
                    val trackIdxInFile = e.sampleTrackIndex
                    if (trackIdxInFile < 0) break
                    val targetTIdx = if (trackIdxInFile == vi) vTIdx else if (trackIdxInFile == ai) aTIdx else -1
                    if (targetTIdx == -1) { e.advance(); continue }
                    val size = e.readSampleData(buffer, 0)
                    if (size < 0) break
                    val rawPts = e.sampleTime
                    val pts = globalFileOffsetUs + (rawPts - fileMinPtsUs)
                    if (firstFramePtsInSession == -1L) firstFramePtsInSession = pts
                    @Suppress("WrongConstant") info.set(0, size, pts - firstFramePtsInSession, e.sampleFlags)
                    muxer.writeSampleData(targetTIdx, buffer, info)
                    maxPtsInFile = maxOf(maxPtsInFile, pts - firstFramePtsInSession)
                    samplesWritten = true
                    e.advance()
                }
                globalFileOffsetUs = firstFramePtsInSession + maxPtsInFile + 1000L
            } catch (e: Exception) { Log.e("ZZZGlip", "Error merge", e) } finally { e.release() }
        }
        try { if (samplesWritten) muxer.stop() } catch (e: Exception) {} finally { muxer.release() }
    }

    private fun getFileDuration(file: File): Long {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(file.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L }
        catch (_: Exception) { 0L } finally { r.release() }
    }

    private fun showFloatingButton() {
        serviceScope.launch {
            try {
                val savedX = settingsManager.floatingXFlow.first()
                val savedY = settingsManager.floatingYFlow.first()
                withContext(Dispatchers.Main) {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(metrics)
                    floatingView = LayoutInflater.from(this@ScreenRecorderService).inflate(R.layout.layout_floating_button, null).apply { alpha = 0.6f }
                    val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = savedX ?: (metrics.widthPixels - 200)
                        y = savedY ?: (metrics.heightPixels - 400)
                    }
                    floatingView?.setOnTouchListener(object : View.OnTouchListener {
                        private var iX = 0; private var iY = 0; private var itX = 0f; private var itY = 0f; private var mv = false
                        override fun onTouch(v: View, e: MotionEvent): Boolean {
                            when (e.action) {
                                MotionEvent.ACTION_DOWN -> { iX = params.x; iY = params.y; itX = e.rawX; itY = e.rawY; mv = false }
                                MotionEvent.ACTION_MOVE -> {
                                    val dx = (e.rawX - itX).toInt(); val dy = (e.rawY - itY).toInt()
                                    if (abs(dx) > 10 || abs(dy) > 10) {
                                        mv = true
                                        val m = DisplayMetrics(); @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
                                        params.x = (iX + dx).coerceIn(0, (m.widthPixels - v.width).coerceAtLeast(0))
                                        params.y = (iY + dy).coerceIn(0, (m.heightPixels - v.height).coerceAtLeast(0))
                                        windowManager.updateViewLayout(floatingView, params)
                                    }
                                }
                                MotionEvent.ACTION_UP -> { if (mv) { serviceScope.launch { settingsManager.saveFloatingPosition(params.x, params.y) } } else if (!isSaving.get()) { saveLastMinutes() } }
                            }
                            return true
                        }
                    })
                    windowManager.addView(floatingView, params)
                }
            } catch (e: Exception) { Log.e("ZZZGlip", "showFloatingButton error", e) }
        }
    }

    private fun setupWipeoutDetection() {
        Log.d("ZZZGlip", "Wipeout detection prepared")
    }

    private suspend fun startDetectionLoop() {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val backgroundExecutor = Dispatchers.Default.asExecutor()

        val localHandlerThread = HandlerThread("ZZZGlip-Detection").apply { start() }
        val currentBgHandler = Handler(localHandlerThread.looper)

        Log.d("ZZZGlip_Detection", "Pipeline started: Optimized Shape+OCR logic. Thread: ${localHandlerThread.name}")

        val isProcessing = AtomicBoolean(false)
        var reusableBitmap: Bitmap? = null

        try {
            var heartbeatCounter = 0
            while (isRecording.get() && isWipeoutDetectionEnabled) {
                delay(300)

                if (isProcessing.get()) continue

                heartbeatCounter++
                if (heartbeatCounter >= 15) {
                    Log.d("ZZZGlip_Detection", "Pipeline Heartbeat: Loop is running.")
                    heartbeatCounter = 0
                }

                val vd = virtualDisplay ?: continue
                
                // 巨大な文字をOCRに認識させるため、あえて超低解像度 (200px) に縮小
                val targetW = 200
                val targetH = (targetW * (captureHeight.toFloat() / captureWidth.toFloat())).toInt()

                val bitmap = if (reusableBitmap != null && reusableBitmap.width == targetW && reusableBitmap.height == targetH) {
                    reusableBitmap
                } else {
                    reusableBitmap?.recycle()
                    Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).also { reusableBitmap = it }
                }

                val completable = CompletableDeferred<Int>()
                currentBgHandler.post {
                    try {
                        val surface = vd.surface
                        if (surface != null && surface.isValid) {
                            PixelCopy.request(surface, bitmap, { result -> completable.complete(result) }, currentBgHandler)
                        } else {
                            completable.complete(PixelCopy.ERROR_UNKNOWN)
                        }
                    } catch (e: Exception) {
                        completable.completeExceptionally(e)
                    }
                }

                if (try { completable.await() } catch (e: Exception) { -1 } == PixelCopy.SUCCESS) {
                    isProcessing.set(true)

                    // 1. 超軽量な形状フィルタ (色に依存しないコントラスト判定)
                    if (!checkShapeContrast(bitmap)) {
                        isProcessing.set(false)
                        continue
                    }

                    // 調査用：形状フィルタを通った画像は全て保存 (OCRが何を見ているか確認するため)
                    if (com.glipverup.app.BuildConfig.DEBUG) {
                        saveDiagBitmap(bitmap, "SHAPE_PASS", 1.0f)
                    }

                    // フローティングボタンのマスク処理 (OCR誤検知防止)
                    val physMetrics = DisplayMetrics()
                    withContext(Dispatchers.Main) { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(physMetrics) }
                    val physW = physMetrics.widthPixels.toFloat(); val physH = physMetrics.heightPixels.toFloat()
                    val buttonRect = android.graphics.Rect()
                    withContext(Dispatchers.Main) { floatingView?.let { view -> val loc = IntArray(2); view.getLocationOnScreen(loc); buttonRect.set(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height) } }
                    if (!buttonRect.isEmpty) {
                        val canvas = android.graphics.Canvas(bitmap); val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.FILL }
                        val maskLeft = (buttonRect.left.toFloat() / physW) * targetW; val maskTop = (buttonRect.top.toFloat() / physH) * targetH
                        val maskRight = (buttonRect.right.toFloat() / physW) * targetW; val maskBottom = (buttonRect.bottom.toFloat() / physH) * targetH
                        canvas.drawRect(maskLeft, maskTop, maskRight, maskBottom, paint)
                    }

                    recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener(backgroundExecutor) { visionText ->
                        try {
                            if (com.glipverup.app.BuildConfig.DEBUG) {
                                val rawText = visionText.text.replace("\n", " ")
                                if (rawText.isNotBlank()) {
                                    Log.d("ZZZGlip_Diag", "Raw OCR: '$rawText'")
                                }
                            }
                            val blocks = visionText.textBlocks.filter { block ->
                                val rect = block.boundingBox ?: return@filter false
                                val normCenterY = rect.centerY().toFloat() / targetH
                                val normHeight = rect.height().toFloat() / targetH
                                // 画面中央付近で、ある程度高さがあるブロックを候補にする
                                (normCenterY in 0.25..0.75) && (normHeight > 0.05)
                            }

                            if (blocks.isEmpty()) return@addOnSuccessListener

                            // 全体のバウンディングボックスを計算
                            val minL = blocks.minOf { it.boundingBox?.left ?: 0 }
                            val maxR = blocks.maxOf { it.boundingBox?.right ?: 0 }
                            val minT = blocks.minOf { it.boundingBox?.top ?: 0 }
                            val maxB = blocks.maxOf { it.boundingBox?.bottom ?: 0 }
                            
                            val totalWidth = (maxR - minL).toFloat()
                            val totalHeight = (maxB - minT).toFloat()
                            val totalAspectRatio = if (totalHeight > 0) totalWidth / totalHeight else 0f
                            val normWidth = totalWidth / targetW
                            val normHeight = totalHeight / targetH

                            // OCR文字列の統合判定 (順序を考慮したシークエンスマッチ)
                            val fullText = blocks.joinToString("") { it.text }.uppercase().replace(Regex("[^A-Z]+"), "")
                            val sequenceMatchCount = if (fullText.isNotBlank()) isSequenceMatchCount(fullText, "WIPEOUT") else 0
                            
                            // 判定ロジック:
                            // A: 文字列が十分一致している (Levenshtein距離)
                            // B: 文字列は一部だが、形状(横長+巨大)がWIPEOUTそのもので、かつ正しい順序の文字が2文字以上ある
                            val isTextMatch = isFuzzyMatch(fullText, "WIPEOUT", if (fullText.length < 6) 2 else 3)
                            val isShapeMatch = totalAspectRatio in 2.8..9.0 && normWidth > 0.5 && normHeight > 0.08

                            if (isTextMatch || (isShapeMatch && sequenceMatchCount >= 2)) {
                                Log.i("ZZZGlip_Detection", "!!! WIPEOUT DETECTED !!!: Text='$fullText', SeqMatch=$sequenceMatchCount, Aspect=$totalAspectRatio")
                                handleWipeoutDetected()
                                return@addOnSuccessListener
                            } else if (com.glipverup.app.BuildConfig.DEBUG && (sequenceMatchCount >= 1 || isShapeMatch)) {
                                // デバッグ版かつ、確定しなかったが「おしい」場合のみ、ログと画像を保存
                                Log.d("ZZZGlip_Diag", "Candidate (Near-miss): Text='$fullText', SeqMatch=$sequenceMatchCount, Aspect=$totalAspectRatio, NormW=$normWidth")
                                saveDiagBitmap(bitmap, fullText, totalAspectRatio)
                            }

                        } finally {
                            isProcessing.set(false)
                        }
                    }.addOnFailureListener {
                        Log.e("ZZZGlip_Detection", "OCR failed", it)
                        isProcessing.set(false)
                    }
                }
            }
        } finally {
            recognizer.close()
            localHandlerThread.quitSafely()
            reusableBitmap?.recycle()
            Log.d("ZZZGlip_Detection", "Pipeline resources released.")
        }
    }


    private fun saveDiagBitmap(bitmap: Bitmap, text: String, aspect: Float) {
        val now = System.currentTimeMillis()
        if (now - lastDiagSaveTimeMs < 5000) return // クールダウン 5秒
        lastDiagSaveTimeMs = now

        serviceScope.launch(Dispatchers.IO) {
            try {
                val sdf = java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault())
                val timeStr = sdf.format(java.util.Date(now))
                val sanitizedText = text.take(10).replace(Regex("[^A-Z_]"), "")
                val fileName = "diag_${timeStr}_${sanitizedText}_${String.format(java.util.Locale.US, "%.2f", aspect)}.jpg"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/ZZZGlip/diag")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let {
                        contentResolver.openOutputStream(it)?.use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        }
                        Log.d("ZZZGlip_Diag", "Saved diagnostic image to DCIM via MediaStore: $fileName")
                    }
                } else {
                    val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                    val diagDir = File(dcimDir, "ZZZGlip/diag").apply { if (!exists()) mkdirs() }
                    val file = File(diagDir, fileName)
                    java.io.FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    }
                    Log.d("ZZZGlip_Diag", "Saved diagnostic image to DCIM: ${file.name}")
                }
            } catch (e: Exception) {
                Log.e("ZZZGlip_Diag", "Failed to save diagnostic image", e)
            }
        }
    }

    private fun handleWipeoutDetected() {
        if (isAutoSavePending.getAndSet(true)) return

        // UI更新をメインスレッドで最小限に行う
        serviceScope.launch(Dispatchers.Main) {
            floatingView?.let { view ->
                val saveBtn = view.findViewById<android.widget.TextView>(R.id.btn_save)
                if (saveBtn?.text != "WO!") saveBtn?.text = "WO!"
                if (view.alpha != 0.4f) view.alpha = 0.4f
            }
        }

        // 待機と保存処理は Default スレッドで行う
        serviceScope.launch(Dispatchers.Default) {
            Log.d("ZZZGlip", "WIPEOUT detected! Waiting 5s to capture after-event...")
            delay(5000)
            saveBufferInternal(10000L, isAutoSave = true)
            isAutoSavePending.set(false)
        }
    }

    private fun isFuzzyMatch(detected: String, target: String, maxDist: Int = 2): Boolean {
        if (detected.length < 3 || detected.length > 15) return false
        if (detected.contains(target)) return true

        val normalized = detected.replace("0", "O").replace("1", "I").replace("8", "B").replace("5", "S").replace("A", "I")
        return levenshteinDistance(normalized, target) <= maxDist
    }

    private fun isSequenceMatchCount(text: String, target: String): Int {
        // 類似文字の正規化 (0->O, 1->I, A->Iなど)
        val normalized = text.replace("0", "O").replace("1", "I").replace("8", "B").replace("5", "S").replace("A", "I")
        var lastIdx = -1
        var count = 0
        for (char in target) {
            val foundIdx = normalized.indexOf(char, lastIdx + 1)
            if (foundIdx != -1) {
                count++
                lastIdx = foundIdx
            }
        }
        return count
    }

    private fun checkShapeContrast(bitmap: Bitmap): Boolean {
        val w = bitmap.width; val h = bitmap.height
        // WIPEOUTが表示される中央付近 (40%〜60%) に限定
        val startY = (h * 0.40).toInt(); val endY = (h * 0.60).toInt()
        val step = 2 // 200pxなのでステップを細かくする
        var contrastPoints = 0
        var totalPoints = 0

        // 画面中央付近の輝度変化をチェック (色に依存しない)
        for (y in startY until endY step step) {
            var lastLum = -1
            for (x in 0 until w step step) {
                val pixel = bitmap.getPixel(x, y)
                val lum = (android.graphics.Color.red(pixel) * 0.299 + 
                           android.graphics.Color.green(pixel) * 0.587 + 
                           android.graphics.Color.blue(pixel) * 0.114).toInt()
                
                if (lastLum != -1) {
                    if (abs(lum - lastLum) > 50) { // コントラストの強さを少し厳しめにする
                        contrastPoints++
                    }
                }
                lastLum = lum
                totalPoints++
            }
        }
        
        val contrastDensity = contrastPoints.toFloat() / totalPoints
        if (com.glipverup.app.BuildConfig.DEBUG) {
            if (contrastDensity <= 0.05) {
                Log.d("ZZZGlip_Diag", "Shape filter rejected: Density=$contrastDensity")
            } else {
                Log.d("ZZZGlip_Diag", "Shape filter passed: Density=$contrastDensity")
            }
        }
        // 何も表示されていない画面では密度が非常に低くなる
        return contrastDensity > 0.05
    }


    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) { for (j in 1..s2.length) { val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1; dp[i][j] = minOf(minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost) } }
        return dp[s1.length][s2.length]
    }

    private fun stopEncoderOnly() {
        virtualDisplay?.surface = null
        try { videoEncoder?.stop() } catch (e: Exception) {} finally { videoEncoder?.release(); videoEncoder = null }
        try { audioEncoder?.stop() } catch (e: Exception) {} finally { audioEncoder?.release(); audioEncoder = null }
        videoEncoderSurface?.release(); videoEncoderSurface = null
    }

    private fun stopRecording() {
        val wasRecording = isRecording.getAndSet(false)
        if (!wasRecording && mediaProjection == null) return
        audioJob?.cancel(); audioJob = null
        detectionJob?.cancel(); detectionJob = null
        stopEncoderOnly()
        virtualDisplay?.release(); virtualDisplay = null
        audioRecord?.let { try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } catch (e: Exception) {} ; try { it.release() } catch (e: Exception) {} }
        audioRecord = null
        synchronized(muxerLock) { try { if (muxerStarted) { if (samplesWrittenToCurrentMuxer) muxer?.stop(); muxer?.release() } } catch (e: Exception) {} ; muxer = null; muxerStarted = false; samplesWrittenToCurrentMuxer = false }
        mediaProjection?.stop(); mediaProjection = null
        segments.forEach { if (it.exists()) it.delete() }; segments.clear()
        handler.post { floatingView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }; floatingView = null }
    }

    private fun createNotificationChannel() {
        val chan = NotificationChannel(CHANNEL_ID, "ZZZGlip Recorder", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(chan)
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(currentBufferTime))
    }

    private fun createNotification(time: String): Notification {
        val stopPI = PendingIntent.getService(this, 0, Intent(this, ScreenRecorderService::class.java).apply { action = "STOP_SERVICE" }, PendingIntent.FLAG_IMMUTABLE)
        val listPI = PendingIntent.getActivity(this, 1, Intent(this, TimeSelectionActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("ZZZGlip Recording").setContentText("Buffer: $time").setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).addAction(0, "Stop", stopPI).addAction(0, "Time List", listPI).build()
    }

override fun onDestroy() { super.onDestroy(); stopRecording(); serviceJob.cancel() }
}
