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

    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager
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
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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
                stopSelf()
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
        if (isRecording.get()) return
        
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

        handler.post {
            try {
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (isRecording.get()) {
                            Log.d("ZZZGlip", "MediaProjection stopped by system")
                            stopRecording()
                            sendBroadcast(Intent("com.glipverup.app.RECORDING_STOPPED").apply { setPackage(packageName) })
                            stopSelf()
                        }
                    }
                }, handler)

                isRecording.set(true)
                serviceScope.launch(Dispatchers.Default) {
                    try {
                        mediaProjection?.let { projection ->
                            while (isRecording.get()) {
                                prepareAndStartRecording(projection)
                                if (isRecording.get()) {
                                    // 画面回転による再起動
                                    stopEncoderAndDisplay()
                                    delay(100)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ZZZGlip", "Recording failed", e)
                        withContext(Dispatchers.Main) { 
                            stopRecording()
                            stopSelf() // 通知も消すために追加
                        }
                    }
                }
                showFloatingButton()
            } catch (e: Exception) {
                Log.e("ZZZGlip", "Failed to get projection", e)
                stopSelf()
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
            // KEY_PRIORITYを削除し、OSの判断（ゲーム優先）に任せる
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

        // Internal Audio Capture (初回のみ)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && audioRecord == null) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                try {
                    Log.d("ZZZGlip", "Initializing AudioRecord with MediaProjection")
                    
                    // Android 14以降の制約に対応するための設定
                    val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                        
                    val minBufSize = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                    
                    audioRecord = AudioRecord.Builder()
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(48000)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                        .setBufferSizeInBytes(maxOf(minBufSize, 4096 * 4))
                        .setAudioPlaybackCaptureConfig(config)
                        .build()
                    
                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        delay(200) // サービスの状態がOSに伝搬するのを待つ
                        audioRecord?.startRecording()
                        Log.d("ZZZGlip", "AudioRecord started. state: ${audioRecord?.recordingState}, session: ${audioRecord?.audioSessionId}")
                    }
                } catch (e: Exception) {
                    Log.e("ZZZGlip", "AudioRecord error", e)
                }
            } else {
                Log.w("ZZZGlip", "RECORD_AUDIO permission not granted")
            }
        }

        virtualDisplay?.let {
            it.resize(vW, vH, metrics.densityDpi)
            it.setSurface(videoEncoderSurface)
        } ?: run {
            virtualDisplay = projection.createVirtualDisplay("ZZZGlipCapture", vW, vH, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, videoEncoderSurface, null, null)
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
            while (isRecording.get() && !pendingRotationRestart) {
                audioRecord?.let { record ->
                    audioPCMBuffer.clear()
                    val read = try { record.read(audioPCMBuffer, audioPCMBuffer.capacity()) } catch (e: Exception) { 
                        Log.e("ZZZGlip", "AudioRecord.read error", e)
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

                        // 音声データが取得できているか定期的にログ出力 (100回に1回)
                        if (audioSampleCount % 100 == 0L) {
                            Log.d("ZZZGlip", "Audio read: $read bytes, Max amp: $maxVal, Total: $audioSampleCount")
                        }
                        val inputIndex = try { audioEncoder?.dequeueInputBuffer(1000) ?: -1 } catch (e: Exception) { -1 }
                        if (inputIndex >= 0) {
                            val inputBuffer = audioEncoder?.getInputBuffer(inputIndex)
                            inputBuffer?.clear()
                            audioPCMBuffer.limit(read)
                            inputBuffer?.put(audioPCMBuffer)
                            
                            val nowUs = System.nanoTime() / 1000
                            if (audioTimelineOffsetUs == -1L) {
                                audioTimelineOffsetUs = nowUs
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
                                    val pts = absoluteAudioPts - segmentFirstPtsUs
                                    if (pts >= 0) {
                                        aBufferInfo.presentationTimeUs = pts
                                        muxer?.writeSampleData(audioTrackIndex, buffer, aBufferInfo)
                                        samplesWrittenToCurrentMuxer = true
                                        // 書き込みログ（50フレームごと）
                                        if (audioSampleCount % 200 == 0L) {
                                            Log.d("ZZZGlip", "Audio sample written to muxer. PTS: $pts, Abs: $absoluteAudioPts")
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
                        virtualDisplay?.setSurface(imageReader?.surface)
                        delay(33) 
                        virtualDisplay?.setSurface(videoEncoderSurface)
                        val params = Bundle()
                        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        videoEncoder?.setParameters(params)
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
        // Android 14以降の制限（1つのMediaProjectionで1つのVirtualDisplayのみ）に対応するため、
        // ここではVirtualDisplayを作成せず、ImageReaderのみ準備する。
        // 実際のキャプチャはrecordingLoop内でサーフェスを一時的に切り替えて行う。
        imageReader = ImageReader.newInstance(width / 4, height / 4, PixelFormat.RGBA_8888, 2)
        startDetectionLoop()
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

    private fun stopEncoderAndDisplay() {
        // VirtualDisplayはAndroid 14の制限により再作成できないため、Surfaceの解除のみ行う
        virtualDisplay?.setSurface(null)
        videoEncoder?.let { try { it.stop() } catch (e: Exception) {} ; it.release() }; videoEncoder = null
        audioEncoder?.let { try { it.stop() } catch (e: Exception) {} ; it.release() }; audioEncoder = null
    }

    private fun stopRecording() {
        isRecording.set(false)
        
        // 音声スレッドの終了を待つ
        runBlocking {
            audioJob?.cancel()
            try { audioJob?.join() } catch (e: Exception) {}
            audioJob = null
        }

        stopEncoderAndDisplay()
        virtualDisplay?.release(); virtualDisplay = null // サービス終了時のみ完全に解放
        detectionVirtualDisplay?.release(); detectionVirtualDisplay = null
        imageReader?.close(); imageReader = null
        
        audioRecord?.let { 
            try { it.stop() } catch (e: Exception) {} 
            it.release() 
        }
        audioRecord = null
        
        synchronized(muxerLock) {
            try {
                if (muxerStarted && samplesWrittenToCurrentMuxer) muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {}
            muxer = null
            muxerStarted = false
            samplesWrittenToCurrentMuxer = false
        }
        mediaProjection?.stop(); mediaProjection = null
        segments.forEach { it.delete() }; segments.clear()
        floatingView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }; floatingView = null
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
