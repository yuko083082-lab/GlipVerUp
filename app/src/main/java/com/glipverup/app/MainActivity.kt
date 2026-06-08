package com.glipverup.app

import android.app.Activity
import android.app.ActivityManager
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.glipverup.app.BuildConfig
import com.glipverup.app.ads.AdManager
import com.glipverup.app.core.Constants
import com.glipverup.app.permissions.PermissionHandler
import com.glipverup.app.service.ScreenRecorderService
import com.glipverup.app.ui.screens.MainScreen
import com.glipverup.app.ui.screens.SettingsScreen
import com.glipverup.app.ui.theme.AppTheme
import com.glipverup.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            super.attachBaseContext(newBase.createAttributionContext("glip_recorder"))
        } else {
            super.attachBaseContext(newBase)
        }
    }

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
        if (PermissionHandler.hasOverlayPermission(this)) {
            startRecordingProcess()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!BuildConfig.DEBUG) {
            AdManager.initialize(this)
        }

        val attributionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createAttributionContext("glip_recorder")
        } else { this }

        projectionManager = attributionContext.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val filter = IntentFilter(Constants.Actions.RECORDING_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, filter)
        }
        
        requestNotificationPermission()
        validateSettings()
        checkServiceRunning()

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

    override fun onResume() {
        super.onResume()
        checkServiceRunning()
        if (intent?.getBooleanExtra("SHOW_AD", false) == true) {
            intent.removeExtra("SHOW_AD")
            AdManager.showAdIfAvailable(this)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("SHOW_AD", false) == true) {
            intent.removeExtra("SHOW_AD")
            AdManager.showAdIfAvailable(this)
        }
    }

    private fun checkServiceRunning() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val services = manager.getRunningServices(Int.MAX_VALUE)
        if (services != null) {
            for (service in services) {
                if (ScreenRecorderService::class.java.name == service.service.className) {
                    mainViewModel?.updateRecordingState(true)
                    return
                }
            }
        }
        mainViewModel?.updateRecordingState(false)
    }

    private fun startRecordingProcess() {
        if (!PermissionHandler.hasOverlayPermission(this)) {
            overlayLauncher.launch(PermissionHandler.getOverlayPermissionIntent(this))
            return
        }
        if (!PermissionHandler.hasAudioPermission(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 103)
        } else {
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                val config = android.media.projection.MediaProjectionConfig.createConfigForUserChoice()
                projectionManager.createScreenCaptureIntent(config)
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            screenCaptureLauncher.launch(intent)
        }
    }

    private fun startRecorderService(resultCode: Int, data: Intent) {
        val attributionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createAttributionContext("glip_recorder")
        } else { this }

        val intent = Intent(attributionContext, ScreenRecorderService::class.java).apply {
            action = Constants.Actions.START_RECORDING
            putExtra(Constants.Extras.RESULT_CODE, resultCode)
            putExtra(Constants.Extras.DATA, data)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                attributionContext.startForegroundService(intent)
            } else {
                attributionContext.startService(intent)
            }
            mainViewModel?.updateRecordingState(true)
        } catch (e: Exception) {
            android.util.Log.e("ZZZGlip", "Failed to start service", e)
        }
    }

    private fun requestNotificationPermission() {
        if (!PermissionHandler.hasNotificationPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }
    }

    private fun validateSettings() {
        val settingsManager = com.glipverup.app.data.SettingsManager(this)
        val validTimes = listOf("6 min", "5 min", "3 min", "1 min", "30 sec", "15 sec")
        val validResolutions = listOf("480p", "720p", "1080p", "1440p")
        
        lifecycleScope.launch {
            val currentTime = settingsManager.bufferTimeFlow.first()
            val currentRes = settingsManager.resolutionFlow.first()
            
            if (currentTime !in validTimes) {
                settingsManager.updateBufferTime("5 min")
            }
            if (currentRes !in validResolutions) {
                settingsManager.updateResolution("1080p")
            }
        }
    }

    private fun stopRecording() {
        val intent = Intent(this, ScreenRecorderService::class.java).apply {
            action = Constants.Actions.STOP_SERVICE
        }
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stopReceiver)
    }
}
