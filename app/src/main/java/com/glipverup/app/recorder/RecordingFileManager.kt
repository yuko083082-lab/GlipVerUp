package com.glipverup.app.recorder

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

class RecordingFileManager(private val context: Context, private val cacheDir: File) {

    fun cleanLegacyFiles() {
        try {
            val files = cacheDir.listFiles()
            files?.forEach { if (it.name.startsWith("seg_")) it.delete() }
        } catch (e: Exception) {
            Log.e("RecordingFileManager", "Failed to clean legacy files", e)
        }
    }

    fun getFileDuration(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            r.release()
        }
    }

    fun fastMergeFiles(files: List<File>, targetMs: Long, pfd: ParcelFileDescriptor) {
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val durations = files.associateWith { getFileDuration(it) }
        var accumulated = 0L
        val toMerge = mutableListOf<File>()
        for (f in files.reversed()) {
            val d = durations[f] ?: 0L
            if (d == 0L) continue
            toMerge.add(0, f)
            accumulated += d
            if (accumulated >= targetMs) break
        }
        if (toMerge.isEmpty()) {
            muxer.release()
            return
        }

        var videoFmt: MediaFormat? = null
        var audioFmt: MediaFormat? = null
        for (f in toMerge) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(f.absolutePath)
                for (i in 0 until ex.trackCount) {
                    val fmt = ex.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (videoFmt == null && mime.startsWith("video/")) videoFmt = fmt
                    if (audioFmt == null && mime.startsWith("audio/")) audioFmt = fmt
                }
            } catch (_: Exception) {
            } finally {
                ex.release()
            }
            if (videoFmt != null && audioFmt != null) break
        }

        if (videoFmt == null) {
            muxer.release()
            return
        }
        val vTIdx = muxer.addTrack(videoFmt)
        val aTIdx = if (audioFmt != null) muxer.addTrack(audioFmt) else -1
        try {
            muxer.start()
        } catch (e: Exception) {
            muxer.release()
            return
        }

        val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var globalFileOffsetUs = 0L
        val startClipUs = if (accumulated > targetMs) (accumulated - targetMs) * 1000 else 0L
        var firstFramePtsInSession = -1L
        var samplesWritten = false

        for (i in toMerge.indices) {
            val f = toMerge[i]
            val e = MediaExtractor()
            try {
                e.setDataSource(f.absolutePath)
                val vi = (0 until e.trackCount).firstOrNull {
                    e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                val ai = (0 until e.trackCount).firstOrNull {
                    e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                var fileMinPtsUs = Long.MAX_VALUE
                if (vi != null) {
                    e.selectTrack(vi)
                    if (i == 0) e.seekTo(startClipUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    if (e.sampleTime != -1L) fileMinPtsUs = minOf(fileMinPtsUs, e.sampleTime)
                    e.unselectTrack(vi)
                }
                if (ai != null) {
                    e.selectTrack(ai)
                    if (i == 0) e.seekTo(startClipUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    if (e.sampleTime != -1L) fileMinPtsUs = minOf(fileMinPtsUs, e.sampleTime)
                    e.unselectTrack(ai)
                }
                if (fileMinPtsUs == Long.MAX_VALUE) fileMinPtsUs = 0L
                if (vi != null) e.selectTrack(vi)
                if (ai != null && aTIdx != -1) e.selectTrack(ai)
                if (i == 0) e.seekTo(startClipUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                else e.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                
                var maxPtsInFile = 0L
                while (true) {
                    val trackIdxInFile = e.sampleTrackIndex
                    if (trackIdxInFile < 0) break
                    val targetTIdx = if (trackIdxInFile == vi) vTIdx else if (trackIdxInFile == ai) aTIdx else -1
                    if (targetTIdx == -1) {
                        e.advance()
                        continue
                    }
                    val size = e.readSampleData(buffer, 0)
                    if (size < 0) break
                    val rawPts = e.sampleTime
                    val pts = globalFileOffsetUs + (rawPts - fileMinPtsUs)
                    if (firstFramePtsInSession == -1L) firstFramePtsInSession = pts
                    @Suppress("WrongConstant")
                    info.set(0, size, pts - firstFramePtsInSession, e.sampleFlags)
                    muxer.writeSampleData(targetTIdx, buffer, info)
                    maxPtsInFile = maxOf(maxPtsInFile, pts - firstFramePtsInSession)
                    samplesWritten = true
                    e.advance()
                }
                // 💡 SPEC: 1msのギャップ挿入
                globalFileOffsetUs = firstFramePtsInSession + maxPtsInFile + 1000L
            } catch (e: Exception) {
                Log.e("RecordingFileManager", "Error during merge loop", e)
            } finally {
                e.release()
            }
        }
        try {
            if (samplesWritten) muxer.stop()
        } catch (e: Exception) {
            Log.e("RecordingFileManager", "Error stopping muxer", e)
        } finally {
            muxer.release()
        }
    }

    fun createVideoFileDescriptor(fileName: String): ParcelFileDescriptor? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/ZZZGlip")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { context.contentResolver.openFileDescriptor(it, "w") }
        } else {
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val appDir = File(dcimDir, "ZZZGlip").apply { if (!exists()) mkdirs() }
            val outFile = File(appDir, fileName)
            ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
        }
    }
}
