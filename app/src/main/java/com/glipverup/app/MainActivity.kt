package com.glipverup.app

import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.glipverup.app.service.ScreenRecorderService
import com.glipverup.app.ui.screens.MainScreen
import com.glipverup.app.ui.screens.SettingsScreen
import com.glipverup.app.ui.theme.AppTheme
import com.glipverup.app.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private var mainViewModel: MainViewModel? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d("ZZZGlip", "MainActivity: Received STOP_RECORDING broadcast")
            mainViewModel?.updateRecordingState(false)
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startRecorderService(result.resultCode, result.data!!)
        } else {
            mainViewModel?.updateRecordingState(false)
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (checkOverlayPermission(request = false)) {
            startRecordingProcess()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, IntentFilter("com.glipverup.app.RECORDING_STOPPED"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, IntentFilter("com.glipverup.app.RECORDING_STOPPED"))
        }
        
        requestNotificationPermission()

        setContent {
            AppTheme {
                val navController = rememberNavController()
                val viewModel: MainViewModel = viewModel()
                mainViewModel = viewModel

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            isRecording = viewModel.isRecording,
                            onToggleRecording = {
                                if (!viewModel.isRecording) {
                                    startRecordingProcess()
                                } else {
                                    stopRecording()
                                    viewModel.updateRecordingState(false)
                                }
                            },
                            onNavigateToSettings = {
                                navController.navigate("settings")
                            }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    private fun startRecordingProcess() {
        if (checkOverlayPermission(request = true)) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 103)
            } else {
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }
    }

    private fun startRecorderService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenRecorderService::class.java).apply {
            action = "START_RECORDING"
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            mainViewModel?.updateRecordingState(true)
        } catch (e: Exception) {
            android.util.Log.e("ZZZGlip", "Failed to start service", e)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = "android.permission.POST_NOTIFICATIONS"
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(permission), 102)
            }
        }
    }

    private fun checkOverlayPermission(request: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                if (request) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayLauncher.launch(intent)
                }
                return false
            }
        }
        return true
    }

    private fun stopRecording() {
        val intent = Intent(this, ScreenRecorderService::class.java).apply {
            action = "STOP_SERVICE"
        }
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stopReceiver)
    }
}
