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
    val isWipeoutEnabled by viewModel.isWipeoutEnabled.collectAsState()

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
            // WIPEOUT Mode Toggle
            Surface(
                onClick = { viewModel.updateWipeoutDetection(!isWipeoutEnabled) },
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("WIPEOUTモード", color = Color.White, fontSize = 16.sp)
                        Text("WIPEOUT時に自動で前後5秒を保存します", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(
                        checked = isWipeoutEnabled,
                        onCheckedChange = { viewModel.updateWipeoutDetection(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFE53935),
                            checkedTrackColor = Color(0xFFE53935).copy(alpha = 0.5f)
                        )
                    )
                }
            }

            Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))

            Text("Recording Buffer Time", color = Color.Gray, fontSize = 14.sp)
            Row(modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
                val times = listOf("6 min", "5 min", "3 min", "1 min", "30 sec", "15 sec")
                Column {
                    times.chunked(3).forEach { rowItems ->
                        Row {
                            rowItems.forEach { item ->
                                // すべての時間設定を解放
                                val isRestricted = false
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

            Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 16.dp))
            
            Text("Presets", color = Color.Gray, fontSize = 14.sp)
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                val presets = listOf("Low" to "低", "Medium" to "標準", "High" to "高", "Custom" to "自由")
                presets.forEach { (key, label) ->
                    FilterChip(
                        selected = quality == key,
                        onClick = { viewModel.updateQuality(key) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Advance (Custom)", color = Color.Gray, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))

            // Resolution - Removed 4K
            Text("Resolution", color = Color.Gray, fontSize = 14.sp)
            Row(modifier = Modifier.padding(vertical = 8.dp)) {
                listOf("480p", "720p", "1080p", "1440p").forEach { item ->
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

            Spacer(modifier = Modifier.height(24.dp))
            
            // 注意書きの追加
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A1A)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "⚠️ 注意事項",
                        color = Color.Yellow,
                        fontSize = 14.sp,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "1440pや高ビットレート設定で長時間録画を行うと、端末の負荷が高まり、録画が強制終了したり端末が発熱したりする場合があります。動作が不安定な場合は、設定を下げてお試しください。",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
