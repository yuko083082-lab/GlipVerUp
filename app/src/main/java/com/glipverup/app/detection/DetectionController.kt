package com.glipverup.app.detection

import android.graphics.Bitmap
import android.view.PixelCopy
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.util.Log
import com.glipverup.app.core.Constants
import com.glipverup.app.util.WipeoutDetector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.LinkedList
import java.util.concurrent.atomic.AtomicBoolean

class DetectionController(
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val onWipeoutDetected: (Bitmap, WipeoutDetector.DetectionResult) -> Unit,
    private val onNearDetected: (Bitmap, WipeoutDetector.DetectionResult) -> Unit
) {
    private val isDetectionProcessing = AtomicBoolean(false)
    private val scoreHistory = LinkedList<List<Float>>()
    private var detectionJob: Job? = null
    
    private var reusableFullFrameBitmap: Bitmap? = null

    fun start(
        virtualDisplayProvider: () -> VirtualDisplay?,
        isRecordingProvider: () -> Boolean,
        isEnabledProvider: () -> Boolean,
        isSavingProvider: () -> Boolean,
        rotateMuxerNextLoopProvider: () -> Boolean,
        captureWidth: Int,
        captureHeight: Int
    ) {
        stop()
        detectionJob = scope.launch(Dispatchers.Default) {
            // 💡 SPEC: 録画開始時に一度だけ検知ループを開始 (SecurityException対策の遅延)
            delay(1000)
            
            while (isRecordingProvider() && isEnabledProvider()) {
                delay(Constants.Intervals.DETECTION_DELAY_MS)
                
                if (isSavingProvider() || rotateMuxerNextLoopProvider() || isDetectionProcessing.get()) continue

                val vd = virtualDisplayProvider() ?: continue
                val surface = vd.surface ?: continue
                if (!surface.isValid) continue

                try {
                    if (reusableFullFrameBitmap == null || reusableFullFrameBitmap!!.width != captureWidth || reusableFullFrameBitmap!!.height != captureHeight) {
                        reusableFullFrameBitmap?.recycle()
                        reusableFullFrameBitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
                    }
                    val fullBitmap = reusableFullFrameBitmap!!

                    val completable = CompletableDeferred<Int>()
                    handler.post {
                        try {
                            if (surface.isValid) {
                                PixelCopy.request(surface, null, fullBitmap, { result: Int -> completable.complete(result) }, handler)
                            } else {
                                completable.complete(PixelCopy.ERROR_UNKNOWN)
                            }
                        } catch (e: Exception) {
                            completable.completeExceptionally(e)
                        }
                    }

                    if (try { completable.await() } catch (e: Exception) { -1 } == PixelCopy.SUCCESS) {
                        processFrame(fullBitmap, captureWidth, captureHeight)
                    }
                } catch (e: Exception) {
                    Log.e("DetectionController", "Detection loop error", e)
                }
            }
        }
    }

    private fun processFrame(fullBitmap: Bitmap, captureWidth: Int, captureHeight: Int) {
        isDetectionProcessing.set(true)
        scope.launch(Dispatchers.Default) {
            var roiSnapshot: Bitmap? = null
            try {
                val left = (captureWidth * WipeoutDetector.ROI_LEFT_PCT).toInt()
                val top = (captureHeight * WipeoutDetector.ROI_TOP_PCT).toInt()
                val right = (captureWidth * WipeoutDetector.ROI_RIGHT_PCT).toInt()
                val bottom = (captureHeight * WipeoutDetector.ROI_BOTTOM_PCT).toInt()
                
                val targetW = (right - left).coerceAtLeast(1)
                val targetH = (bottom - top).coerceAtLeast(1)

                roiSnapshot = Bitmap.createBitmap(fullBitmap, left, top, targetW, targetH)
                val result = WipeoutDetector.detectWipeout(roiSnapshot)
                
                // 💡 SPEC: FIFOバッファにスコアを蓄積 (直近20フレーム)
                synchronized(scoreHistory) {
                    scoreHistory.addLast(result.scores)
                    if (scoreHistory.size > 20) {
                        scoreHistory.removeFirst()
                    }
                }

                // 💡 SPEC: 履歴の中から各文字ごとの最大スコアを算出
                val maxScores = List(7) { charIdx ->
                    synchronized(scoreHistory) {
                        scoreHistory.maxOfOrNull { it[charIdx] } ?: 0f
                    }
                }

                // 💡 SPEC: 最大スコアを使って最終判定 (Triple Check)
                if (WipeoutDetector.evaluateTripleCheck(maxScores)) {
                    val templates = WipeoutDetector.getAllTemplates()
                    val detail = templates.indices.joinToString(", ") { i ->
                        val name = templates[i].charName
                        val score = maxScores[i]
                        "$name: ${String.format(java.util.Locale.US, "%.2f", score)}"
                    }
                    Log.i("DetectionController", "!!! WIPEOUT DETECTED !!! Details: [$detail]")
                    
                    onWipeoutDetected(roiSnapshot, result)
                    
                    // HITした場合は履歴をリセットして重複検知を抑制
                    synchronized(scoreHistory) { scoreHistory.clear() }
                } else {
                    // 💡 SPEC: NEAR保存
                    val countOver75 = maxScores.count { it >= 0.75f }
                    val countOver70 = maxScores.count { it >= 0.70f }
                    if (countOver75 >= 1 || countOver70 >= 2) {
                        onNearDetected(roiSnapshot, result)
                    } else {
                        // 診断画像保存に回さない場合はここでリサイクル
                        result.binarizedBitmap?.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.e("DetectionController", "Analysis error", e)
            } finally {
                roiSnapshot?.recycle()
                isDetectionProcessing.set(false)
            }
        }
    }

    fun stop() {
        detectionJob?.cancel()
        detectionJob = null
        synchronized(scoreHistory) { scoreHistory.clear() }
        reusableFullFrameBitmap?.recycle()
        reusableFullFrameBitmap = null
    }
}
