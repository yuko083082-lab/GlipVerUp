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
import android.net.Uri
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.glipverup.app.R
import com.glipverup.app.data.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRecorderService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var settingsManager: SettingsManager
    private var floatingView: View? = null

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val CHANNEL_ID = "ZZZGlipChannel"
    private val NOTIFICATION_ID = 1001

    private val segmentDurationMs = 30000L
    private val segments = LinkedList<File>()
    private val handler = Handler(Looper.getMainLooper())
    private val isRecording = AtomicBoolean(false)
    private var currentBufferTime = "7 min"
    
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var lastMuxerRotationTimeMs = 0L
    private var muxerStarted = false
    private var rotateMuxerNextLoop = false
    
    // Persistent formats to avoid re-calculating on rotation
    private var persistedVideoFormat: MediaFormat? = null
    private var persistedAudioFormat: MediaFormat? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        settingsManager = SettingsManager(this)
        createNotificationChannel()
        
        serviceScope.launch {
            settingsManager.bufferTimeFlow.collectLatest { time ->
                Log.d("ZZZGlip", "Service: Buffer time updated from settings: $time")
                currentBufferTime = time
                if (isRecording.get()) updateNotification()
            }
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
                Log.d("ZZZGlip", "Service: CHANGE_TIME action received: $newTime")
                if (newTime != null) {
                    serviceScope.launch { 
                        settingsManager.updateBufferTime(newTime)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun handleStartRecording(intent: Intent) {
        if (isRecording.get()) return
        val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        } ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(currentBufferTime), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, createNotification(currentBufferTime))
        }

        handler.postDelayed({
            try {
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (isRecording.get()) {
                            stopRecording()
                            sendBroadcast(Intent("com.glipverup.app.RECORDING_STOPPED").apply { setPackage(packageName) })
                            stopSelf()
                        }
                    }
                }, handler)

                isRecording.set(true)
                serviceScope.launch(Dispatchers.Default) {
                    try {
                        prepareAndStartRecording()
                    } catch (e: Exception) {
                        Log.e("ZZZGlip", "Recording failed", e)
                        withContext(Dispatchers.Main) { stopRecording() }
                    }
                }
                showFloatingButton()
            } catch (e: Exception) {
                Log.e("ZZZGlip", "Failed to get projection", e)
                stopSelf()
            }
        }, 50)
    }

    private suspend fun prepareAndStartRecording() {
        val res = settingsManager.resolutionFlow.first()
        val fps = settingsManager.fpsFlow.first()
        val bitrate = settingsManager.bitrateFlow.first()
        
        val metrics = DisplayMetrics()
        withContext(Dispatchers.Main) { 
            @Suppress("DEPRECATION") 
            windowManager.defaultDisplay.getRealMetrics(metrics) 
        }
        
<<<<<<< HEAD
        val res = when(resStr) {
        val baseRes = when(resStr) {
            "480p" -> Pair(854, 480)
            "1080p" -> Pair(1920, 1080)
            "1440p" -> Pair(2560, 1440)
            "4K" -> Pair(3840, 2160)
            else -> Pair(1280, 720)
        }

        // デバイスの現在の向きに合わせて解像度を調整
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = if (isLandscape) maxOf(baseRes.first, baseRes.second) else minOf(baseRes.first, baseRes.second)
        val height = if (isLandscape) minOf(baseRes.first, baseRes.second) else maxOf(baseRes.first, baseRes.second)

        val file = File(cacheDir, "seg_${System.currentTimeMillis()}.mp4")
        segments.add(file)

        // Keep buffer for max possible time (7 min)
        while (segments.size * segmentDurationMs > 8 * 60 * 1000) {
        // Keep buffer for max possible time (6 min + 1 min margin)
        while (segments.size * segmentDurationMs > 7 * 60 * 1000) {
=======
        val isPortrait = metrics.heightPixels > metrics.widthPixels
        val shortSide = when (res) { "480p" -> 480; "1080p" -> 1080; "1440p" -> 1440; "4K" -> 2160; else -> 720 }
        val scale = shortSide.toFloat() / if (isPortrait) metrics.widthPixels else metrics.heightPixels
        val vW = ((metrics.widthPixels * scale).toInt() / 2) * 2
        val vH = ((metrics.heightPixels * scale).toInt() / 2) * 2

        // Video Encoder
        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vW, vH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate * 1024 * 1024)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoEncoder?.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = videoEncoder?.createInputSurface()
        videoEncoder?.start()

        // Audio Encoder
        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioEncoder?.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder?.start()

        // Internal Audio Capture
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build())
                .setAudioPlaybackCaptureConfig(config)
                .build()
            audioRecord?.startRecording()
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay("ZZZGlipCapture", vW, vH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)

        // Initialize first segment
        rotateMuxer()
        lastMuxerRotationTimeMs = System.currentTimeMillis()

        recordingLoop()
    }

    private fun rotateMuxer() {
        try { muxer?.stop(); muxer?.release() } catch (e: Exception) {}
        val file = File(cacheDir, "seg_${System.currentTimeMillis()}.mp4")
        segments.add(file)
        while (segments.size > 20) {
>>>>>>> main
            val oldest = segments.removeFirst()
            if (oldest.exists()) oldest.delete()
        }
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        videoTrackIndex = -1
        audioTrackIndex = -1
        muxerStarted = false

<<<<<<< HEAD
        Log.d("ZZZGlip", "Setting up MediaRecorder: ${width}x${height}, Landscape=$isLandscape")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            // 内部音声/マイクの録音設定を追加
            setAudioSource(MediaRecorder.AudioSource.MIC) 
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(res.first, res.second)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(width, height)
            setVideoFrameRate(fps)
            setVideoEncodingBitRate(bitrateMbps * 1024 * 1024)
            setAudioEncodingBitRate(128 * 1024)
            setAudioSamplingRate(44100)
            setOutputFile(file.absolutePath)
            prepare()
        }

        val surface = mediaRecorder?.surface

        // 向きが変わっている、または未作成の場合はVirtualDisplayを更新
        if (virtualDisplay == null || virtualDisplay?.display?.rotation != windowManager.defaultDisplay.rotation) {
            virtualDisplay?.release()
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ZZZGlipCapture",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
            Log.d("ZZZGlip", "Created VirtualDisplay")
        } else {
            virtualDisplay?.surface = surface
            Log.d("ZZZGlip", "Reused VirtualDisplay with new surface")
=======
        // RE-ADD tracks immediately if formats are already known from previous segments
        persistedVideoFormat?.let { videoTrackIndex = muxer?.addTrack(it) ?: -1 }
        persistedAudioFormat?.let { audioTrackIndex = muxer?.addTrack(it) ?: -1 }
        
        checkMuxerStart()
        Log.d("ZZZGlip", "Muxer rotated for ${file.name}, started=$muxerStarted")
    }

    private fun checkMuxerStart() {
        if (!muxerStarted && videoTrackIndex >= 0 && (audioTrackIndex >= 0 || audioRecord == null)) {
            try {
                muxer?.start()
                muxerStarted = true
            } catch (e: Exception) {
                Log.e("ZZZGlip", "Failed to start muxer", e)
            }
>>>>>>> main
        }
    }

    private fun recordingLoop() {
        val vBufferInfo = MediaCodec.BufferInfo()
        val aBufferInfo = MediaCodec.BufferInfo()
        val audioPCMBuffer = ByteBuffer.allocateDirect(4096)

        while (isRecording.get()) {
            // Audio Input
            audioRecord?.let { record ->
                val read = record.read(audioPCMBuffer, audioPCMBuffer.capacity())
                if (read > 0) {
                    val inputIndex = audioEncoder?.dequeueInputBuffer(1000) ?: -1
                    if (inputIndex >= 0) {
                        val inputBuffer = audioEncoder?.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        audioPCMBuffer.position(0); audioPCMBuffer.limit(read)
                        inputBuffer?.put(audioPCMBuffer)
                        audioEncoder?.queueInputBuffer(inputIndex, 0, read, System.nanoTime() / 1000, 0)
                    }
                }
            }
            // Video Output
            videoEncoder?.let { encoder ->
                val outIdx = encoder.dequeueOutputBuffer(vBufferInfo, 1000)
                if (outIdx >= 0) {
                    val buffer = encoder.getOutputBuffer(outIdx)
                    if (buffer != null && videoTrackIndex >= 0 && muxerStarted) {
                        try {
                            muxer?.writeSampleData(videoTrackIndex, buffer, vBufferInfo)
                        } catch (e: Exception) { Log.e("ZZZGlip", "Video write error", e) }
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    
                    if (rotateMuxerNextLoop && (vBufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)) {
                        rotateMuxer()
                        lastMuxerRotationTimeMs = System.currentTimeMillis()
                        rotateMuxerNextLoop = false
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d("ZZZGlip", "Video output format changed")
                    persistedVideoFormat = encoder.outputFormat
                    if (videoTrackIndex < 0) {
                        videoTrackIndex = muxer?.addTrack(persistedVideoFormat!!) ?: -1
                        checkMuxerStart()
                    }
                }
            }
            // Audio Output
            audioEncoder?.let { encoder ->
                val outIdx = encoder.dequeueOutputBuffer(aBufferInfo, 1000)
                if (outIdx >= 0) {
                    val buffer = encoder.getOutputBuffer(outIdx)
                    if (buffer != null && audioTrackIndex >= 0 && muxerStarted) {
                        try {
                            muxer?.writeSampleData(audioTrackIndex, buffer, aBufferInfo)
                        } catch (e: Exception) { Log.e("ZZZGlip", "Audio write error", e) }
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d("ZZZGlip", "Audio output format changed")
                    persistedAudioFormat = encoder.outputFormat
                    if (audioTrackIndex < 0) {
                        audioTrackIndex = muxer?.addTrack(persistedAudioFormat!!) ?: -1
                        checkMuxerStart()
                    }
                }
            }
            
            if (System.currentTimeMillis() - lastMuxerRotationTimeMs > segmentDurationMs) {
                rotateMuxerNextLoop = true
            }
        }
    }

    private fun saveLastMinutes() {
        serviceScope.launch {
<<<<<<< HEAD
            // 1. Stop current segment to make it playable
            delay(500) // 最後の数秒が切れるのを防ぐためのバッファ
            stopCurrentSegment()
            
            val durationMs = when(currentBufferTime) {
                "7 min" -> 7 * 60 * 1000L
            val resolution = settingsManager.resolutionFlow.first()
            
            // 解像度に基づいた制限ロジック
            val rawDurationMs = when(currentBufferTime) {
                "7 min", "6 min" -> 6 * 60 * 1000L
                "5 min" -> 5 * 60 * 1000L
                "3 min" -> 3 * 60 * 1000L
                "1 min" -> 60 * 1000L
                "30 sec" -> 30 * 1000L
                "15 sec" -> 15 * 1000L
                else -> 15 * 1000L
            }

            val durationMs = if (resolution == "1440p") minOf(rawDurationMs, 30 * 1000L)
                             else if (resolution == "1080p") minOf(rawDurationMs, 3 * 60 * 1000L)
                             else rawDurationMs

            // 2. Identify segments
            val availableSegments = segments.filter { it.exists() && it.length() > 0 }
            if (availableSegments.isEmpty()) {
                Toast.makeText(this@ScreenRecorderService, "No recording available yet", Toast.LENGTH_SHORT).show()
                recordNextSegment()
                return@launch
=======
            rotateMuxerNextLoop = true
            delay(1200)
            val targetMs = when(currentBufferTime) {
                "7 min" -> 420000L; "5 min" -> 300000L; "3 min" -> 180000L; "1 min" -> 60000L; "30 sec" -> 30000L; "15 sec" -> 15000L; else -> 15000L
            }
            val available = segments.filter { it.exists() && it.length() > 5000 }
            if (available.isEmpty()) return@launch
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val appDir = File(moviesDir, "ZZZGlip").apply { if (!exists()) mkdirs() }
            val outFile = File(appDir, "clip_${System.currentTimeMillis()}.mp4")
            withContext(Dispatchers.IO) {
                try {
                    fastMergeFiles(available, targetMs, outFile)
                    withContext(Dispatchers.Main) { Toast.makeText(this@ScreenRecorderService, "Saved!", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) { Log.e("ZZZGlip", "Merge failed", e) }
>>>>>>> main
            }
        }
    }

    private fun fastMergeFiles(files: List<File>, targetMs: Long, outputFile: File) {
        val durations = files.associateWith { getFileDuration(it) }
        var accumulated = 0L
        val toMerge = mutableListOf<File>()
        for (f in files.reversed()) {
            toMerge.add(0, f); accumulated += durations[f] ?: 0L
            if (accumulated >= targetMs) break
        }
        
        if (toMerge.isEmpty()) return
        
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        // Find format from any valid file in the list
        var videoFmt: MediaFormat? = null
        var audioFmt: MediaFormat? = null
        
        for (f in toMerge) {
            val ex = MediaExtractor(); ex.setDataSource(f.absolutePath)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (videoFmt == null && mime.startsWith("video/")) videoFmt = fmt
                if (audioFmt == null && mime.startsWith("audio/")) audioFmt = fmt
            }
            ex.release()
            if (videoFmt != null) break
        }
        
        if (videoFmt == null) return
        
        val vTIdx = muxer.addTrack(videoFmt)
        val aTIdx = if (audioFmt != null) muxer.addTrack(audioFmt) else -1
        
        muxer.start()
        val buffer = ByteBuffer.allocate(4 * 1024 * 1024); val info = MediaCodec.BufferInfo()
        var vOff = 0L; var aOff = 0L
        
        // Accurate clipping for the first file
        val startClipMs = if (accumulated > targetMs) accumulated - targetMs else 0L

        for (i in toMerge.indices) {
            val f = toMerge[i]
            val e = MediaExtractor().apply { setDataSource(f.absolutePath) }
            // Video
            val vi = (0 until e.trackCount).firstOrNull { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            if (vi != null) {
                e.selectTrack(vi)
                if (i == 0 && startClipMs > 0) e.seekTo(startClipMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                
                var lastPts = 0L; var firstPtsInFile = -1L
                while (true) {
                    val sampleSize = e.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    if (firstPtsInFile == -1L) firstPtsInFile = e.sampleTime
                    
                    val pts = vOff + (e.sampleTime - firstPtsInFile)
                    info.set(0, sampleSize, pts, e.sampleFlags)
                    muxer.writeSampleData(vTIdx, buffer, info); lastPts = pts; e.advance()
                }
                val vFmtFile = e.getTrackFormat(vi)
                val fps = if (vFmtFile.containsKey(MediaFormat.KEY_FRAME_RATE)) vFmtFile.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
                vOff = lastPts + (1000000L / fps)
            }
            // Audio
            val ai = (0 until e.trackCount).firstOrNull { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            if (ai != null && aTIdx != -1) {
                e.selectTrack(ai)
                // We don't strictly clip audio start to match video sync, just let it be continuous
                var lastPts = 0L; var firstPtsInFile = -1L
                while (true) {
                    val sampleSize = e.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    if (firstPtsInFile == -1L) firstPtsInFile = e.sampleTime
                    
                    val pts = aOff + (e.sampleTime - firstPtsInFile)
                    info.set(0, sampleSize, pts, e.sampleFlags)
                    muxer.writeSampleData(aTIdx, buffer, info); lastPts = pts; e.advance()
                }
                aOff = lastPts + 23219L
            }
            e.release()
        }
        muxer.stop(); muxer.release()
    }

    private fun getFileDuration(file: File): Long {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(file.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L }
        catch (e: Exception) { 0L } finally { r.release() }
    }

    private fun showFloatingButton() {
        handler.post {
            floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_button, null).apply { alpha = 0.6f }
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START; x = 200; y = 200
            }
            floatingView?.setOnTouchListener(object : View.OnTouchListener {
                private var iX = 0; private var iY = 0; private var itX = 0f; private var itY = 0f; private var mv = false
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { iX = params.x; iY = params.y; itX = e.rawX; itY = e.rawY; mv = false }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (e.rawX - itX).toInt(); val dy = (e.rawY - itY).toInt()
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                                mv = true
                                val m = DisplayMetrics(); @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
                                params.x = (iX + dx).coerceIn(0, m.widthPixels - v.width)
                                params.y = (iY + dy).coerceIn(0, m.heightPixels - v.height)
                                windowManager.updateViewLayout(floatingView, params)
                            }
                        }
                        MotionEvent.ACTION_UP -> if (!mv) saveLastMinutes()
                    }
                    return true
                }
            })
            windowManager.addView(floatingView, params)
        }
    }

    private fun stopRecording() {
        isRecording.set(false)
        virtualDisplay?.release(); virtualDisplay = null
        videoEncoder?.let { try { it.stop() } catch (e: Exception) {} ; it.release() }; videoEncoder = null
        audioEncoder?.let { try { it.stop() } catch (e: Exception) {} ; it.release() }; audioEncoder = null
        audioRecord?.let { try { it.stop() } catch (e: Exception) {} ; it.release() }; audioRecord = null
        try { muxer?.stop(); muxer?.release() } catch (e: Exception) {}
        muxer = null; mediaProjection?.stop(); mediaProjection = null
        segments.forEach { it.delete() }; segments.clear()
        floatingView?.let { windowManager.removeView(it) }; floatingView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "ZZZGlip Recorder", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, createNotification(currentBufferTime))
    }

    private fun createNotification(time: String): Notification {
        val stopPI = PendingIntent.getService(this, 0, Intent(this, ScreenRecorderService::class.java).apply { action = "STOP_SERVICE" }, PendingIntent.FLAG_IMMUTABLE)
        val listPI = PendingIntent.getActivity(this, 1, Intent(this, TimeSelectionActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("ZZZGlip Recording").setContentText("Buffer: $time").setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true)
            .addAction(0, "Stop", stopPI).addAction(0, "Time List", listPI).build()
    }

    override fun onDestroy() { super.onDestroy(); stopRecording(); serviceJob.cancel() }
}
