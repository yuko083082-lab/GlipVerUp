package com.glipverup.app.service

import android.app.*
import android.content.*
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
import com.glipverup.app.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
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
        val context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            newBase.createAttributionContext("glip_recorder")
        } else {
            newBase
        }
        super.attachBaseContext(context)
    }

    private fun getAttributedContext(): Context {
        return this
    }

    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager
    private var audioManager: AudioManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var detectionVirtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var settingsManager: SettingsManager
    private var floatingView: View? = null
    private var videoEncoderSurface: Surface? = null

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
    private var currentBufferTime = "6 min"
    private var isWipeoutDetectionEnabled = false
    
    private var mInterstitialAd: InterstitialAd? = null

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    
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
        val attrContext = getAttributedContext()
        windowManager = attrContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        projectionManager = attrContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        audioManager = attrContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
        
        loadAd()
    }

    private fun loadAd() {
        if (BuildConfig.DEBUG) return
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(this, "ca-app-pub-3940256099942544/1033173712", adRequest, 
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    mInterstitialAd = interstitialAd
                }
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    mInterstitialAd = null
                }
            })
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
                    serviceScope.launch { settingsManager.updateBufferTime(newTime) }
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
        // 前回プロセスの残骸や、直前の停止処理が完了するのを待機 (Android 14対策)
        Thread.sleep(1500)
        
        cleanLegacyFiles()
        sessionStartTimeMs = System.currentTimeMillis()

        val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        } ?: return

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

        try {
            val attrContext = getAttributedContext()
            windowManager = attrContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            projectionManager = attrContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            audioManager = attrContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // 前回セッションのMediaProjectionが残っている可能性を排除
            mediaProjection?.stop()
            mediaProjection = null

            val projection = projectionManager.getMediaProjection(resultCode, data)
            if (projection == null) {
                Log.e("ZZZGlip", "Failed to get MediaProjection")
                stopSelf()
                return
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

            // VirtualDisplayとEncoderSurfaceを完全にクリーンアップした状態で開始
            virtualDisplay?.release()
            virtualDisplay = null
            videoEncoderSurface?.release()
            videoEncoderSurface = null

            // AudioRecordの初期化と録画ループ開始を管理
            serviceScope.launch(Dispatchers.Main) {
                try {
                    // 1. AudioRecordのセットアップを最優先
                    initializeAudioRecord(projection)
                    
                    // 2. 録画中フラグを立てる
                    isRecording.set(true)

                    // 3. Wipeout検出のループを開始 (1回のみ)
                    if (isWipeoutDetectionEnabled) {
                        startDetectionLoop()
                    }
                    
                    // 4. 録画メインループを開始
                    startRecordingMainLoop()
                } catch (e: Exception) {
                    Log.e("ZZZGlip", "Recording setup failed", e)
                    stopRecording()
                    stopSelf()
                }
            }

            showFloatingButton()
        } catch (e: Exception) {
            Log.e("ZZZGlip", "Failed to get projection", e)
            stopSelf()
        }
    }

    private suspend fun startRecordingMainLoop() {
        withContext(Dispatchers.Default) {
            try {
                while (isRecording.get()) {
                    val currentProjection = mediaProjection ?: break
                    prepareAndStartRecording(currentProjection)
                    
                    if (isRecording.get()) {
                        // 画面回転時などはリソースを一度完全に解放して再生成する
                        // Android 14対策: VirtualDisplayは破棄せず、Encoderのみを入れ替える
                        stopEncoderOnly()
                        delay(800)
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
            } finally {
                Log.d("ZZZGlip", "Recording outer loop finished")
            }
        }
    }

    private suspend fun initializeAudioRecord(projection: MediaProjection) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && audioRecord == null) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    val attrContext = getAttributedContext()
                    Log.d("ZZZGlip", "Initializing AudioRecord. Context tag: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) attrContext.attributionTag else "N/A"}")
                    
                    // Audio Focus Dance
                    val am = audioManager ?: (attrContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                            .build()
                        Log.d("ZZZGlip", "Requesting audio focus for reset (ZZZ adjustment)")
                        am.requestAudioFocus(focusRequest)
                        delay(1000) // 500msから1000msに延長
                        am.abandonAudioFocusRequest(focusRequest)
                        Log.d("ZZZGlip", "Released audio focus")
                        delay(1000) // 500msから1000msに延長
                    }

                    val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                        .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                        .build()
                        
                    val sampleRate = 48000
                    val minBufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                    
                    val record = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            AudioRecord.Builder()
                                .setContext(this@ScreenRecorderService) // createAttributionContext済みのService自身を使用
                                .setAudioFormat(AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                                    .build())
                                .setBufferSizeInBytes(maxOf(minBufSize, 4096 * 8))
                                .setAudioPlaybackCaptureConfig(config)
                                .build()
                        } else {
                            @Suppress("DEPRECATION")
                            AudioRecord(
                                MediaRecorder.AudioSource.VOICE_RECOGNITION, 
                                sampleRate,
                                AudioFormat.CHANNEL_IN_STEREO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                maxOf(minBufSize, 4096 * 8)
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("ZZZGlip", "AudioRecord.Builder failed", e)
                        null
                    }
                    
                    if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord = record
                        delay(2000)
                        record.startRecording()
                        Log.d("ZZZGlip", "AudioRecord started. state: ${record.recordingState}, session: ${record.audioSessionId}")
                    } else {
                        Log.e("ZZZGlip", "AudioRecord failed to initialize or state invalid")
                        record?.release()
                    }
                } catch (e: Exception) {
                    Log.e("ZZZGlip", "AudioRecord initialization error", e)
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

        // Video Encoder
        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vW, vH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1024 * 1024)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder?.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoEncoderSurface = videoEncoder?.createInputSurface()
        videoEncoder?.start()

        // Audio Encoder
        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder?.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder?.start()

        // VirtualDisplayの管理（Android 14以降、1つのMediaProjectionに対してcreateVirtualDisplayは1回のみ）
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
            setupWipeoutDetection(vW, vH)
        }

        rotateMuxer()
        lastMuxerRotationTimeMs = System.currentTimeMillis()
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
                if (videoTrackIndex < 0) {
                    persistedVideoFormat?.let { videoTrackIndex = muxer?.addTrack(it) ?: -1 }
                }
                if (audioTrackIndex < 0) {
                    persistedAudioFormat?.let { audioTrackIndex = muxer?.addTrack(it) ?: -1 }
                }
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

    private suspend fun recordingLoop() {
        val vBufferInfo = MediaCodec.BufferInfo()
        val aBufferInfo = MediaCodec.BufferInfo()
        val audioPCMBuffer = ByteBuffer.allocateDirect(4096)
        
        val lastOrientation = resources.configuration.orientation
        segmentFirstPtsUs = -1L
        audioSampleCount = 0L
        var lastDetectionTime = 0L
        videoTimelineOffsetUs = -1L
        audioTimelineOffsetUs = -1L
        lastMuxerRotationTimeMs = System.currentTimeMillis()
        pendingRotationRestart = false // リセット

        // 音声処理を別スレッドで開始
        audioJob = serviceScope.launch(Dispatchers.IO) {
            Log.d("ZZZGlip", "Audio thread started. Recording: ${isRecording.get()}")
            var consecutiveSilentCount = 0
            var soundEverDetected = false
            
            while (isRecording.get() && !pendingRotationRestart) {
                val currentProjection = mediaProjection ?: break
                audioRecord?.let { record ->
                    audioPCMBuffer.clear()
                    val read = try { record.read(audioPCMBuffer, audioPCMBuffer.capacity()) } catch (e: Exception) { 
                        Log.e("ZZZGlip", "AudioRecord.read EXCEPTION", e)
                        -1 
                    }
                    if (read > 0) {
                        // 音声の強さを簡易的にチェック (PCM 16bit)
                        var maxVal = 0
                        audioPCMBuffer.position(0)
                        val shorts = audioPCMBuffer.asShortBuffer()
                        for (i in 0 until (read / 2)) {
                            if (i < shorts.limit()) {
                                val v = Math.abs(shorts.get(i).toInt())
                                if (v > maxVal) maxVal = v
                            }
                        }
                        audioPCMBuffer.position(0)

                        if (maxVal > 0) {
                            consecutiveSilentCount = 0
                            soundEverDetected = true
                        } else {
                            consecutiveSilentCount++
                        }

                        // 音声データが取得できているか定期的にログ出力 (100回に1回)
                        if (audioSampleCount % 100 == 0L) {
                            val state = when(record.recordingState) {
                                AudioRecord.RECORDSTATE_RECORDING -> "RECORDING"
                                AudioRecord.RECORDSTATE_STOPPED -> "STOPPED"
                                else -> "UNKNOWN"
                            }
                            Log.d("ZZZGlip_Audio", "Read: $read bytes, Max amp: $maxVal, State: $state, Session: ${record.audioSessionId}, SilentCount: $consecutiveSilentCount")
                            
                            // ゼンゼロ対策: 3秒以上無音が続いた場合、かつ過去に一度でも音が出ていたなら、セッションが死んだとみなして再起動を試みる
                            // (※最初から無音の場合は、ゲーム側がまだ音を出していないだけの可能性があるので少し長めに待つ)
                            val restartThreshold = if (soundEverDetected) 300 else 1000 // 3秒 or 10秒
                            
                            if (consecutiveSilentCount > restartThreshold) {
                                Log.e("ZZZGlip_Audio", "Critical: SILENCE detected. Restarting AudioRecord for recovery...")
                                consecutiveSilentCount = 0
                                serviceScope.launch(Dispatchers.Main) {
                                    try {
                                        record.stop()
                                        record.release()
                                        audioRecord = null
                                        Log.d("ZZZGlip", "Old AudioRecord released for recovery")
                                        delay(500)
                                        initializeAudioRecord(currentProjection)
                                    } catch (e: Exception) {
                                        Log.e("ZZZGlip", "Failed to restart AudioRecord", e)
                                    }
                                }
                            }
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
                            
                            val nowUs = System.nanoTime() / 1000
                            if (audioTimelineOffsetUs == -1L) {
                                audioTimelineOffsetUs = nowUs
                                Log.d("ZZZGlip", "Audio timeline offset set: $audioTimelineOffsetUs")
                            }
                            
                            // エンコーダには0ベースのPTSを渡す（多くのAACエンコーダはこれを基準にする）
                            val ptsUs = nowUs - audioTimelineOffsetUs
                            audioEncoder?.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                            audioSampleCount += (read / 4)
                        }
                    } else if (read < 0) {
                        Log.e("ZZZGlip", "AudioRecord.read returned error: $read")
                        delay(100)
                    }
                } ?: run {
                    delay(500)
                }
            }
            Log.d("ZZZGlip", "Audio thread finishing")
        }

        while (isRecording.get()) {
            val currentOrientation = resources.configuration.orientation
            if (currentOrientation != lastOrientation) {
                pendingRotationRestart = true
                rotateMuxerNextLoop = true
                break
            }

            // セグメント時間のチェック（映像フレームが来なくても強制的に回転させるため）
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
                                    // Muxerにトラックを追加する際、persistedVideoFormat (encoder.outputFormat) 
                                    // を使用している場合、すでに CSD (Codec Specific Data) が含まれている。
                                    // その状態で BUFFER_FLAG_CODEC_CONFIG を書き込むと "Already have codec specific data" エラーになる。
                                    if ((vBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        // 全てのセグメントで、個別の設定データサンプルはスキップする
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
                        // エンコーダから出たPTSを絶対時間（nanoTimeベース）に戻す
                        val absoluteAudioPts = aBufferInfo.presentationTimeUs + audioTimelineOffsetUs
                        
                        synchronized(muxerLock) {
                            if (audioTrackIndex >= 0 && muxerStarted) {
                                if (segmentFirstPtsUs == -1L) {
                                    segmentFirstPtsUs = absoluteAudioPts
                                }

                                try {
                                    // Audioも同様に、既にフォーマット経由で設定済みの場合はスキップ
                                    if ((aBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        return@let
                                    }

                                    val pts = absoluteAudioPts - segmentFirstPtsUs
                                    if (pts >= 0) {
                                        aBufferInfo.presentationTimeUs = pts
                                        muxer?.writeSampleData(audioTrackIndex, buffer, aBufferInfo)
                                        samplesWrittenToCurrentMuxer = true
                                        // 書き込みログ（200フレームごと）
                                        if (audioSampleCount % 200 == 0L) {
                                            Log.d("ZZZGlip_Muxer", "Audio frame written. PTS: $pts, Abs: $absoluteAudioPts, Track: $audioTrackIndex")
                                        }
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

            if (isWipeoutDetectionEnabled && imageReader != null && videoEncoderSurface != null) {
                val now = System.currentTimeMillis()
                if (now - lastDetectionTime > 1500) { 
                    lastDetectionTime = now
                    try {
                        // Android 14+での無音化を避けるため、一時的にサーフェス切り替えを無効化
                        /*
                        virtualDisplay?.setSurface(imageReader?.surface)
                        delay(33) 
                        virtualDisplay?.setSurface(videoEncoderSurface)
                        val params = Bundle()
                        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        videoEncoder?.setParameters(params)
                        */
                    } catch (e: Exception) {
                        Log.e("ZZZGlip", "Surface swap failed", e)
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
        if (isSaving.getAndSet(true)) return // 多重保存を防止
        
        Log.d("ZZZGlip_Save", "Save triggered. Target: $targetMs ms, AutoSave: $isAutoSave, Total Audio Samples: $audioSampleCount")

        serviceScope.launch {
            // UI更新: 保存中
            withContext(Dispatchers.Main) {
                floatingView?.findViewById<View>(R.id.save_progress)?.visibility = View.VISIBLE
                floatingView?.alpha = 0.4f
            }

            rotateMuxerNextLoop = true
            // キーフレームでのファイル切り替えを待つ（最大2秒）
            var waitCount = 0
            while (rotateMuxerNextLoop && waitCount < 20) {
                delay(100)
                waitCount++
            }
            
            // 2秒待ってもキーフレームが来ない場合は、静止画状態とみなして強制的に切り替える
            if (rotateMuxerNextLoop) {
                rotateMuxer()
                lastMuxerRotationTimeMs = System.currentTimeMillis()
                rotateMuxerNextLoop = false
            }
            
            // 保存すべき向きのセグメントを特定（直近のセグメントから多数決で決定）
            val landscapeCount = segments.count { it.name.endsWith("_L.mp4") }
            val portraitCount = segments.count { it.name.endsWith("_P.mp4") }
            val targetSuffix = if (landscapeCount >= portraitCount) "_L.mp4" else "_P.mp4"
            
            val available = segments.filter { 
                it.exists() && it.name.endsWith(targetSuffix) && it.length() > 1024 && it.lastModified() >= sessionStartTimeMs
            }.toList()

            if (available.isEmpty()) {
                withContext(Dispatchers.Main) { 
                    Toast.makeText(this@ScreenRecorderService, "Wait a few seconds...", Toast.LENGTH_SHORT).show() 
                    isSaving.set(false)
                    floatingView?.findViewById<View>(R.id.save_progress)?.visibility = View.GONE
                    floatingView?.alpha = 0.6f
                }
                return@launch
            }

            withContext(Dispatchers.IO) {
                try {
                    val fileName = "clip_${System.currentTimeMillis()}.mp4"
                    val pfd: ParcelFileDescriptor?
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ZZZGlip")
                        }
                        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                        pfd = uri?.let { contentResolver.openFileDescriptor(it, "w") }
                    } else {
                        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        val appDir = File(moviesDir, "ZZZGlip").apply { if (!exists()) mkdirs() }
                        val outFile = File(appDir, fileName)
                        pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
                    }

                    if (pfd != null) {
                        fastMergeFiles(available, targetMs, pfd)
                        pfd.close()
                        withContext(Dispatchers.Main) { 
                            val msg = if (isAutoSave) "WIPEOUT clip saved!" else "Saved to Movies!"
                            Toast.makeText(this@ScreenRecorderService, msg, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.e("ZZZGlip", "Failed to open output file descriptor")
                    }
                } catch (e: Exception) { 
                    Log.e("ZZZGlip", "Merge failed", e)
                    withContext(Dispatchers.Main) { 
                        Toast.makeText(this@ScreenRecorderService, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show() 
                    }
                } finally {
                    isSaving.set(false)
                    withContext(Dispatchers.Main) {
                        floatingView?.findViewById<View>(R.id.save_progress)?.visibility = View.GONE
                        floatingView?.alpha = 0.6f
                    }
                }
            }
        }
    }

    private fun fastMergeFiles(files: List<File>, targetMs: Long, pfd: ParcelFileDescriptor) {
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        Log.d("ZZZGlip", "Merging ${files.size} files for $targetMs ms")
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
        
        if (toMerge.isEmpty()) {
            Log.e("ZZZGlip", "No files to merge (accumulated: $accumulated)")
            muxer.release()
            return
        }

        var videoFmt: MediaFormat? = null
        var audioFmt: MediaFormat? = null
        
        // フォーマットを特定
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

        if (videoFmt == null) {
            Log.e("ZZZGlip", "Video format not found in segments")
            muxer.release()
            return
        }

        val vTIdx = muxer.addTrack(videoFmt)
        val aTIdx = if (audioFmt != null) muxer.addTrack(audioFmt) else -1
        Log.d("ZZZGlip", "Muxer: Tracks added (v: $vTIdx, a: $aTIdx)")
        
        try {
            muxer.start()
        } catch (e: Exception) {
            Log.e("ZZZGlip", "Muxer start failed in merge", e)
            muxer.release()
            return
        }

        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        
        // 全体のオフセットを管理
        var globalFileOffsetUs = 0L
        val startClipUs = if (accumulated > targetMs) (accumulated - targetMs) * 1000 else 0L
        var firstFramePtsInSession = -1L
        var samplesWritten = false

        for (i in toMerge.indices) {
            val f = toMerge[i]
            val e = MediaExtractor()
            try {
                e.setDataSource(f.absolutePath)
                
                // トラックを特定
                val vi = (0 until e.trackCount).firstOrNull { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                val ai = (0 until e.trackCount).firstOrNull { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }

                // このファイル内での最小PTSを探す (映像と音声の両方を考慮)
                var fileMinPtsUs = Long.MAX_VALUE
                if (vi != null) {
                    e.selectTrack(vi)
                    if (i == 0) e.seekTo(startClipUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    if (e.sampleTime != -1L) fileMinPtsUs = minOf(fileMinPtsUs, e.sampleTime)
                    e.unselectTrack(vi)
                }
                if (ai != null) {
                    e.selectTrack(ai)
                    if (i == 0) e.seekTo(startClipUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    if (e.sampleTime != -1L) fileMinPtsUs = minOf(fileMinPtsUs, e.sampleTime)
                    e.unselectTrack(ai)
                }
                if (fileMinPtsUs == Long.MAX_VALUE) fileMinPtsUs = 0L

                // 両方のトラックを選択してインターリーブ（交互）に読み込む
                if (vi != null) e.selectTrack(vi)
                if (ai != null && aTIdx != -1) e.selectTrack(ai)

                // シーク（再度行う必要がある）
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
                    
                    @Suppress("WrongConstant")
                    info.set(0, size, pts - firstFramePtsInSession, e.sampleFlags)
                    muxer.writeSampleData(targetTIdx, buffer, info)
                    
                    maxPtsInFile = maxOf(maxPtsInFile, pts - firstFramePtsInSession)
                    samplesWritten = true
                    e.advance()
                }
                
                // 次のファイルのためにオフセットを更新
                globalFileOffsetUs = firstFramePtsInSession + maxPtsInFile + 1000L

            } catch (e: Exception) {
                Log.e("ZZZGlip", "Error processing file $f during merge", e)
            } finally {
                e.release()
            }
        }
        
        try {
            if (samplesWritten) muxer.stop()
        } catch (e: Exception) {
            Log.e("ZZZGlip", "Muxer stop failed in merge", e)
        } finally {
            muxer.release()
        }
    }

    private fun getFileDuration(file: File): Long {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(file.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L }
        catch (_: Exception) { 0L } finally { r.release() }
    }

    private fun showFloatingButton() {
        serviceScope.launch {
            val savedX = settingsManager.floatingXFlow.first()
            val savedY = settingsManager.floatingYFlow.first()
            
            withContext(Dispatchers.Main) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)

                floatingView = LayoutInflater.from(this@ScreenRecorderService).inflate(R.layout.layout_floating_button, null).apply { alpha = 0.6f }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, 
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, 
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    // 保存された位置があれば適用、なければ画面右下に配置
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
                            MotionEvent.ACTION_UP -> {
                                if (mv) {
                                    // 位置を保存
                                    serviceScope.launch { settingsManager.saveFloatingPosition(params.x, params.y) }
                                } else if (!isSaving.get()) {
                                    saveLastMinutes()
                                }
                            }
                        }
                        return true
                    }
                })
                windowManager.addView(floatingView, params)
            }
        }
    }

    private fun setupWipeoutDetection(width: Int, height: Int) {
        // 解像度変更時に ImageReader を再作成
        imageReader?.close()
        imageReader = ImageReader.newInstance(width / 4, height / 4, PixelFormat.RGBA_8888, 2)
        // ループは既に 1 つ走っているはずだが、isRecording && isWipeoutDetectionEnabled で制御
    }

    private var lastWipeoutDetectionTime = 0L

    private fun startDetectionLoop() {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        serviceScope.launch(Dispatchers.Default) {
            while (isRecording.get() && isWipeoutDetectionEnabled) {
                delay(1000)
                val image = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null } ?: continue
                try {
                    val buffer = image.planes[0].buffer
                    val pixelStride = image.planes[0].pixelStride
                    val rowStride = image.planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * image.width
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride, image.height,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    recognizer.process(inputImage).addOnSuccessListener { visionText ->
                        for (block in visionText.textBlocks) {
                            val text = block.text.uppercase().replace(" ", "")
                            if (isFuzzyMatch(text, "WIPEOUT")) {
                                val box = block.boundingBox
                                if (box != null) {
                                    val centerX = box.centerX()
                                    val centerY = box.centerY()
                                    val imgW = image.width
                                    val imgH = image.height
                                    
                                    // 中央付近かチェック (30% - 70% range)
                                    if (centerX > imgW * 0.25 && centerX < imgW * 0.75 &&
                                        centerY > imgH * 0.25 && centerY < imgH * 0.75) {
                                        
                                        val now = System.currentTimeMillis()
                                        if (now - lastWipeoutDetectionTime > 15000) {
                                            lastWipeoutDetectionTime = now
                                            handleWipeoutDetected()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) { Log.e("ZZZGlip", "Detection error") }
                finally { image.close() }
            }
            recognizer.close()
        }
    }

    private fun handleWipeoutDetected() {
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(this@ScreenRecorderService, "WIPEOUT Detected! Saving...", Toast.LENGTH_SHORT).show()
            delay(5000)
            saveBufferInternal(10000L, isAutoSave = true)
        }
    }

    private fun isFuzzyMatch(detected: String, target: String): Boolean {
        if (detected.contains(target)) return true
        var matches = 0
        var lastIdx = -1
        for (char in target) {
            val idx = detected.indexOf(char, lastIdx + 1)
            if (idx != -1) {
                matches++
                lastIdx = idx
            }
        }
        return (matches.toFloat() / target.length) >= 0.75f
    }

    private fun stopEncoderOnly() {
        // VirtualDisplayを解放する前にSurfaceを切り離して、BufferQueueの意図しないabandonedを防ぐ
        virtualDisplay?.surface = null

        try {
            videoEncoder?.stop()
        } catch (e: Exception) {
            // ignore
        } finally {
            videoEncoder?.release()
            videoEncoder = null
        }
        
        try {
            audioEncoder?.stop()
        } catch (e: Exception) {
            // ignore
        } finally {
            audioEncoder?.release()
            audioEncoder = null
        }
        
        videoEncoderSurface?.release()
        videoEncoderSurface = null
    }

    private fun stopRecording() {
        val wasRecording = isRecording.getAndSet(false)
        if (!wasRecording && mediaProjection == null) return
        
        Log.d("ZZZGlip", "Stopping recording resources...")

        // 1. まず音声を止める
        audioJob?.cancel()
        audioJob = null

        // 2. エンコーダを止める (VirtualDisplayを消す前に止めることで BufferQueue abandoned を防ぐ)
        stopEncoderOnly()
        
        // 3. 画面の接続を切る
        virtualDisplay?.release()
        virtualDisplay = null
        
        detectionVirtualDisplay?.release()
        detectionVirtualDisplay = null
        
        imageReader?.close()
        imageReader = null
        
        // 4. 音声入力を解放
        audioRecord?.let { 
            try { 
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                Log.d("ZZZGlip", "AudioRecord stopped")
            } catch (e: Exception) {
                Log.e("ZZZGlip", "AudioRecord stop error", e)
            } 
            try {
                it.release()
                Log.d("ZZZGlip", "AudioRecord released")
            } catch (e: Exception) {
                Log.e("ZZZGlip", "AudioRecord release error", e)
            }
        }
        audioRecord = null
        
        // 5. 保存処理
        synchronized(muxerLock) {
            try {
                if (muxerStarted) {
                    if (samplesWrittenToCurrentMuxer) {
                        muxer?.stop()
                    }
                    muxer?.release()
                }
            } catch (e: Exception) {
                Log.e("ZZZGlip", "Muxer release error", e)
            }
            muxer = null
            muxerStarted = false
            samplesWrittenToCurrentMuxer = false
        }
        
        // 6. 最後に MediaProjection を止める
        mediaProjection?.stop()
        mediaProjection = null
        
        segments.forEach { if (it.exists()) it.delete() }
        segments.clear()
        
        handler.post {
            floatingView?.let { 
                try { windowManager.removeView(it) } catch (e: Exception) {} 
            }
            floatingView = null
        }
        
        Log.d("ZZZGlip", "Recording resources cleaned up")
    }

    private fun createNotificationChannel() {
        val chan = NotificationChannel(CHANNEL_ID, "ZZZGlip Recorder", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(currentBufferTime))
    }

    private fun createNotification(time: String): Notification {
        val stopPI = PendingIntent.getService(this, 0, Intent(this, ScreenRecorderService::class.java).apply { action = "STOP_SERVICE" }, PendingIntent.FLAG_IMMUTABLE)
        val listPI = PendingIntent.getActivity(this, 1, Intent(this, TimeSelectionActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("ZZZGlip Recording").setContentText("Buffer: $time").setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true)
            .addAction(0, "Stop", stopPI).addAction(0, "Time List", listPI).build()
    }

    override fun onDestroy() { super.onDestroy(); stopRecording(); serviceJob.cancel() }
}
