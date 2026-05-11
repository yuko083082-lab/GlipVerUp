package com.glipverup.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontSize
import androidx.compose.ui.unit.sp
import com.glipverup.app.service.ScreenRecorderService
import com.glipverup.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val isRecording = remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = {
                            if (!isRecording.value) {
                                // 録画開始
                                startRecording()
                                isRecording.value = true
                            } else {
                                // 停止
                                stopRecording()
                                isRecording.value = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE53935) // 赤色
                        ),
                        modifier = Modifier
                            .fillMaxSize(0.3f)
                    ) {
                        Text(
                            text = if (isRecording.value) "STOP" else "REC",
                            fontSize = 32.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }

    private fun startRecording() {
        val intent = Intent(this, ScreenRecorderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopRecording() {
        val intent = Intent(this, ScreenRecorderService::class.java)
        stopService(intent)
    }
}
