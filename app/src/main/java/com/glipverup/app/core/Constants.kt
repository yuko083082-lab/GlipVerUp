package com.glipverup.app.core

object Constants {
    const val CHANNEL_ID = "ZZZGlipChannel"
    const val NOTIFICATION_ID = 1001
    
    object Actions {
        const val START_RECORDING = "START_RECORDING"
        const val STOP_SERVICE = "STOP_SERVICE"
        const val SAVE_BUFFER = "SAVE_BUFFER"
        const val CHANGE_TIME = "CHANGE_TIME"
        const val RECORDING_STOPPED = "com.glipverup.app.RECORDING_STOPPED"
    }
    
    object Extras {
        const val SELECTED_TIME = "selected_time"
        const val RESULT_CODE = "resultCode"
        const val DATA = "data"
    }
    
    object Intervals {
        const val SEGMENT_DURATION_MS = 60000L
        const val DETECTION_DELAY_MS = 100L
        const val AUTO_SAVE_WAIT_MS = 5000L
        const val CLIP_DURATION_MS = 10000L
    }
}
