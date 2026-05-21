package com.glipverup.app.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
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
import androidx.core.app.NotificationCompat
import com.glipverup.app.R
import com.glipverup.app.MainActivity
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            super.attachBaseContext(newBase.createAttributionContext("glip_recorder"))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun getAttributionTag(): String? = "glip_recorder"

    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var settingsManager: SettingsManager
    private var floatingView: View? = null
    private var videoEncoderSurface: Surface? = null
    
    private var captureWidth = 1280
    private var captureHeight = 720

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
    
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    private var detectionJob: Job? = null

    private var muxer: MediaMuxer? = null
    private val muxerLock = Any()
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var totalAudioSamples = 0L
    private var lastMuxerRotationTimeMs = 0L
    private var muxerStarted = false
    private var samplesWrittenToCurrentMuxer = false
    private var rotateMuxerNextLoop = false
    private var pendingRotationRestart = false
    private var lastMatchTimeMs = 0L
    private var matchCount = 0

    private var persistedVideoFormat: MediaFormat? = null
    private var persistedAudioFormat: MediaFormat? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val attributionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createAttributionContext("glip_recorder")
        } else { this }

        windowManager = attributionContext.getSystemService(WINDOW_SERVICE) as WindowManager
        projectionManager = attributionContext.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        settingsManager = SettingsManager(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(currentBufferTime))

        serviceScope.launch { settingsManager.bufferTimeFlow.collectLatest { currentBufferTime = it; updateNotification() } }
        serviceScope.launch { settingsManager.wipeoutDetectionFlow.collectLatest { isWipeoutDetectionEnabled = it } }
    }

    private fun loadAd() {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_RECORDING" -> handleStartRecording(intent)
            "STOP_SERVICE" -> { stopRecording(); stopSelf() }
            "SAVE_BUFFER" -> saveLastMinutes()
            "CHANGE_TIME" -> {
                intent.getStringExtra("selected_time")?.let { newTime ->
                    serviceScope.launch { settingsManager.updateBufferTime(newTime); currentBufferTime = newTime; updateNotification() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStartRecording(intent: Intent) {
        if (isRecording.get()) return
        val resultCode = intent.getIntExtra("resultCode", 0)
        val data = intent.getParcelableExtra<Intent>("data") ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, createNotification(currentBufferTime), type)
            }
        } catch (e: Exception) { Log.e("ZZZGlip", "Foreground error", e) }
        mediaProjection = projectionManager.getMediaProjection(resultCode, data) ?: return
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopRecording() }
        }, handler)
        startRecordingMainLoop()
    }

    private fun startRecordingMainLoop() {
        isRecording.set(true); sessionStartTimeMs = System.currentTimeMillis(); showFloatingButton()
        serviceScope.launch(Dispatchers.Default) {
            while (isRecording.get() && currentCoroutineContext().isActive) {
                val mp = mediaProjection ?: break
                prepareAndStartRecording(mp)
                while (isRecording.get() && !pendingRotationRestart && currentCoroutineContext().isActive) { delay(500) }
                stopEncoderOnly()
                if (!isRecording.get() || !currentCoroutineContext().isActive) break
                delay(500); pendingRotationRestart = false
            }
        }
        detectionJob = serviceScope.launch(Dispatchers.Default) { startDetectionLoop() }
    }

    private fun initializeAudioRecord(mp: MediaProjection) {
        val sampleRate = 44100; val minBufSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mp).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build()
            audioRecord = AudioRecord.Builder().setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(minBufSize * 2).setAudioPlaybackCaptureConfig(config).build()
            audioRecord?.startRecording()
        } catch (e: Exception) { Log.e("ZZZGlip", "Audio fail", e) }
    }

    private fun prepareAndStartRecording(mp: MediaProjection) {
        if (!isRecording.get()) return
        try {
            val metrics = DisplayMetrics(); @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(metrics)
            val rotation = windowManager.defaultDisplay.rotation; val isPortrait = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
            val vW = if (isPortrait) minOf(metrics.widthPixels, 1080) else maxOf(metrics.widthPixels, 1920)
            val vW2 = vW + (if (vW % 16 != 0) 16 - (vW % 16) else 0)
            val vH = if (isPortrait) maxOf(metrics.heightPixels, 1920) else minOf(metrics.heightPixels, 1080)
            val vH2 = vH + (if (vH % 16 != 0) 16 - (vH % 16) else 0)
            captureWidth = vW2; captureHeight = vH2
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vW2, vH2).apply { setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface); setInteger(MediaFormat.KEY_BIT_RATE, 12 * 1000 * 1000); setInteger(MediaFormat.KEY_FRAME_RATE, 60); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) }
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); videoEncoderSurface = createInputSurface(); start()
            }
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply { setInteger(MediaFormat.KEY_BIT_RATE, 128000) }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
            initializeAudioRecord(mp); rotateMuxer()
            
            // Android 14 対策: VirtualDisplayは一度作ったら resize() / surface 差し替えで運用する
            val currentVd = virtualDisplay
            if (currentVd == null) {
                virtualDisplay = mp.createVirtualDisplay("ZZZGlip", vW2, vH2, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, videoEncoderSurface, null, null)
            } else {
                currentVd.resize(vW2, vH2, metrics.densityDpi)
                currentVd.surface = videoEncoderSurface
            }
            
            serviceScope.launch(Dispatchers.Default) { recordingLoop() }
        } catch (e: Exception) { 
            Log.e("ZZZGlip", "Prepare fail", e) 
        }
    }

    private fun rotateMuxer() {
        synchronized(muxerLock) {
            try {
                if (muxerStarted) { if (samplesWrittenToCurrentMuxer) muxer?.stop(); muxer?.release() }
                muxerStarted = false; samplesWrittenToCurrentMuxer = false; videoTrackIndex = -1; audioTrackIndex = -1
                val file = File(cacheDir, "seg_${System.nanoTime()}${if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) "_P.mp4" else "_L.mp4"}")
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); segments.add(file)
                while (segments.size > 20) { segments.poll()?.let { if (it.exists()) it.delete() } }
                rotateMuxerNextLoop = false
                persistedVideoFormat?.let { videoTrackIndex = muxer?.addTrack(it) ?: -1 }
                persistedAudioFormat?.let { audioTrackIndex = muxer?.addTrack(it) ?: -1 }
                checkMuxerStart()
            } catch (e: Exception) { Log.e("ZZZGlip", "Rotate fail", e) }
        }
    }

    private fun checkMuxerStart() {
        if (!muxerStarted && videoTrackIndex >= 0 && (audioTrackIndex >= 0 || audioRecord == null)) {
            try { muxer?.start(); muxerStarted = true } catch (e: Exception) { Log.e("ZZZGlip", "Muxer start fail", e) }
        }
    }

    private var videoTimelineOffsetUs = -1L; private var audioTimelineOffsetUs = -1L; private var segmentFirstPtsUs = -1L

    private suspend fun audioRecordingLoop(audioPCMBuffer: ByteBuffer) {
        val sampleRate = 44100
        audioLoop@while (isRecording.get() && !pendingRotationRestart && currentCoroutineContext().isActive) {
            val record = audioRecord ?: run { delay(100); break@audioLoop }
            audioPCMBuffer.clear(); val read = try { record.read(audioPCMBuffer, 2048) } catch (e: Exception) { -1 }
            if (read > 0) {
                val inputIndex = try { audioEncoder?.dequeueInputBuffer(1000) ?: -1 } catch (e: Exception) { -1 }
                if (inputIndex >= 0) {
                    val inputBuffer = audioEncoder?.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear(); val bytesToCopy = minOf(read, inputBuffer.capacity()); audioPCMBuffer.position(0); audioPCMBuffer.limit(bytesToCopy); inputBuffer.put(audioPCMBuffer)
                        val ptsUs = (totalAudioSamples * 1_000_000L) / sampleRate
                        if (audioTimelineOffsetUs == -1L) audioTimelineOffsetUs = (System.nanoTime() / 1000) - ptsUs
                        audioEncoder?.queueInputBuffer(inputIndex, 0, bytesToCopy, ptsUs, 0)
                        totalAudioSamples += (read / 2) // 16bit = 2bytes per sample
                    }
                }
            } else if (read < 0) { if (isRecording.get() && !pendingRotationRestart) { delay(100); break@audioLoop } }
        }
    }

    private suspend fun recordingLoop() {
        val vBufferInfo = MediaCodec.BufferInfo(); val aBufferInfo = MediaCodec.BufferInfo()
        val audioPCMBuffer = ByteBuffer.allocateDirect(4096); val lastOrientation = resources.configuration.orientation
        segmentFirstPtsUs = -1L; videoTimelineOffsetUs = -1L; audioTimelineOffsetUs = -1L; totalAudioSamples = 0L
        lastMuxerRotationTimeMs = System.currentTimeMillis()
        audioJob = serviceScope.launch(Dispatchers.IO) { audioRecordingLoop(audioPCMBuffer) }
        while (isRecording.get() && !pendingRotationRestart && currentCoroutineContext().isActive) {
            if (resources.configuration.orientation != lastOrientation) { pendingRotationRestart = true; break }
            if (System.currentTimeMillis() - lastMuxerRotationTimeMs > segmentDurationMs) { rotateMuxerNextLoop = true }
            videoEncoder?.let { encoder ->
                val outIdx = try { encoder.dequeueOutputBuffer(vBufferInfo, 1000) } catch (_: Exception) { -1 }
                if (outIdx >= 0) {
                    val buffer = encoder.getOutputBuffer(outIdx)
                    if (buffer != null) {
                        val absPts = vBufferInfo.presentationTimeUs + (if (videoTimelineOffsetUs == -1L) { videoTimelineOffsetUs = (System.nanoTime() / 1000) - vBufferInfo.presentationTimeUs; videoTimelineOffsetUs } else videoTimelineOffsetUs)
                        if (rotateMuxerNextLoop && (vBufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)) { rotateMuxer(); lastMuxerRotationTimeMs = System.currentTimeMillis() }
                        synchronized(muxerLock) {
                            if (videoTrackIndex >= 0 && muxerStarted) {
                                if (segmentFirstPtsUs == -1L) segmentFirstPtsUs = absPts
                                if ((vBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    val pts = absPts - segmentFirstPtsUs
                                    if (pts >= 0) { vBufferInfo.presentationTimeUs = pts; muxer?.writeSampleData(videoTrackIndex, buffer, vBufferInfo); samplesWrittenToCurrentMuxer = true }
                                }
                            }
                        }
                    }
                    try { encoder.releaseOutputBuffer(outIdx, false) } catch (e: Exception) {}
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { synchronized(muxerLock) { persistedVideoFormat = encoder.outputFormat; if (videoTrackIndex < 0) { videoTrackIndex = muxer?.addTrack(persistedVideoFormat!!) ?: -1; checkMuxerStart() } } }
            }
            audioEncoder?.let { encoder ->
                val outIdx = try { encoder.dequeueOutputBuffer(aBufferInfo, 1000) } catch (_: Exception) { -1 }
                if (outIdx >= 0) {
                    val buffer = encoder.getOutputBuffer(outIdx)
                    if (buffer != null) {
                        val absPts = aBufferInfo.presentationTimeUs + audioTimelineOffsetUs
                        synchronized(muxerLock) {
                            if (audioTrackIndex >= 0 && muxerStarted) {
                                if (segmentFirstPtsUs == -1L) segmentFirstPtsUs = absPts
                                if ((aBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    val pts = absPts - segmentFirstPtsUs
                                    if (pts >= 0) { aBufferInfo.presentationTimeUs = pts; muxer?.writeSampleData(audioTrackIndex, buffer, aBufferInfo); samplesWrittenToCurrentMuxer = true }
                                }
                            }
                        }
                    }
                    try { encoder.releaseOutputBuffer(outIdx, false) } catch (e: Exception) {}
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { synchronized(muxerLock) { persistedAudioFormat = encoder.outputFormat; if (audioTrackIndex < 0) { audioTrackIndex = muxer?.addTrack(persistedAudioFormat!!) ?: -1; checkMuxerStart() } } }
            }
        }
        audioJob?.cancel(); audioJob = null
    }

    private fun saveLastMinutes() { val targetMs = when(currentBufferTime) { "6 min" -> 360000L; "5 min" -> 300000L; "3 min" -> 180000L; "1 min" -> 60000L; "30 sec" -> 30000L; "15 sec" -> 15000L; else -> 15000L }; saveBufferInternal(targetMs, false) }

    private fun saveBufferInternal(targetMs: Long, isAutoSave: Boolean) {
        // handleWipeoutDetected ですでに isSaving が true にセットされているため
        // ここでの getAndSet(true) は重複防止として機能する
        if (isAutoSave) {
             // すでにセット済み
        } else {
            if (isSaving.getAndSet(true)) return
        }
        
        saveScope.launch {
            withContext(NonCancellable) {
                try {
                    withContext(Dispatchers.Main) {
                        floatingView?.findViewById<View>(R.id.save_progress)?.visibility = View.VISIBLE
                        if (isAutoSave) {
                            floatingView?.findViewById<android.widget.TextView>(R.id.btn_save)?.text = "WO!"
                        }
                        floatingView?.alpha = 0.4f
                    }
                    rotateMuxerNextLoop = true; var waitCount = 0; while (rotateMuxerNextLoop && waitCount < 20) { delay(100); waitCount++ }
                    val targetSuffix = if (segments.count { it.name.endsWith("_L.mp4") } >= segments.count { it.name.endsWith("_P.mp4") }) "_L.mp4" else "_P.mp4"
                    val available = segments.filter { it.exists() && it.name.endsWith(targetSuffix) && it.length() > 512 && it.lastModified() >= (sessionStartTimeMs - 2000) }.toList()
                    if (available.isNotEmpty()) {
                        val fileName = "clip_${System.currentTimeMillis()}.mp4"
                        val pfd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val cv = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, fileName); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ZZZGlip") }
                            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)?.let { contentResolver.openFileDescriptor(it, "w") }
                        } else {
                            val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ZZZGlip").apply { if (!exists()) mkdirs() }
                            ParcelFileDescriptor.open(File(moviesDir, fileName), ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
                        }
                        if (pfd != null) { fastMergeFiles(available, targetMs, pfd); pfd.close() }
                    }
                    
                    // 広告表示のためにMainActivityを起動
                    if (!BuildConfig.DEBUG) {
                        val adIntent = Intent(this@ScreenRecorderService, MainActivity::class.java).apply {
                            putExtra("SHOW_AD", true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(adIntent)
                    }
                } catch (e: Exception) { Log.e("ZZZGlip", "Save fail", e) } finally {
                    isSaving.set(false)
                    withContext(Dispatchers.Main) { 
                        floatingView?.findViewById<View>(R.id.save_progress)?.visibility = View.GONE
                        floatingView?.findViewById<android.widget.TextView>(R.id.btn_save)?.text = "SAVE"
                        floatingView?.alpha = 0.6f 
                    }
                }
            }
        }
    }

    private fun fastMergeFiles(files: List<File>, targetMs: Long, pfd: ParcelFileDescriptor) {
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); val durations = files.associateWith { getFileDuration(it) }; var accumulated = 0L; val toMerge = mutableListOf<File>()
        for (f in files.reversed()) { val d = durations[f] ?: 0L; if (d == 0L) continue; toMerge.add(0, f); accumulated += d; if (accumulated >= targetMs) break }
        if (toMerge.isEmpty()) { muxer.release(); return }
        var videoFmt: MediaFormat? = null; var audioFmt: MediaFormat? = null
        for (f in toMerge) {
            val ex = MediaExtractor(); try { ex.setDataSource(f.absolutePath)
                for (i in 0 until ex.trackCount) { val fmt = ex.getTrackFormat(i); val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""; if (videoFmt == null && mime.startsWith("video/")) videoFmt = fmt; if (audioFmt == null && mime.startsWith("audio/")) audioFmt = fmt }
            } catch (_: Exception) {} finally { ex.release() }; if (videoFmt != null && audioFmt != null) break
        }
        if (videoFmt == null) { muxer.release(); return }
        val vTIdx = muxer.addTrack(videoFmt); val aTIdx = if (audioFmt != null) muxer.addTrack(audioFmt) else -1
        try { muxer.start() } catch (e: Exception) { muxer.release(); return }
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024); val info = MediaCodec.BufferInfo(); var globalOffsetUs = 0L; val startClipUs = if (accumulated > targetMs) (accumulated - targetMs) * 1000 else 0L
        var firstPts = -1L; var samplesWritten = false
        for (i in toMerge.indices) {
            val e = MediaExtractor(); try { e.setDataSource(toMerge[i].absolutePath)
                val vi = (0 until e.trackCount).firstOrNull { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }; val ai = (0 until e.trackCount).firstOrNull { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                var fileMinPts = Long.MAX_VALUE; if (vi != null) { e.selectTrack(vi); if (i == 0) e.seekTo(startClipUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC); if (e.sampleTime != -1L) fileMinPts = minOf(fileMinPts, e.sampleTime); e.unselectTrack(vi) }
                if (ai != null) { e.selectTrack(ai); if (i == 0) e.seekTo(startClipUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC); if (e.sampleTime != -1L) fileMinPts = minOf(fileMinPts, e.sampleTime); e.unselectTrack(ai) }
                if (fileMinPts == Long.MAX_VALUE) fileMinPts = 0L; if (vi != null) e.selectTrack(vi); if (ai != null && aTIdx != -1) e.selectTrack(ai)
                if (i == 0) e.seekTo(startClipUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC) else e.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var maxPtsInFile = 0L
                while (true) {
                    val trackIdx = e.sampleTrackIndex; if (trackIdx < 0) break; val targetTIdx = if (trackIdx == vi) vTIdx else if (trackIdx == ai) aTIdx else -1
                    if (targetTIdx == -1) { e.advance(); continue }
                    val size = e.readSampleData(buffer, 0); if (size < 0) break; val pts = globalOffsetUs + (e.sampleTime - fileMinPts)
                    if (firstPts == -1L) firstPts = pts
                    @Suppress("WrongConstant") info.set(0, size, pts - firstPts, e.sampleFlags)
                    muxer.writeSampleData(targetTIdx, buffer, info); maxPtsInFile = maxOf(maxPtsInFile, pts - firstPts); samplesWritten = true; e.advance()
                }
                globalOffsetUs = firstPts + maxPtsInFile + 1000L
            } catch (e: Exception) {} finally { e.release() }
        }
        try { if (samplesWritten) muxer.stop() } catch (e: Exception) {} finally { muxer.release() }
    }

    private fun getFileDuration(file: File): Long { val r = MediaMetadataRetriever(); return try { r.setDataSource(file.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L } catch (_: Exception) { 0L } finally { r.release() } }

    private fun showFloatingButton() {
        serviceScope.launch {
            try {
                val savedX = settingsManager.floatingXFlow.first(); val savedY = settingsManager.floatingYFlow.first()
                withContext(Dispatchers.Main) {
                    val metrics = DisplayMetrics(); @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(metrics)
                    floatingView = LayoutInflater.from(this@ScreenRecorderService).inflate(R.layout.layout_floating_button, null).apply { alpha = 0.6f }
                    val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = savedX ?: (metrics.widthPixels - 200); y = savedY ?: (metrics.heightPixels - 400) }
                    floatingView?.setOnTouchListener(object : View.OnTouchListener {
                        private var iX = 0; private var iY = 0; private var itX = 0f; private var itY = 0f; private var mv = false
                        override fun onTouch(v: View, e: MotionEvent): Boolean {
                            when (e.action) {
                                MotionEvent.ACTION_DOWN -> { iX = params.x; iY = params.y; itX = e.rawX; itY = e.rawY; mv = false }
                                MotionEvent.ACTION_MOVE -> { val dx = (e.rawX - itX).toInt(); val dy = (e.rawY - itY).toInt(); if (abs(dx) > 10 || abs(dy) > 10) { mv = true; params.x = (iX + dx).coerceIn(0, (metrics.widthPixels - v.width).coerceAtLeast(0)); params.y = (iY + dy).coerceIn(0, (metrics.heightPixels - v.height).coerceAtLeast(0)); windowManager.updateViewLayout(floatingView, params) } }
                                MotionEvent.ACTION_UP -> { if (mv) { serviceScope.launch { settingsManager.saveFloatingPosition(params.x, params.y) } } else if (!isSaving.get()) { saveLastMinutes() } }
                            }
                            return true
                        }
                    }); windowManager.addView(floatingView, params)
                }
            } catch (e: Exception) {}
        }
    }

    private suspend fun startDetectionLoop() {
        Log.i("ZZZGlip_Detection", "Loop Started"); val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val backgroundExecutor = Dispatchers.Default.asExecutor(); val localHandlerThread = HandlerThread("ZZZGlip-Detection").apply { start() }
        val isProcessing = AtomicBoolean(false); var reusableBitmap: Bitmap? = null
        matchCount = 0; lastMatchTimeMs = 0L
        try {
            while (currentCoroutineContext().isActive) {
                delay(400); if (!isRecording.get() || isProcessing.get() || isSaving.get()) continue
                // isWipeoutDetectionEnabled は外さず、検出ロジック自体を安定版に合わせる
                if (!isWipeoutDetectionEnabled) continue

                val vd = virtualDisplay ?: continue; val surface = vd.surface ?: continue; if (!surface.isValid) continue
                val targetW = 640; val targetH = (targetW * (captureHeight.toFloat() / captureWidth.toFloat())).toInt()
                val bitmap = if (reusableBitmap != null && reusableBitmap.width == targetW && reusableBitmap.height == targetH) reusableBitmap else { reusableBitmap?.recycle(); Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888).also { reusableBitmap = it } }
                val currentBgHandler = Handler(localHandlerThread.looper); val completable = CompletableDeferred<Int>()
                currentBgHandler.post { try { PixelCopy.request(surface, bitmap, { result -> completable.complete(result) }, currentBgHandler) } catch (e: Exception) { completable.completeExceptionally(e) } }
                if (try { completable.await() } catch (e: Exception) { -1 } == PixelCopy.SUCCESS) {
                    isProcessing.set(true); val yellowDensity = calculateYellowDensity(bitmap)
                    recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener(backgroundExecutor) { visionText ->
                        try {
                            var frameDetected = false
                            for (block in visionText.textBlocks) {
                                // 形状判定: アスペクト比が 2.5 〜 8.5 であること
                                val rect = block.boundingBox ?: continue
                                val aspect = rect.width().toFloat() / rect.height().toFloat()
                                if (aspect !in 2.5f..8.5f) continue

                                val text = block.text.uppercase().replace(Regex("[^A-Z]+"), "")
                                if (text.length < 3) continue
                                
                                val isFuzzy = isFuzzyMatch(text, "WIPEOUT", if (text.length < 6) 1 else 2)
                                val wpuCount = text.count { it == 'W' || it == 'P' || it == 'U' }
                                
                                // 論理和による確定: ファジー一致 または (黄色密度OK かつ 特徴文字3つ以上)
                                if (isFuzzy || (yellowDensity > 0.008f && wpuCount >= 3)) {
                                    Log.d("ZZZGlip_Detection", "Match! Text: $text, Aspect: $aspect, Yellow: $yellowDensity, WPU: $wpuCount")
                                    frameDetected = true
                                    break
                                }
                            }
                            
                            val now = System.currentTimeMillis()
                            if (frameDetected) {
                                if (now - lastMatchTimeMs > 2000) { matchCount = 1 } else { matchCount++ }
                                lastMatchTimeMs = now
                                Log.d("ZZZGlip_Detection", "Match in frame ($matchCount/3)")
                                if (matchCount >= 3) {
                                    // 正常動作の完全復旧: 即座にアトミックロックをかけて連射を防止
                                    if (isSaving.compareAndSet(false, true)) {
                                        Log.i("ZZZGlip_Detection", "!!! WIPEOUT DETECTED (Logic Confirmed) !!!")
                                        matchCount = 0
                                        handleWipeoutDetected()
                                    }
                                }
                            } else if (now - lastMatchTimeMs > 1500) {
                                if (matchCount > 0) Log.d("ZZZGlip_Detection", "Match timeout, resetting count")
                                matchCount = 0
                            }
                        } finally { isProcessing.set(false) }
                    }.addOnFailureListener { isProcessing.set(false) }
                }
            }
        } finally { Log.i("ZZZGlip_Detection", "Loop Exit"); recognizer.close(); localHandlerThread.quitSafely(); reusableBitmap?.recycle() }
    }

    private fun isYellowish(pixel: Int): Boolean { val hsv = FloatArray(3); android.graphics.Color.colorToHSV(pixel, hsv); return hsv[0] in 35f..65f && hsv[1] > 0.45f && hsv[2] > 0.6f }
    
    private fun calculateYellowDensity(bitmap: Bitmap): Float { val w = bitmap.width; val h = bitmap.height; var yellow = 0; val step = 4; var total = 0
        for (y in (h * 0.3).toInt() until (h * 0.7).toInt() step step) { for (x in 0 until w step step) { if (isYellowish(bitmap.getPixel(x, y))) yellow++; total++ } }
        return if (total > 0) yellow.toFloat() / total else 0f
    }

    private fun isFuzzyMatch(detected: String, target: String, maxDist: Int = 2): Boolean {
        if (detected.length < 3 || detected.length > 15) return false
        if (detected.contains(target)) return true
        val s1 = detected.replace("0", "O").replace("1", "I").replace("8", "B"); val dp = Array(s1.length + 1) { IntArray(target.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i; for (j in 0..target.length) dp[0][j] = j
        for (i in 1..s1.length) { for (j in 1..target.length) { val cost = if (s1[i - 1] == target[j - 1]) 0 else 1; dp[i][j] = minOf(minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost) } }
        return dp[s1.length][target.length] <= maxDist
    }

    private fun handleWipeoutDetected() {
        serviceScope.launch(Dispatchers.Main) { 
            Log.i("ZZZGlip", "WIPEOUT! Triggering auto-save..."); 
            // 仕様通り: 検知から5秒待機して保存（前後5秒を収めるため）
            delay(5000)
            saveBufferInternal(10000L, true)
        }
    }

    private fun stopEncoderOnly() { 
        virtualDisplay?.surface = null
        try { videoEncoder?.stop() } catch (_: Exception) {} finally { videoEncoder?.release(); videoEncoder = null }
        try { audioEncoder?.stop() } catch (_: Exception) {} finally { audioEncoder?.release(); audioEncoder = null }
        videoEncoderSurface?.release(); videoEncoderSurface = null 
    }

    private fun stopRecording() {
        if (!isRecording.getAndSet(false) && mediaProjection == null) return
        audioJob?.cancel(); audioJob = null; detectionJob?.cancel(); detectionJob = null
        stopEncoderOnly()
        
        // Android 14 復旧: VirtualDisplay と MediaProjection を完全に破棄して null にする
        virtualDisplay?.release(); virtualDisplay = null
        
        audioRecord?.let { try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } catch (_: Exception) {}; it.release() }; audioRecord = null
        synchronized(muxerLock) { try { if (muxerStarted) { if (samplesWrittenToCurrentMuxer) muxer?.stop(); muxer?.release() } } catch (_: Exception) {}; muxer = null; muxerStarted = false; samplesWrittenToCurrentMuxer = false }
        
        mediaProjection?.stop(); mediaProjection = null 
        
        handler.post { floatingView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }; floatingView = null }
        
        // MainActivityに録画停止を通知（BroadcastReceiverが期待している処理）
        sendBroadcast(Intent("com.glipverup.app.RECORDING_STOPPED").setPackage(packageName))
    }

    private fun createNotificationChannel() { val chan = NotificationChannel(CHANNEL_ID, "ZZZGlip Recorder", NotificationManager.IMPORTANCE_LOW); (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan) }
    private fun updateNotification() { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, createNotification(currentBufferTime)) }
    private fun createNotification(time: String): Notification {
        val stopPI = PendingIntent.getService(this, 0, Intent(this, ScreenRecorderService::class.java).apply { action = "STOP_SERVICE" }, PendingIntent.FLAG_IMMUTABLE)
        val listPI = PendingIntent.getActivity(this, 1, Intent(this, TimeSelectionActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("ZZZGlip Recording").setContentText("Buffer: $time").setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).addAction(0, "Stop", stopPI).addAction(0, "Time List", listPI).build()
    }
    override fun onDestroy() { Log.i("ZZZGlip_Life", "Service onDestroy"); super.onDestroy(); stopRecording(); serviceJob.cancel() }
}
