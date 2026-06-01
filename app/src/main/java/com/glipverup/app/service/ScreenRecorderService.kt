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

    private val segmentDurationMs = 60000L
    private val segments = ConcurrentLinkedDeque<File>()
    private var sessionStartTimeMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val isRecording = AtomicBoolean(false)
    private val isSaving = AtomicBoolean(false)
    private val isAutoSavePending = AtomicBoolean(false)
    private var currentBufferTime = "6 min"
    private var isWipeoutDetectionEnabled = false

    private var lastFloatingX = 0
    private var lastFloatingY = 0

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    private var detectionJob: Job? = null

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

    private data class OcrFragment(val char: Char, val rawChar: Char, val centerX: Int, val width: Int, val timestamp: Long)
    private val ocrBuffer = java.util.concurrent.CopyOnWriteArrayList<OcrFragment>()

    private var audioSampleCount = 0L

    // Atlas Debug Image Buffer (Ver 6.0)
    private val atlasFrames = mutableListOf<Bitmap>()
    private val atlasLock = Any()

    private var reusablePixels: IntArray? = null
    private var reusableOutPixels: IntArray? = null

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
                stopSelf()
            }
            "SAVE_BUFFER" -> saveLastMinutes()
            "CHANGE_TIME" -> {
                val newTime = intent.getStringExtra("selected_time")
                if (newTime != null) {
                    serviceScope.launch {
                        settingsManager.updateBufferTime(newTime)
                        currentBufferTime = newTime
                        updateNotification()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun handleStartRecording(intent: Intent) {
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

        serviceScope.launch(Dispatchers.Main) {
            delay(1000)

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
                mediaProjection?.stop()
                mediaProjection = null

                val projection = projectionManager.getMediaProjection(resultCode, data)
                if (projection == null) {
                    stopSelf()
                    return@launch
                }

                mediaProjection = projection

                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopRecording()
                        sendBroadcast(Intent("com.glipverup.app.RECORDING_STOPPED").apply { setPackage(packageName) })
                        stopSelf()
                    }
                }, handler)

                isRecording.set(true)
                initializeAudioRecord(projection)
                showFloatingButton()
                startRecordingMainLoop()
            } catch (e: Exception) {
                stopSelf()
            }
        }
    }

    private suspend fun startRecordingMainLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        withContext(Dispatchers.Default) {
            try {
                while (isRecording.get()) {
                    val currentProjection = mediaProjection ?: break
                    prepareAndStartRecording(currentProjection)

                    if (isRecording.get()) {
                        stopEncoderOnly()
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                if (isRecording.get()) {
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
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    var focusRequest: AudioFocusRequest? = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                            .build()
                        am.requestAudioFocus(focusRequest)
                        delay(200)
                    }

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
                        null
                    }

                    if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord = record
                        delay(500)
                        if (record.state == AudioRecord.STATE_INITIALIZED) {
                            try {
                                record.startRecording()
                            } catch (e: Exception) {
                            }
                        } else {
                            record.release()
                        }
                    } else {
                        record?.release()
                    }

                    focusRequest?.let { am.abandonAudioFocusRequest(it) }

                } catch (e: Exception) {
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
        } catch (e: Exception) { }
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

        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vW, vH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1024 * 1024)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder?.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoEncoderSurface = videoEncoder?.createInputSurface()
        videoEncoder?.start()

        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder?.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder?.start()

        if (virtualDisplay == null) {
            virtualDisplay = projection.createVirtualDisplay("ZZZGlipCapture", vW, vH, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, videoEncoderSurface, null, null)
        } else {
            virtualDisplay?.resize(vW, vH, metrics.densityDpi)
            virtualDisplay?.surface = videoEncoderSurface
        }

        if (isWipeoutDetectionEnabled) {
            setupWipeoutDetection()
        }

        rotateMuxer()
        lastMuxerRotationTimeMs = System.currentTimeMillis()

        if (isWipeoutDetectionEnabled && (detectionJob == null || detectionJob?.isActive == false)) {
            detectionJob = serviceScope.launch(Dispatchers.Default) {
                delay(1000)
                startDetectionLoop()
            }
        }

        recordingLoop()
    }

    private fun rotateMuxer() {
        synchronized(muxerLock) {
            try {
                if (muxerStarted && samplesWrittenToCurrentMuxer) {
                    try { muxer?.stop() } catch (e: Exception) { }
                }
                muxer?.release()
            } catch (e: Exception) { }

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

                persistedVideoFormat?.let { videoTrackIndex = muxer?.addTrack(it) ?: -1 }
                persistedAudioFormat?.let { audioTrackIndex = muxer?.addTrack(it) ?: -1 }
                checkMuxerStart()
            } catch (e: Exception) {
            }
        }
    }

    private fun checkMuxerStart() {
        if (!muxerStarted && videoTrackIndex >= 0 && (audioTrackIndex >= 0 || audioRecord == null)) {
            try {
                muxer?.start()
                muxerStarted = true
            } catch (e: Exception) {
            }
        }
    }

    private var videoTimelineOffsetUs = -1L
    private var audioTimelineOffsetUs = -1L
    private var segmentFirstPtsUs = -1L

    private suspend fun audioRecordingLoop(audioPCMBuffer: ByteBuffer) {
        audioLoop@while (isRecording.get() && !pendingRotationRestart) {
            val record = audioRecord ?: run { delay(100); break@audioLoop }

            audioPCMBuffer.clear()
            val read = try { record.read(audioPCMBuffer, audioPCMBuffer.capacity()) } catch (e: Exception) { -1 }
            if (read > 0) {
                val inputIndex = try { audioEncoder?.dequeueInputBuffer(1000) ?: -1 } catch (e: Exception) { -1 }
                if (inputIndex >= 0) {
                    val inputBuffer = audioEncoder?.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    audioPCMBuffer.limit(read)
                    inputBuffer?.put(audioPCMBuffer)

                    if (audioTimelineOffsetUs == -1L) {
                        audioTimelineOffsetUs = System.nanoTime() / 1000
                    }

                    val ptsUs = audioSampleCount * 1_000_000L / 48000L
                    audioEncoder?.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                    audioSampleCount += (read / 4)
                }
            } else if (read < 0) {
                if (isRecording.get() && !pendingRotationRestart) {
                    delay(100)
                    currentCoroutineContext().cancel()
                    break@audioLoop
                }
            }
        }
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
        pendingRotationRestart = false

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

            videoEncoder?.let { encoder ->
                val outIdx = try { encoder.dequeueOutputBuffer(vBufferInfo, 1000) } catch (_: Exception) { -1 }
                if (outIdx >= 0) {
                    // videoCaptured = true // ログ削減のため停止
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
                                    if ((vBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                        val pts = absoluteVideoPts - segmentFirstPtsUs
                                        if (pts >= 0) {
                                            vBufferInfo.presentationTimeUs = pts
                                            muxer?.writeSampleData(videoTrackIndex, buffer, vBufferInfo)
                                            samplesWrittenToCurrentMuxer = true
                                        }
                                    }
                                } catch (e: Exception) { }
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

            audioEncoder?.let { encoder ->
                val outIdx = try { encoder.dequeueOutputBuffer(aBufferInfo, 1000) } catch (_: Exception) { -1 }
                if (outIdx >= 0) {
                    // audioCaptured = true // ログ削減のため停止
                    val buffer = encoder.getOutputBuffer(outIdx)
                    if (buffer != null) {
                        val absoluteAudioPts = aBufferInfo.presentationTimeUs + audioTimelineOffsetUs
                        synchronized(muxerLock) {
                            if (audioTrackIndex >= 0 && muxerStarted) {
                                if (segmentFirstPtsUs == -1L) {
                                    segmentFirstPtsUs = absoluteAudioPts
                                }
                                try {
                                    if ((aBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                        val pts = absoluteAudioPts - segmentFirstPtsUs
                                        if (pts >= 0) {
                                            aBufferInfo.presentationTimeUs = pts
                                            muxer?.writeSampleData(audioTrackIndex, buffer, aBufferInfo)
                                            samplesWrittenToCurrentMuxer = true
                                        }
                                    }
                                } catch (e: Exception) { }
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

            delay(5)
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
        serviceScope.launch(Dispatchers.Default) {
            try {
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

                if (available.isNotEmpty()) {
                    Log.d("ZZZGlip_Save", "Starting merge for ${available.size} files, targetMs=$targetMs")
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
                            Log.i("ZZZGlip_Save", "Successfully saved video: $fileName")
                        } else {
                            Log.e("ZZZGlip_Save", "Failed to open FileDescriptor for $fileName")
                        }
                    }
                } else {
                    Log.w("ZZZGlip_Save", "No matching segment files found (suffix=$targetSuffix, count=${segments.size})")
                }
            } catch (e: Exception) {
                Log.e("ZZZGlip_Save", "CRITICAL ERROR during save: ${e.message}", e)
            } finally {
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
            } catch (e: Exception) { } finally { e.release() }
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
            } catch (e: Exception) { }
        }
    }

    private fun setupWipeoutDetection() { }

    private fun checkShapeFast(bitmap: Bitmap): Boolean {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        
        var yellowCount = 0
        val startY = (h * 0.3).toInt()
        val endY = (h * 0.7).toInt()
        
        for (y in startY until endY) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
                val yellowVal = ((r + g) / 2.0 - b).toInt()
                if (yellowVal > 40) yellowCount++
            }
        }
        
        val area = w * (endY - startY)
        // 閾値を緩和（以前の75%相当として、1.5%密度を基準に設定）
        return yellowCount > (area * 0.015)
    }

    private suspend fun startDetectionLoop() {
        val recognizerRaw = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val recognizerColor = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val backgroundExecutor = Dispatchers.Default.asExecutor()

        val localHandlerThread = HandlerThread("ZZZGlip-Detection").apply { start() }
        val currentBgHandler = Handler(localHandlerThread.looper)

        val isProcessing = AtomicBoolean(false)
        var reusableBitmap: Bitmap? = null
        var ocrReusableBitmap: Bitmap? = null
        var processedReusableBitmap: Bitmap? = null
        var channelIndex = 0
        
        var ocrActiveUntil = 0L
        var heartbeatCount = 0

        try {
            while (isRecording.get() && isWipeoutDetectionEnabled) {
                delay(100)
                if (isProcessing.get()) continue
                
                // 録画の重要局面（保存・セグメント回転）では負荷を逃がす
                if (isSaving.get() || rotateMuxerNextLoop) continue

                val vd = virtualDisplay ?: continue
                val targetW = 320
                val targetH = (targetW * (captureHeight.toFloat() / captureWidth.toFloat())).toInt()

                val bitmap = if (reusableBitmap != null && reusableBitmap!!.width == targetW && reusableBitmap!!.height == targetH) {
                    reusableBitmap!!
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
                    val now = System.currentTimeMillis()
                    val isOcrCurrentlyActive = now < ocrActiveUntil
                    val remainingOcrTime = ocrActiveUntil - now

                    // 1. 形状判定（トリガー）: OCR停止中、または終了直前(100ms以内)のみ実行
                    if (!isOcrCurrentlyActive || remainingOcrTime <= 100) {
                        if (checkShapeFast(bitmap)) {
                            ocrActiveUntil = now + 2000
                        }
                    }

                    // OCR期間外なら処理をスキップし、バッファをクリア
                    if (now >= ocrActiveUntil) {
                        if (ocrBuffer.isNotEmpty()) ocrBuffer.clear()
                        continue
                    }

                    isProcessing.set(true)

                    // 2. OCR用に解像度を200pxに落とす
                    val ocrW = 200
                    val ocrH = (ocrW * (targetH.toFloat() / targetW.toFloat())).toInt()
                    
                    val ocrBaseBitmap = if (ocrReusableBitmap != null && ocrReusableBitmap!!.width == ocrW && ocrReusableBitmap!!.height == ocrH) {
                        val canvas = android.graphics.Canvas(ocrReusableBitmap!!)
                        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, ocrW, ocrH), paint)
                        ocrReusableBitmap!!
                    } else {
                        ocrReusableBitmap?.recycle()
                        Bitmap.createScaledBitmap(bitmap, ocrW, ocrH, true).also { ocrReusableBitmap = it }
                    }

                    val mode = if (channelIndex % 2 == 0) "RAW" else "COLOR"
                    val currentRecognizer = if (mode == "RAW") recognizerRaw else recognizerColor
                    channelIndex++

                    val chBitmap = if (processedReusableBitmap != null && processedReusableBitmap!!.width == ocrW && processedReusableBitmap!!.height == ocrH) {
                        processedReusableBitmap!!
                    } else {
                        processedReusableBitmap?.recycle()
                        Bitmap.createBitmap(ocrW, ocrH, Bitmap.Config.ARGB_8888).also { processedReusableBitmap = it }
                    }

                    preprocessForChannel(ocrBaseBitmap, chBitmap, mode)

                    if (com.glipverup.app.BuildConfig.DEBUG) {
                        addToAtlasBuffer(chBitmap)
                    }

                    currentRecognizer.process(InputImage.fromBitmap(chBitmap, 0))
                        .addOnSuccessListener(backgroundExecutor) { visionText ->
                            val currentFrameFragments = mutableListOf<OcrFragment>()
                            val rawLog = StringBuilder()

                            for (block in visionText.textBlocks) {
                                // 効率化: ブロック単位で中央付近にあるか判定
                                val blockRect = block.boundingBox ?: continue
                                val blockCenterY = blockRect.centerY().toFloat() / ocrH
                                if (blockCenterY !in 0.2..0.8) continue

                                for (line in block.lines) {
                                    for (element in line.elements) {
                                        for (symbol in element.symbols) {
                                            val char = symbol.text.uppercase().firstOrNull() ?: continue
                                            val rect = symbol.boundingBox ?: continue
                                            
                                            val centerX = rect.centerX()
                                            val width = rect.width()
                                            val centerY = rect.centerY()
                                            val normCenterY = centerY.toFloat() / ocrH
                                            
                                            if (com.glipverup.app.BuildConfig.DEBUG) {
                                                rawLog.append("[$char at $centerX,$centerY w$width] ")
                                            }

                                            if (normCenterY in 0.3..0.7) {
                                                val normalizedChar = isSpatialMatch(char, centerX, width)
                                                if (normalizedChar != null) {
                                                    currentFrameFragments.add(OcrFragment(normalizedChar, char, centerX, width, now))
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (com.glipverup.app.BuildConfig.DEBUG && rawLog.isNotEmpty()) {
                                Log.d("ZZZGlip_RawOCR", "Raw [$mode]: $rawLog")
                            }

                            if (currentFrameFragments.isNotEmpty()) {
                                ocrBuffer.addAll(currentFrameFragments)
                            }
                            
                            ocrBuffer.removeIf { it.timestamp < now - 2000 }
                            
                            // ログ削減: Heartbeatを30回に1回に制限
                            heartbeatCount++
                            if (heartbeatCount >= 30) {
                                val uniqueChars = ocrBuffer.map { it.char }.distinct()
                                Log.d("ZZZGlip_OCR", "HB [$mode]: count=${uniqueChars.size}, chars='${uniqueChars.joinToString("")}', bufSize=${ocrBuffer.size}")
                                heartbeatCount = 0
                            }

                            if (checkSpatioTemporalMatch(mode)) {
                                Log.i("ZZZGlip_Detection", "!!! WIPEOUT DETECTED ($mode) !!!")
                                handleWipeoutDetected()
                            }
                            isProcessing.set(false)
                        }
                        .addOnFailureListener {
                            isProcessing.set(false)
                        }
                }
            }
        } finally {
            recognizerRaw.close()
            recognizerColor.close()
            localHandlerThread.quitSafely()
            reusableBitmap?.recycle()
            ocrReusableBitmap?.recycle()
            processedReusableBitmap?.recycle()
        }
    }

    private fun checkSpatioTemporalMatch(mode: String): Boolean {
        // バッファ内に存在する「合格済み」のユニークな文字種をカウント
        val uniqueChars = ocrBuffer.map { it.char }.distinct()
        val count = uniqueChars.size

        // 英字の裏付けチェック: 少なくとも1つは生文字が英字であること
        val hasAlphabetEvidence = ocrBuffer.any { it.rawChar in 'A'..'Z' }

        // 判定の進捗を可視化
        val bufStr = if (uniqueChars.isEmpty()) "(empty)" else uniqueChars.joinToString("")
        Log.d("ZZZGlip_OCR", "HB [$mode]: count=$count, chars='$bufStr', alphabet=$hasAlphabetEvidence, bufSize=${ocrBuffer.size}")
        
        if (count >= 2 && hasAlphabetEvidence) {
            val foundStr = uniqueChars.sortedBy { "WIPEOUT".indexOf(it) }.joinToString("")
            Log.d("ZZZGlip_Fusion", "Success: DistinctCount=$count, Chars='$foundStr', Evidence=$hasAlphabetEvidence")
            return true
        }
        return false
    }

    private fun isSpatialMatch(char: Char, x: Int, width: Int): Char? {
        // 200px幅における定義: (中心X基準, 理想幅基準, 許容文字セット)
        val slots = listOf(
            Triple(29.0, 40.0, setOf('W', 'V', 'M')),           // Slot 0: W (40%)
            Triple(59.5, 17.0, setOf('I', '1', '|', 'L')),      // Slot 1: I (50%)
            Triple(86.0, 19.5, setOf('P', 'F', 'B', 'I')),      // Slot 2: P (40%)
            Triple(110.0, 20.5, setOf('E', 'L', 'F')),          // Slot 3: E (40%)
            Triple(140.0, 22.5, setOf('O', '0', 'Q', 'D', 'C')),// Slot 4: O (40%)
            Triple(170.0, 18.5, setOf('U', 'V', 'L', 'J')),     // Slot 5: U (40%)
            Triple(187.5, 17.0, setOf('T', 'I', 'L'))           // Slot 6: T (50%)
        )
        val expectedChars = "WIPEOUT"
        
        for (i in slots.indices) {
            val (targetX, idealW, allowedSet) = slots[i]
            
            // ① 幅の下限チェック (I, Tは50%、他は40%)
            val threshold = if (i == 1 || i == 6) 0.5 else 0.4
            if (width < (idealW * threshold).toInt()) continue

            // ② 位置チェック: ±20px (実測ベースに寄せたため範囲を絞る)
            if (abs(x - targetX.toInt()) > 20) continue

            // ③ 文字種チェック
            if (char in allowedSet) {
                return expectedChars[i]
            }
        }
        return null
    }

    private fun preprocessForChannel(source: Bitmap, out: Bitmap, mode: String) {
        val w = source.width; val h = source.height
        val size = w * h
        if (reusablePixels == null || reusablePixels!!.size != size) {
            reusablePixels = IntArray(size)
            reusableOutPixels = IntArray(size)
        }
        val pixels = reusablePixels!!
        val outPixels = reusableOutPixels!!
        
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        
        if (mode == "RAW") {
            // RAWモード: そのままコピー (UIマスクのみ適用)
            System.arraycopy(pixels, 0, outPixels, 0, pixels.size)
        } else {
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
                
                // 黄色抽出ベースのグレースケール変換 (Ver 6.2.7)
                // 二値化(Black/White)や過度な黒色化はML Kitの認識精度を下げるため、
                // 黄色の要素が強いほど濃いグレー(最低60)になるように変換し、トポロジー（文字の穴など）を維持する。
                
                // 黄色度合い(Yellow Intensity): RとGの平均からBを引いたもの。
                val yellowVal = ((r + g) / 2.0 - b).toInt().coerceIn(0, 255)
                
                // 閾値以上の黄色を抽出。
                // 輝度を下げすぎないことで、文字内の余白や背景との境界線を維持する。
                val gray = if (yellowVal > 40) {
                    (255 - (yellowVal * 0.8).toInt()).coerceIn(60, 255)
                } else {
                    255
                }
                outPixels[i] = (0xff shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
        }

        // 上下25%を白マスク（文字検知対象外エリア）
        val maskH = (h * 0.25).toInt()
        for (y in 0 until h) {
            if (y < maskH || y > h - maskH) {
                for (x in 0 until w) outPixels[y * w + x] = android.graphics.Color.WHITE
            }
        }
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
    }

    private fun addToAtlasBuffer(bitmap: Bitmap) {
        synchronized(atlasLock) {
            atlasFrames.add(Bitmap.createBitmap(bitmap))
            if (atlasFrames.size >= 10) {
                val framesToSave = atlasFrames.toList()
                atlasFrames.clear()
                serviceScope.launch(Dispatchers.IO) {
                    saveDiagAtlas(framesToSave)
                }
            }
        }
    }

    private fun saveDiagAtlas(frames: List<Bitmap>) {
        if (frames.isEmpty()) return
        val fw = frames[0].width
        val fh = frames[0].height
        
        // 2列 x 5行のAtlas画像を作成 (10枚用)
        val atlasW = fw * 2
        val atlasH = fh * 5
        val atlasBitmap = Bitmap.createBitmap(atlasW, atlasH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(atlasBitmap)
        
        for (i in frames.indices) {
            val col = i % 2
            val row = i / 2
            canvas.drawBitmap(frames[i], col.toFloat() * fw, row.toFloat() * fh, null)
            frames[i].recycle()
        }
        
        val now = System.currentTimeMillis()
        serviceScope.launch(Dispatchers.IO) {
            try {
                val timeStr = java.text.SimpleDateFormat("HHmmss_SSS", java.util.Locale.getDefault()).format(java.util.Date(now))
                val fileName = "atlas_$timeStr.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/ZZZGlip/diag")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { 
                    contentResolver.openOutputStream(it)?.use { out -> 
                        atlasBitmap.compress(Bitmap.CompressFormat.JPEG, 70, out) 
                    } 
                }
                atlasBitmap.recycle()
            } catch (e: Exception) {
                atlasBitmap.recycle()
            }
        }
    }

    private fun handleWipeoutDetected() {
        if (isAutoSavePending.getAndSet(true)) return
        serviceScope.launch(Dispatchers.Main) {
            floatingView?.let { view ->
                view.findViewById<android.widget.TextView>(R.id.btn_save)?.text = "WO!"
                view.alpha = 0.4f
            }
        }
        serviceScope.launch(Dispatchers.Default) {
            delay(5000)
            saveBufferInternal(10000L, isAutoSave = true)
            isAutoSavePending.set(false)
        }
    }

    private fun cleanUpOcrBitmap(bitmap: Bitmap) {
        val w = bitmap.width; val h = bitmap.height
        val pix = IntArray(w * h); bitmap.getPixels(pix, 0, w, 0, 0, w, h)
        val out = pix.copyOf(); val black = android.graphics.Color.BLACK; val white = android.graphics.Color.WHITE
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                if (pix[y * w + x] == black) {
                    var n = 0; for (dy in -1..1) { for (dx in -1..1) { if ((dx != 0 || dy != 0) && pix[(y + dy) * w + (x + dx)] == black) n++ } }
                    if (n <= 1) out[y * w + x] = white
                }
            }
        }
        val dilated = out.copyOf()
        for (y in 1 until h - 1) { for (x in 1 until w - 1) { if (out[y * w + x] == black) { for (dy in -1..1) { for (dx in -1..1) dilated[(y + dy) * w + (x + dx)] = black } } } }
        bitmap.setPixels(dilated, 0, w, 0, 0, w, h)
    }

    private fun stopEncoderOnly() {
virtualDisplay?.surface = null; try { videoEncoder?.stop() } catch (e: Exception) {} finally { videoEncoder?.release(); videoEncoder = null }; try { audioEncoder?.stop() } catch (e: Exception) {} finally { audioEncoder?.release(); audioEncoder = null }; videoEncoderSurface?.release(); videoEncoderSurface = null }
    private fun stopRecording() { val was = isRecording.getAndSet(false); if (!was && mediaProjection == null) return; audioJob?.cancel(); audioJob = null; detectionJob?.cancel(); detectionJob = null; stopEncoderOnly(); virtualDisplay?.release(); virtualDisplay = null; audioRecord?.let { try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } catch (e: Exception) {} ; try { it.release() } catch (e: Exception) {} }; audioRecord = null; synchronized(muxerLock) { try { if (muxerStarted) { if (samplesWrittenToCurrentMuxer) muxer?.stop(); muxer?.release() } } catch (e: Exception) {} ; muxer = null; muxerStarted = false; samplesWrittenToCurrentMuxer = false }; mediaProjection?.stop(); mediaProjection = null; segments.forEach { if (it.exists()) it.delete() }; segments.clear(); handler.post { floatingView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }; floatingView = null } }
    private fun createNotificationChannel() { val chan = NotificationChannel(CHANNEL_ID, "ZZZGlip Recorder", NotificationManager.IMPORTANCE_LOW); val manager = getSystemService(NotificationManager::class.java); manager.createNotificationChannel(chan) }
    private fun updateNotification() { val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager; manager.notify(NOTIFICATION_ID, createNotification(currentBufferTime)) }
    private fun createNotification(time: String): Notification { val stopPI = PendingIntent.getService(this, 0, Intent(this, ScreenRecorderService::class.java).apply { action = "STOP_SERVICE" }, PendingIntent.FLAG_IMMUTABLE); val listPI = PendingIntent.getActivity(this, 1, Intent(this, TimeSelectionActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT); return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("ZZZGlip Recording").setContentText("Buffer: $time").setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).addAction(0, "Stop", stopPI).addAction(0, "Time List", listPI).build() }
    override fun onDestroy() { super.onDestroy(); stopRecording(); serviceJob.cancel() }
}
