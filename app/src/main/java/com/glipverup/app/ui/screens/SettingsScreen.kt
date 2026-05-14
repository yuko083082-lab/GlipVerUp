package com.glipverup.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glipverup.app.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val quality by viewModel.quality.collectAsState()
    val resolution by viewModel.resolution.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val bitrate by viewModel.bitrate.collectAsState()
    val bufferTime by viewModel.bufferTime.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF12121A),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0A0A0F)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Recording Buffer Time", color = Color.Gray, fontSize = 14.sp)
            Row(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
                val times = listOf("7 min", "5 min", "3 min", "1 min", "30 sec", "15 sec")
                Column {
                    times.chunked(3).forEach { rowItems ->
                        Row {
                            rowItems.forEach { item ->
                                val isRestricted = resolution == "4K" && item != "30 sec" && item != "15 sec"
                                FilterChip(
                                    selected = bufferTime == item,
                                    onClick = { if (!isRestricted) viewModel.updateBufferTime(item) },
                                    label = { 
                                        Text(
                                            text = item,
                                            color = if (isRestricted) Color.DarkGray else Color.Unspecified
                                        ) 
                                    },
                                    modifier = Modifier.padding(end = 8.dp),
                                    enabled = !isRestricted
                                )
                            }
                        }
                    }
                }
            }
            if (resolution == "4K") {
                Text(
                    "Note: 4K recording is limited to 30s buffer due to performance.",
                    color = Color(0xFFFFCC00),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 16.dp))

            Text("Quality", color = Color.Gray, fontSize = 14.sp)
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                listOf("Custom", "Low", "Medium", "High").forEach { item ->
                    FilterChip(
                        selected = quality == item,
                        onClick = { viewModel.updateQuality(item) },
                        label = { Text(item) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 16.dp))

            Text("Advance", color = Color.Gray, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))

            // Resolution
            Text("Resolution", color = Color.Gray, fontSize = 14.sp)
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                listOf("480p", "720p", "1080p", "1440p", "4K").forEach { item ->
                    FilterChip(
                        selected = resolution == item,
                        onClick = { viewModel.updateResolution(item) },
                        label = { Text(item) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // FPS
            Text("FPS", color = Color.Gray, fontSize = 14.sp)
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                listOf(30, 45, 60).forEach { item ->
                    FilterChip(
                        selected = fps == item,
                        onClick = { viewModel.updateFps(item) },
                        label = { Text(item.toString()) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bitrate
            Text("Bitrate (Mbps)", color = Color.Gray, fontSize = 14.sp)
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                listOf(4, 8, 10, 12).forEach { item ->
                    FilterChip(
                        selected = bitrate == item,
                        onClick = { viewModel.updateBitrate(item) },
                        label = { Text(item.toString()) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
    }
}
