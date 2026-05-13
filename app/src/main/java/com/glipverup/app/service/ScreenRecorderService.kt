package com.glipverup.app.service

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.glipverup.app.R
import com.glipverup.app.data.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.*

// Media3 imports
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer

class ScreenRecorderService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private lateinit var settingsManager: SettingsManager

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val CHANNEL_ID = "ZZZGlipChannel"
    private val NOTIFICATION_ID = 1001

    private var segmentDurationMs = 60000L // 1 minute segments
    private val segments = LinkedList<File>()
    private val handler = Handler(Looper.getMainLooper())
    private var isRecording = false
    private var currentBufferTime = "7 min"
    private var rotationRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("ZZZGlip", "Service: onCreate")
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        settingsManager = SettingsManager(this)
        
        serviceScope.launch {
            settingsManager.bufferTimeFlow.collectLatest { time ->
                currentBufferTime = time
                if (isRecording) {
                    updateNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d("ZZZGlip", "Service: onStartCommand Action=$action")

        when (action) {
            "START_RECORDING" -> {
                handleStartRecording(intent)
            }
            "STOP_SERVICE" -> {
                stopRecording()
                stopSelf()
                sendBroadcast(Intent("com.glipverup.app.RECORDING_STOPPED"))
                return START_NOT_STICKY
            }
            "CHANGE_TIME" -> {
                val newTime = intent.getStringExtra("selected_time")
                if (newTime != null) {
                    serviceScope.launch { settingsManager.updateBufferTime(newTime) }
                }
            }
            "SAVE_BUFFER" -> {
                saveLastMinutes()
            }
        }
        
        return START_STICKY
    }

    private fun handleStartRecording(intent: Intent) {
        if (isRecording) return

        val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }

        if (data != null) {
            try {
                startForeground(NOTIFICATION_ID, createNotification(currentBufferTime))
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d("ZZZGlip", "MediaProjection onStop called")
                        // On some devices, releasing virtual display triggers this.
                        // We only stop if we are actually supposed to be stopping.
                        if (isRecording) {
                            Log.w("ZZZGlip", "MediaProjection stopped unexpectedly!")
                            // You might want to try to restart it or just stop.
                            stopRecording()
                            stopSelf()
                            sendBroadcast(Intent("com.glipverup.app.RECORDING_STOPPED"))
                        }
                    }
                }, handler)

                showFloatingButton()
                isRecording = true
                recordNextSegment()
            } catch (e: Exception) {
                Log.e("ZZZGlip", "Service: Failed to start recording", e)
                stopSelf()
            }
        }
    }

    private fun recordNextSegment() {
        if (!isRecording) return

        serviceScope.launch {
            try {
                val resolution = settingsManager.resolutionFlow.first()
                val fps = settingsManager.fpsFlow.first()
                val bitrate = settingsManager.bitrateFlow.first()
                
                setupMediaRecorder(resolution, fps, bitrate)
                
                delay(200)
                mediaRecorder?.start()
                Log.d("ZZZGlip", "MediaRecorder started")

                scheduleNextRotation()
            } catch (e: Exception) {
                Log.e("ZZZGlip", "Service: Error in recordNextSegment", e)
                // Don't set isRecording = false immediately, try again if possible
            }
        }
    }

    private fun scheduleNextRotation() {
        rotationRunnable?.let { handler.removeCallbacks(it) }
        rotationRunnable = Runnable {
            if (isRecording) {
                Log.d("ZZZGlip", "Rotating segment...")
                stopCurrentSegment()
                recordNextSegment()
            }
        }
        handler.postDelayed(rotationRunnable!!, segmentDurationMs)
    }

    private fun setupMediaRecorder(resStr: String, fps: Int, bitrateMbps: Int) {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        
        val res = when(resStr) {
            "480p" -> Pair(854, 480)
            "1080p" -> Pair(1920, 1080)
            "1440p" -> Pair(2560, 1440)
            "4K" -> Pair(3840, 2160)
            else -> Pair(1280, 720)
        }

        val file = File(cacheDir, "seg_${System.currentTimeMillis()}.mp4")
        segments.add(file)

        // Keep buffer for max possible time (7 min)
        while (segments.size * segmentDurationMs > 8 * 60 * 1000) {
            val oldest = segments.removeFirst()
            if (oldest.exists()) oldest.delete()
        }

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(res.first, res.second)
            setVideoFrameRate(fps)
            setVideoEncodingBitRate(bitrateMbps * 1024 * 1024)
            setOutputFile(file.absolutePath)
            prepare()
        }

        val surface = mediaRecorder?.surface
        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ZZZGlipCapture",
                res.first, res.second, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
            Log.d("ZZZGlip", "Created VirtualDisplay")
        } else {
            virtualDisplay?.surface = surface
            Log.d("ZZZGlip", "Reused VirtualDisplay with new surface")
        }
    }

    private fun stopCurrentSegment() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            // DO NOT release virtualDisplay here to avoid projection stopping
            Log.d("ZZZGlip", "Stopped current segment")
        } catch (e: Exception) { 
            Log.e("ZZZGlip", "stopCurrentSegment failed", e)
        }
    }

    private fun saveLastMinutes() {
        Log.d("ZZZGlip", "saveLastMinutes triggered")
        rotationRunnable?.let { handler.removeCallbacks(it) }
        
        serviceScope.launch {
            // 1. Stop current segment to make it playable
            stopCurrentSegment()
            
            val durationMs = when(currentBufferTime) {
                "7 min" -> 7 * 60 * 1000L
                "5 min" -> 5 * 60 * 1000L
                "3 min" -> 3 * 60 * 1000L
                "1 min" -> 60 * 1000L
                "30 sec" -> 30 * 1000L
                "15 sec" -> 15 * 1000L
                else -> 15 * 1000L
            }

            // 2. Identify segments
            val availableSegments = segments.filter { it.exists() && it.length() > 0 }
            if (availableSegments.isEmpty()) {
                Toast.makeText(this@ScreenRecorderService, "No recording available yet", Toast.LENGTH_SHORT).show()
                recordNextSegment()
                return@launch
            }

            // Calculate how many segments we need
            var accumulatedMs = 0L
            val filesToMerge = mutableListOf<File>()
            for (f in availableSegments.reversed()) {
                filesToMerge.add(0, f)
                accumulatedMs += segmentDurationMs // Approximate
                if (accumulatedMs >= durationMs) break
            }

            Log.d("ZZZGlip", "Merging ${filesToMerge.size} files for $currentBufferTime")

            // 3. Perform Merge using Media3 Transformer
            try {
                val moviesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
                val appDir = File(moviesDir, "ZZZGlip")
                if (!appDir.exists()) appDir.mkdirs()

                val outFile = File(appDir, "clip_${System.currentTimeMillis()}.mp4")
                
                if (filesToMerge.size == 1) {
                    filesToMerge[0].copyTo(outFile, overwrite = true)
                    Toast.makeText(this@ScreenRecorderService, "Saved! Buffer: $currentBufferTime", Toast.LENGTH_LONG).show()
                } else {
                    mergeFiles(filesToMerge, outFile)
                }
            } catch (e: Exception) {
                Log.e("ZZZGlip", "Save failed", e)
                Toast.makeText(this@ScreenRecorderService, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }

            // 4. Resume recording
            recordNextSegment()
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun mergeFiles(files: List<File>, outputFile: File) {
        val transformer = Transformer.Builder(this).build()
        val mediaItems = files.map { MediaItem.fromUri(Uri.fromFile(it)) }
        val editedMediaItems = mediaItems.map { EditedMediaItem.Builder(it).build() }
        val sequence = EditedMediaItemSequence(editedMediaItems)
        val composition = Composition.Builder(listOf(sequence)).build()

        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                handler.post {
                    Toast.makeText(this@ScreenRecorderService, "Saved merged clip!", Toast.LENGTH_LONG).show()
                    Log.d("ZZZGlip", "Merge completed: ${outputFile.absolutePath}")
                }
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                handler.post {
                    Toast.makeText(this@ScreenRecorderService, "Merge failed: ${exportException.message}", Toast.LENGTH_SHORT).show()
                    Log.e("ZZZGlip", "Merge error", exportException)
                }
            }
        })

        transformer.start(composition, outputFile.absolutePath)
    }

    private fun stopRecording() {
        Log.d("ZZZGlip", "stopRecording called")
        isRecording = false
        rotationRunnable?.let { handler.removeCallbacks(it) }
        stopCurrentSegment()
        virtualDisplay?.release()
        virtualDisplay = null
        if (mediaProjection != null) {
            mediaProjection?.stop()
            mediaProjection = null
        }
        segments.forEach { if (it.exists()) it.delete() }
        segments.clear()
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {}
            floatingView = null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("ZZZGlip", "onTaskRemoved")
        // User said: don't disappear until task kill.
        // So keeping it here is correct.
        stopRecording()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "ZZZGlip Recorder", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(currentBufferTime))
    }

    private fun createNotification(currentTime: String): Notification {
        val stopIntent = Intent(this, ScreenRecorderService::class.java).apply { action = "STOP_SERVICE" }
        val stopPI = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val selectIntent = Intent(this, TimeSelectionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val selectPI = PendingIntent.getActivity(this, 1, selectIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ZZZGlip Recording")
            .setContentText("Buffer: $currentTime")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPI)
            .addAction(android.R.drawable.ic_menu_recent_history, "Time List", selectPI)
            .build()
    }

    private fun showFloatingButton() {
        if (floatingView != null) return
        handler.post {
            try {
                floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_button, null)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, 
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.START
                params.x = 200; params.y = 200

                floatingView?.setOnTouchListener(object : View.OnTouchListener {
                    private var initialX = 0; private var initialY = 0
                    private var initialTouchX = 0f; private var initialTouchY = 0f
                    private var isMoving = false
                    private val touchSlop = ViewConfiguration.get(this@ScreenRecorderService).scaledTouchSlop

                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                Log.d("ZZZGlip", "Floating: DOWN")
                                initialX = params.x; initialY = params.y
                                initialTouchX = event.rawX; initialTouchY = event.rawY
                                isMoving = false
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = (event.rawX - initialTouchX).toInt()
                                val dy = (event.rawY - initialTouchY).toInt()
                                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                                    isMoving = true
                                    params.x = initialX + dx
                                    params.y = initialY + dy
                                    windowManager.updateViewLayout(floatingView, params)
                                }
                                return true
                            }
                            MotionEvent.ACTION_UP -> {
                                Log.d("ZZZGlip", "Floating: UP, isMoving=$isMoving")
                                if (!isMoving) {
                                    saveLastMinutes()
                                }
                                return true
                            }
                        }
                        return false
                    }
                })
                windowManager.addView(floatingView, params)
            } catch (e: Exception) {
                Log.e("ZZZGlip", "Service: Error showing floating button", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        stopRecording()
    }
}
