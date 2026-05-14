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
import kotlinx.coroutines.runBlocking

class TimeSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val settingsManager = SettingsManager(this)
        
        // Blockingly get resolution for simple filtering in this transient UI
        val currentResolution = runBlocking { settingsManager.resolutionFlow.first() }

        setContent {
            AppTheme {
                val allTimes = listOf("7 min", "5 min", "3 min", "1 min", "30 sec", "15 sec")
                val selectableTimes = if (currentResolution == "4K") {
                    listOf("30 sec", "15 sec")
                } else {
                    allTimes
                }
                
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Select Buffer Time") },
                    text = {
                        Column {
                            if (currentResolution == "4K") {
                                Text(
                                    "4K is limited to 30s max.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
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
