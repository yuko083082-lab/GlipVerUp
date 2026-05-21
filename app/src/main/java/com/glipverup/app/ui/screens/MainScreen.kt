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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isRecording: Boolean,
    targetAppName: String?,
    onToggleRecording: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSelectApp: () -> Unit
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

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Target App Display (Last Used App)
            Card(
                onClick = onSelectApp,
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .padding(bottom = 32.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Target Game:", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        text = targetAppName ?: "Tap to Select App",
                        color = Color.Cyan,
                        fontSize = 20.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    if (targetAppName != null) {
                        Text("(Last Used)", color = Color.DarkGray, fontSize = 10.sp)
                    }
                }
            }
            
            Button(
                onClick = onToggleRecording,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Gray else Color(0xFFE53935)
                ),
                modifier = Modifier.size(180.dp)
            ) {
                Text(
                    text = if (isRecording) "STOP" else "REC",
                    fontSize = 32.sp,
                    color = Color.White
                )
            }
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
