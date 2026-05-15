package com.glipverup.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.glipverup.app.BuildConfig

@Composable
fun MainScreen(
    isRecording: Boolean,
    onToggleRecording: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Settings Button
        IconButton(
            onClick = onNavigateToSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
        }

        Button(
            onClick = onToggleRecording,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFE53935) // 赤色
            ),
            modifier = Modifier
                .size(200.dp)
        ) {
            Text(
                text = if (isRecording) "STOP" else "REC",
                fontSize = 32.sp,
                color = Color.White
            )
        }

        // Ad Banner at the bottom
        if (!BuildConfig.DEBUG) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                factory = { context ->
                    AdView(context).apply {
                        setAdSize(AdSize.BANNER)
                        adUnitId = "ca-app-pub-3940256099942544/6300978111" // Test ID
                        loadAd(AdRequest.Builder().build())
                    }
                }
            )
        }
    }
}
