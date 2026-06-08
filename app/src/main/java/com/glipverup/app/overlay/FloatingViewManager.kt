package com.glipverup.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.glipverup.app.R
import com.glipverup.app.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FloatingViewManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope,
    private val onSaveClick: () -> Unit
) {
    private var floatingView: View? = null
    private val moveThreshold = 10 // 10pxしきい値

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        scope.launch {
            try {
                val savedX = settingsManager.floatingXFlow.first()
                val savedY = settingsManager.floatingYFlow.first()
                withContext(Dispatchers.Main) {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getRealMetrics(metrics)
                    
                    floatingView = LayoutInflater.from(context).inflate(R.layout.layout_floating_button, null).apply {
                        alpha = 0.6f
                    }
                    
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = savedX ?: (metrics.widthPixels - 200)
                        y = savedY ?: (metrics.heightPixels - 400)
                    }

                    floatingView?.setOnTouchListener(object : View.OnTouchListener {
                        private var iX = 0
                        private var iY = 0
                        private var itX = 0f
                        private var itY = 0f
                        private var mv = false

                        override fun onTouch(v: View, e: MotionEvent): Boolean {
                            when (e.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    iX = params.x
                                    iY = params.y
                                    itX = e.rawX
                                    itY = e.rawY
                                    mv = false
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    val dx = (e.rawX - itX).toInt()
                                    val dy = (e.rawY - itY).toInt()
                                    if (abs(dx) > moveThreshold || abs(dy) > moveThreshold) {
                                        mv = true
                                        val m = DisplayMetrics()
                                        @Suppress("DEPRECATION")
                                        windowManager.defaultDisplay.getRealMetrics(m)
                                        params.x = (iX + dx).coerceIn(0, (m.widthPixels - v.width).coerceAtLeast(0))
                                        params.y = (iY + dy).coerceIn(0, (m.heightPixels - v.height).coerceAtLeast(0))
                                        windowManager.updateViewLayout(floatingView, params)
                                    }
                                }
                                MotionEvent.ACTION_UP -> {
                                    if (mv) {
                                        scope.launch {
                                            settingsManager.saveFloatingPosition(params.x, params.y)
                                        }
                                    } else {
                                        onSaveClick()
                                    }
                                }
                            }
                            return true
                        }
                    })
                    windowManager.addView(floatingView, params)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun hide() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            floatingView = null
        }
    }

    fun setSavingMode(isSaving: Boolean, isWipeout: Boolean = false) {
        scope.launch(Dispatchers.Main) {
            floatingView?.let { view ->
                val progress = view.findViewById<View>(R.id.save_progress)
                val btnSave = view.findViewById<TextView>(R.id.btn_save)
                
                if (isSaving) {
                    progress.visibility = View.VISIBLE
                    view.alpha = 0.4f
                    if (isWipeout) {
                        btnSave.text = "WO!"
                    }
                } else {
                    progress.visibility = View.GONE
                    btnSave.text = "SAVE"
                    view.alpha = 0.6f
                }
            }
        }
    }
}
