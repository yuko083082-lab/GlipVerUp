package com.glipverup.app.recorder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

class MuxerManager(private val cacheDir: File) {
    private var muxer: MediaMuxer? = null
    private val muxerLock = Any()
    
    var videoTrackIndex = -1
        private set
    var audioTrackIndex = -1
        private set
        
    var muxerStarted = false
        private set
    var samplesWrittenToCurrentMuxer = false
        private set
    var videoTrackAdded = false
        private set
    var audioTrackAdded = false
        private set

    val segments = ConcurrentLinkedDeque<File>()
    
    var persistedVideoFormat: MediaFormat? = null
    var persistedAudioFormat: MediaFormat? = null

    var videoTimelineOffsetUs = -1L
    var audioTimelineOffsetUs = -1L
    var segmentFirstPtsUs = -1L

    fun rotateMuxer(isLandscape: Boolean, maxSegments: Int, onComplete: () -> Unit) {
        synchronized(muxerLock) {
            try {
                if (muxerStarted && samplesWrittenToCurrentMuxer) {
                    try { muxer?.stop() } catch (e: Exception) { }
                }
                muxer?.release()
            } catch (e: Exception) { }

            val suffix = if (isLandscape) "L" else "P"
            val file = File(cacheDir, "seg_${System.currentTimeMillis()}_$suffix.mp4")
            segments.add(file)

            while (segments.size > maxSegments) {
                val oldest = segments.pollFirst()
                if (oldest != null && oldest.exists()) oldest.delete()
            }

            try {
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                videoTrackIndex = -1
                audioTrackIndex = -1
                videoTrackAdded = false
                audioTrackAdded = false
                muxerStarted = false
                samplesWrittenToCurrentMuxer = false
                segmentFirstPtsUs = -1L

                persistedVideoFormat?.let { 
                    videoTrackIndex = muxer?.addTrack(it) ?: -1 
                    if (videoTrackIndex >= 0) videoTrackAdded = true
                }
                persistedAudioFormat?.let { 
                    audioTrackIndex = muxer?.addTrack(it) ?: -1 
                    if (audioTrackIndex >= 0) audioTrackAdded = true
                }
                checkMuxerStart()
                onComplete()
            } catch (e: Exception) {
                Log.e("MuxerManager", "Failed to rotate muxer", e)
            }
        }
    }

    fun checkMuxerStart() {
        if (!muxerStarted && videoTrackAdded && audioTrackAdded) {
            try {
                muxer?.start()
                muxerStarted = true
            } catch (e: Exception) {
                Log.e("MuxerManager", "Failed to start muxer", e)
            }
        }
    }

    fun addTrack(format: MediaFormat, isVideo: Boolean): Int {
        synchronized(muxerLock) {
            val index = muxer?.addTrack(format) ?: -1
            if (isVideo) {
                videoTrackIndex = index
                videoTrackAdded = (index >= 0)
                persistedVideoFormat = format
            } else {
                audioTrackIndex = index
                audioTrackAdded = (index >= 0)
                persistedAudioFormat = format
            }
            checkMuxerStart()
            return index
        }
    }

    fun writeSampleData(trackIndex: Int, byteBuffer: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (muxerStarted && trackIndex >= 0) {
                try {
                    muxer?.writeSampleData(trackIndex, byteBuffer, bufferInfo)
                    samplesWrittenToCurrentMuxer = true
                } catch (e: Exception) {
                    // Log.e("MuxerManager", "Error writing sample data", e)
                }
            }
        }
    }

    fun release() {
        synchronized(muxerLock) {
            try {
                if (muxerStarted) {
                    if (samplesWrittenToCurrentMuxer) {
                        try {
                            muxer?.stop()
                        } catch (e: Exception) {
                            Log.w("MuxerManager", "Error stopping muxer during release", e)
                        }
                    }
                }
            } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
            muxer = null
            muxerStarted = false
            samplesWrittenToCurrentMuxer = false
            videoTrackIndex = -1
            audioTrackIndex = -1
            videoTrackAdded = false
            audioTrackAdded = false
        }
    }
    
    fun resetTimeline() {
        videoTimelineOffsetUs = -1L
        audioTimelineOffsetUs = -1L
        segmentFirstPtsUs = -1L
    }
}
