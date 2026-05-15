package com.glipverup.app.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.glipverup.app.data.SettingsManager
import com.glipverup.app.ui.theme.AppTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TimeSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            AppTheme {
                val allTimes = listOf("6 min", "5 min", "3 min", "1 min", "30 sec", "15 sec")
                val selectableTimes = allTimes
                
                val settingsManager = remember { SettingsManager(this@TimeSelectionActivity) }
                val wipeoutEnabled by settingsManager.wipeoutDetectionFlow.collectAsState(initial = false)
                val coroutineScope = rememberCoroutineScope()

                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Settings") },
                    text = {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        coroutineScope.launch {
                                            settingsManager.updateWipeoutDetection(!wipeoutEnabled)
                                        }
                                    }
                                    .padding(16.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = wipeoutEnabled,
                                    onCheckedChange = {
                                        coroutineScope.launch {
                                            settingsManager.updateWipeoutDetection(it)
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("WIPEOUT Auto Detection")
                            }

                            Divider()
                            
                            Text(
                                "Select Buffer Time",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(16.dp)
                            )

                            LazyColumn {
                                items(selectableTimes) { time ->
                                    Text(
                                        text = time,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val intent = Intent(this@TimeSelectionActivity, ScreenRecorderService::class.java).apply {
                                                    action = "CHANGE_TIME"
                                                    putExtra("selected_time", time)
                                                }
                                                startService(intent)
                                                finish()
                                            }
                                            .padding(16.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { finish() }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}
