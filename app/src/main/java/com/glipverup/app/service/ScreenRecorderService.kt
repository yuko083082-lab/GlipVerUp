package com.glipverup.app.service

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import com.glipverup.app.core.Constants
import com.glipverup.app.data.SettingsManager
import com.glipverup.app.detection.DetectionController
import com.glipverup.app.overlay.FloatingViewManager
import com.glipverup.app.recorder.AudioRecorder
import com.glipverup.app.recorder.MuxerManager
import com.glipverup.app.recorder.RecordingFileManager
import com.glipverup.app.recorder.VideoEncoder
import com.glipverup.app.util.WipeoutDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

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
    
    private var floatingViewManager: FloatingViewManager? = null
    private var muxerManager: MuxerManager? = null
    private lateinit var fileManager: RecordingFileManager
    private var detectionController: DetectionController? = null
    private var videoEncoderController: VideoEncoder = VideoEncoder()
    private var audioRecorderController: AudioRecorder? = null

    private var captureWidth = 1280
    private var captureHeight = 720

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val CHANNEL_ID = Constants.CHANNEL_ID
    private val NOTIFICATION_ID = Constants.NOTIFICATION_ID

    private val segmentDurationMs = Constants.Intervals.SEGMENT_DURATION_MS
    private var sessionStartTimeMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val isRecording = AtomicBoolean(false)
    private val isSaving = AtomicBoolean(false)
    private val isAutoSavePending = AtomicBoolean(false)
    private var currentBufferTime = "6 min"
    private var isWipeoutDetectionEnabled = false

    private var audioEncoder: MediaCodec? = null
    private var audioJob: Job? = null
    
    private var lastMuxerRotationTimeMs = 0L
    private var rotateMuxerNextLoop = false
    private var pendingRotationRestart = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("ZZZGlip", "Service.onCreate. Context tag: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this.attributionTag else "N/A"}")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        settingsManager = SettingsManager(this)
        
        fileManager = RecordingFileManager(this, cacheDir)
        muxerManager = MuxerManager(cacheDir)
        
        audioRecorderController = AudioRecorder(
            context = this,
            scope = serviceScope,
            audioEncoderProvider = { audioEncoder },
            muxerManagerProvider = { muxerManager }
        )
        
        floatingViewManager = FloatingViewManager(this, windowManager, settingsManager, serviceScope) {
            if (!isSaving.get()) {
                saveLastMinutes()
            }
        }

        detectionController = DetectionController(
            serviceScope,
            handler,
            onWipeoutDetected = { roi, result ->
                handleWipeoutDetected()
                saveEnhancedDiagnosticImage(roi, result, "hit")
            },
            onNearDetected = { roi, result ->
                saveEnhancedDiagnosticImage(roi, result, "near")
            }
        )
        
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.Actions.START_RECORDING -> handleStartRecording(intent)
            Constants.Actions.STOP_SERVICE -> {
                stopRecording()
                sendBroadcast(Intent(Constants.Actions.RECORDING_STOPPED).apply { setPackage(packageName) })
                stopSelf()
            }
            Constants.Actions.SAVE_BUFFER -> saveLastMinutes()
            Constants.Actions.CHANGE_TIME -> {
                val newTime = intent.getStringExtra(Constants.Extras.SELECTED_TIME)
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

            fileManager.cleanLegacyFiles()
            sessionStartTimeMs = System.currentTimeMillis()

            val resultCode = intent.getIntExtra(Constants.Extras.RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Constants.Extras.DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Constants.Extras.DATA)
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
                        sendBroadcast(Intent(Constants.Actions.RECORDING_STOPPED).apply { setPackage(packageName) })
                        stopSelf()
                    }
                }, handler)

                isRecording.set(true)
                audioRecorderController?.initialize(projection)
                floatingViewManager?.show()
                
                detectionController?.start(
                    virtualDisplayProvider = { virtualDisplay },
                    isRecordingProvider = { isRecording.get() },
                    isEnabledProvider = { isWipeoutDetectionEnabled },
                    isSavingProvider = { isSaving.get() },
                    rotateMuxerNextLoopProvider = { rotateMuxerNextLoop },
                    captureWidth = captureWidth,
                    captureHeight = captureHeight
                )
                
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

    private suspend fun prepareAndStartRecording(projection: MediaProjection) {
        val resStr = settingsManager.resolutionFlow.first()
        val fps = settingsManager.fpsFlow.first()
        val bitrate = settingsManager.bitrateFlow.first()

        val metrics = DisplayMetrics()
        withContext(Dispatchers.Main) {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }

        val lastOrientation = resources.configuration.orientation
        val isLandscape = lastOrientation == Configuration.ORIENTATION_LANDSCAPE
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

        val videoSurface = videoEncoderController.initialize(vW, vH, bitrate, fps)

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
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, videoSurface, null, null)
        } else {
            virtualDisplay?.resize(vW, vH, metrics.densityDpi)
            virtualDisplay?.surface = videoSurface
        }

        rotateMuxer()
        lastMuxerRotationTimeMs = System.currentTimeMillis()

        recordingLoop(lastOrientation)
    }

    private fun rotateMuxer() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        val bufferMinutes = when(currentBufferTime) {
            "6 min" -> 6
            "5 min" -> 5
            "3 min" -> 3
            "1 min" -> 1
            "30 sec" -> 1
            "15 sec" -> 1
            else -> 1
        }
        val maxSegments = kotlin.math.ceil(bufferMinutes * 1.2).toInt().coerceAtLeast(1)

        if (pendingRotationRestart) {
            muxerManager?.resetTimeline()
            pendingRotationRestart = false
        }

        muxerManager?.rotateMuxer(isLandscape, maxSegments) {
            rotateMuxerNextLoop = false
        }
    }

    private suspend fun recordingLoop(lastOrientation: Int) {
        val vBufferInfo = MediaCodec.BufferInfo()
        val aBufferInfo = MediaCodec.BufferInfo()

        muxerManager?.resetTimeline()
        lastMuxerRotationTimeMs = System.currentTimeMillis()
        pendingRotationRestart = false

        audioRecorderController?.startLoop(isRecording) { pendingRotationRestart }

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

            videoEncoderController.encoder?.let { encoder ->
                val outIdx = try { encoder.dequeueOutputBuffer(vBufferInfo, 1000) } catch (_: Exception) { -1 }
                if (outIdx >= 0) {
                    val buffer = encoder.getOutputBuffer(outIdx)
                    if (buffer != null) {
                        val manager = muxerManager
                        if (manager != null) {
                            val nowUs = System.nanoTime() / 1000
                            if (manager.videoTimelineOffsetUs == -1L) {
                                manager.videoTimelineOffsetUs = nowUs - vBufferInfo.presentationTimeUs
                            }
                            val absoluteVideoPts = vBufferInfo.presentationTimeUs + manager.videoTimelineOffsetUs

                            if (rotateMuxerNextLoop && (vBufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)) {
                                rotateMuxer()
                                lastMuxerRotationTimeMs = System.currentTimeMillis()
                                rotateMuxerNextLoop = false
                            }

                            if (manager.videoTrackIndex >= 0 && manager.muxerStarted) {
                                if (manager.segmentFirstPtsUs == -1L) {
                                    manager.segmentFirstPtsUs = absoluteVideoPts
                                }
                                try {
                                    if ((vBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                        val pts = absoluteVideoPts - manager.segmentFirstPtsUs
                                        if (pts >= 0) {
                                            vBufferInfo.presentationTimeUs = pts
                                            manager.writeSampleData(manager.videoTrackIndex, buffer, vBufferInfo)
                                        }
                                    }
                                } catch (e: Exception) { }
                            }
                        }
                    }
                    try { encoder.releaseOutputBuffer(outIdx, false) } catch (e: Exception) {}
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerManager?.addTrack(encoder.outputFormat, isVideo = true)
                }
            }

            audioEncoder?.let { encoder ->
                val outIdx = try { encoder.dequeueOutputBuffer(aBufferInfo, 1000) } catch (_: Exception) { -1 }
                if (outIdx >= 0) {
                    val buffer = encoder.getOutputBuffer(outIdx)
                    if (buffer != null) {
                        val manager = muxerManager
                        if (manager != null) {
                            val absoluteAudioPts = aBufferInfo.presentationTimeUs + manager.audioTimelineOffsetUs
                            if (manager.audioTrackIndex >= 0 && manager.muxerStarted) {
                                if (manager.segmentFirstPtsUs == -1L) {
                                    manager.segmentFirstPtsUs = absoluteAudioPts
                                }
                                try {
                                    if ((aBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                        val pts = absoluteAudioPts - manager.segmentFirstPtsUs
                                        if (pts >= 0) {
                                            aBufferInfo.presentationTimeUs = pts
                                            manager.writeSampleData(manager.audioTrackIndex, buffer, aBufferInfo)
                                        }
                                    }
                                } catch (e: Exception) { }
                            }
                        }
                    }
                    try { encoder.releaseOutputBuffer(outIdx, false) } catch (e: Exception) {}
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerManager?.addTrack(encoder.outputFormat, isVideo = false)
                }
            }

            delay(5)
        }
        audioRecorderController?.stop()
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
                floatingViewManager?.setSavingMode(true, isWipeout = isAutoSave)

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

                val landscapeCount = muxerManager?.segments?.count { it.name.endsWith("_L.mp4") } ?: 0
                val portraitCount = muxerManager?.segments?.count { it.name.endsWith("_P.mp4") } ?: 0
                val targetSuffix = if (landscapeCount >= portraitCount) "_L.mp4" else "_P.mp4"

                val available = muxerManager?.segments?.filter { it.exists() && it.name.endsWith(targetSuffix) && it.lastModified() >= (sessionStartTimeMs - 2000) }?.toList() ?: emptyList()

                if (available.isNotEmpty()) {
                    Log.d("ZZZGlip_Save", "Starting merge for ${available.size} files, targetMs=$targetMs")
                    withContext(Dispatchers.IO) {
                        val fileName = "clip_${System.currentTimeMillis()}.mp4"
                        val pfd = fileManager.createVideoFileDescriptor(fileName)

                        if (pfd != null) {
                            fileManager.fastMergeFiles(available, targetMs, pfd)
                            pfd.close()
                            Log.i("ZZZGlip_Save", "Successfully saved video: $fileName")
                        } else {
                            Log.e("ZZZGlip_Save", "Failed to open FileDescriptor for $fileName")
                        }
                    }
                } else {
                    Log.w("ZZZGlip_Save", "No matching segment files found (suffix=$targetSuffix, count=${muxerManager?.segments?.size ?: 0})")
                }
            } catch (e: Exception) {
                Log.e("ZZZGlip_Save", "CRITICAL ERROR during save: ${e.message}", e)
            } finally {
                isSaving.set(false)
                floatingViewManager?.setSavingMode(false)
            }
        }
    }

    private fun handleWipeoutDetected() {
        if (isAutoSavePending.getAndSet(true)) return
        floatingViewManager?.setSavingMode(true, isWipeout = true)
        
        serviceScope.launch(Dispatchers.Default) {
            try {
                delay(Constants.Intervals.AUTO_SAVE_WAIT_MS)
                saveBufferInternal(Constants.Intervals.CLIP_DURATION_MS, isAutoSave = true)
            } finally {
                isAutoSavePending.set(false)
            }
        }
    }

    private fun saveEnhancedDiagnosticImage(roiBitmap: Bitmap, result: WipeoutDetector.DetectionResult, prefix: String) {
        val binarized = result.binarizedBitmap ?: return
        val templates = WipeoutDetector.getAllTemplates()
        
        // 💡 4段構成 (1:元画像, 2:二値化, 3:テンプレート配置, 4:差分マップ)
        val combinedWidth = roiBitmap.width
        val combinedHeight = roiBitmap.height * 4 + 20 // 5px * 4隙間
        val combined = Bitmap.createBitmap(combinedWidth, combinedHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        val paint = Paint()

        // 1段目: ROI元画像
        canvas.drawBitmap(roiBitmap, 0f, 0f, paint)
        
        // 2段目: 二値化画像
        val binarizedScaled = Bitmap.createScaledBitmap(binarized, combinedWidth, roiBitmap.height, false)
        canvas.drawBitmap(binarizedScaled, 0f, roiBitmap.height.toFloat() + 5, paint)
        
        // 3段目: テンプレート配置画像 (文字ごとに色分け、重なりは加算)
        val templateLayer = Bitmap.createBitmap(binarized.width, binarized.height, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(
            0xFFFF0000.toInt(), // W: 赤
            0xFF00FF00.toInt(), // I: 緑
            0xFF0000FF.toInt(), // P: 青
            0xFFFFFF00.toInt(), // E: 黄
            0xFFFF00FF.toInt(), // O: 紫
            0xFF00FFFF.toInt(), // U: 水
            0xFFFFFFFF.toInt()  // T: 白
        )
        
        for (i in templates.indices) {
            val template = templates[i]
            val bestX = result.bestXOffsets.getOrNull(i) ?: continue
            val score = result.scores.getOrNull(i) ?: 0f
            
            // 💡 スコアが低い(0.5未満)テンプレートはノイズとして描画しない
            if (score < 0.5f) continue

            val charColor = colors[i % colors.size]
            
            for (ty in 0 until template.pixels.size) {
                for (tx in 0 until template.width) {
                    if (template.pixels[ty][tx] == 1) {
                        val targetX = bestX + tx
                        if (targetX >= 0 && targetX < templateLayer.width) {
                            val existingColor = templateLayer.getPixel(targetX, ty)
                            // 加算合成（ARGBを単純に足してクリップ）
                            val r = (android.graphics.Color.red(existingColor) + android.graphics.Color.red(charColor)).coerceAtMost(255)
                            val g = (android.graphics.Color.green(existingColor) + android.graphics.Color.green(charColor)).coerceAtMost(255)
                            val b = (android.graphics.Color.blue(existingColor) + android.graphics.Color.blue(charColor)).coerceAtMost(255)
                            templateLayer.setPixel(targetX, ty, android.graphics.Color.rgb(r, g, b))
                        }
                    }
                }
            }
        }
        val templateScaled = Bitmap.createScaledBitmap(templateLayer, combinedWidth, roiBitmap.height, false)
        canvas.drawBitmap(templateScaled, 0f, (roiBitmap.height * 2).toFloat() + 10, paint)

        // 4段目: 差分マップ
        val diffLayer = Bitmap.createBitmap(binarized.width, binarized.height, Bitmap.Config.ARGB_8888)
        val diffCanvas = Canvas(diffLayer)
        for (i in templates.indices) {
            val bestX = result.bestXOffsets.getOrNull(i) ?: continue
            val score = result.scores.getOrNull(i) ?: 0f
            
            // 💡 スコアが低いものは差分マップにも表示しない
            if (score < 0.5f) continue

            val diffMap = WipeoutDetector.generateDiffMap(binarized, templates[i], bestX)
            diffCanvas.drawBitmap(diffMap, bestX.toFloat(), 0f, paint)
            diffMap.recycle()
        }
        val diffScaled = Bitmap.createScaledBitmap(diffLayer, combinedWidth, roiBitmap.height, false)
        canvas.drawBitmap(diffScaled, 0f, (roiBitmap.height * 3).toFloat() + 15, paint)
        
        // 解析用の二値化Bitmapはここでリサイクル（メモリ解放）
        binarized.recycle()
        
        templateLayer.recycle()
        diffLayer.recycle()
        binarizedScaled.recycle()
        templateScaled.recycle()
        diffScaled.recycle()

        serviceScope.launch(Dispatchers.IO) {
            try {
                val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
                val timeStr = sdf.format(Calendar.getInstance().time)
                val detail = if (result.matchedChars.isNotEmpty()) result.matchedChars.joinToString("") else "NONE"
                val fileName = "${prefix}_${timeStr}_${detail}.png"
                
                val outputStream: java.io.OutputStream?
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/ZZZGlip/diag")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    outputStream = uri?.let { contentResolver.openOutputStream(it) }
                } else {
                    val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                    val diagDir = File(dcimDir, "ZZZGlip/diag").apply { if (!exists()) mkdirs() }
                    val file = File(diagDir, fileName)
                    outputStream = java.io.FileOutputStream(file)
                }

                outputStream?.use {
                    combined.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                combined.recycle()
                Log.d("ZZZGlip_Diag", "Saved enhanced diagnostic image: $fileName")
            } catch (e: Exception) {
                Log.e("ZZZGlip_Diag", "Failed to save diagnostic image", e)
            }
        }
    }

    private fun stopEncoderOnly() {
        virtualDisplay?.surface = null
        videoEncoderController.stop()
        try { audioEncoder?.stop() } catch (e: Exception) {} finally { audioEncoder?.release(); audioEncoder = null }
    }

    private fun stopRecording() { 
        val was = isRecording.getAndSet(false)
        if (!was && mediaProjection == null) return
        
        audioJob?.cancel()
        audioJob = null
        detectionController?.stop()
        
        stopEncoderOnly()
        virtualDisplay?.release()
        virtualDisplay = null
        
        audioRecorderController?.stop()
        
        muxerManager?.release()
        mediaProjection?.stop()
        mediaProjection = null
        
        muxerManager?.segments?.forEach { if (it.exists()) it.delete() }
        muxerManager?.segments?.clear()
        floatingViewManager?.hide()
    }

    private fun createNotificationChannel() { val chan = NotificationChannel(CHANNEL_ID, "ZZZGlip Recorder", NotificationManager.IMPORTANCE_LOW); val manager = getSystemService(NotificationManager::class.java); manager.createNotificationChannel(chan) }
    private fun updateNotification() { val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager; manager.notify(NOTIFICATION_ID, createNotification(currentBufferTime)) }
    private fun createNotification(time: String): Notification { val stopPI = PendingIntent.getService(this, 0, Intent(this, ScreenRecorderService::class.java).apply { action = Constants.Actions.STOP_SERVICE }, PendingIntent.FLAG_IMMUTABLE); val listPI = PendingIntent.getActivity(this, 1, Intent(this, TimeSelectionActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT); return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("ZZZGlip Recording").setContentText("Buffer: $time").setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).addAction(0, "Stop", stopPI).addAction(0, "Time List", listPI).build() }
    override fun onDestroy() { super.onDestroy(); stopRecording(); serviceJob.cancel() }
}
