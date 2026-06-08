package com.glipverup.app.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

class VideoEncoder {
    var encoder: MediaCodec? = null
        private set
    var surface: Surface? = null
        private set

    fun initialize(width: Int, height: Int, bitrateMbps: Int, fps: Int): Surface? {
        stop()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1024 * 1024)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder?.createInputSurface()
            encoder?.start()
            return surface
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun stop() {
        try {
            encoder?.stop()
        } catch (e: Exception) {}
        try {
            encoder?.release()
        } catch (e: Exception) {}
        encoder = null
        surface?.release()
        surface = null
    }
}
