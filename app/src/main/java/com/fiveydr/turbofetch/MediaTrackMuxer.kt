package com.fiveydr.turbofetch

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Muxes clear, already-downloaded audio and video tracks into one MP4 using Android's native
 * media APIs. DRM/encrypted samples are intentionally not handled by this utility.
 */
internal object MediaTrackMuxer {
    private const val DEFAULT_SAMPLE_BUFFER_BYTES = 1024 * 1024

    fun mux(videoFile: File, audioFile: File, outputFile: File) {
        val parent = outputFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create mux output directory")
        }
        if (outputFile.exists() && !outputFile.delete()) {
            throw IOException("Cannot replace existing mux output")
        }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoTrackIndex = findTrack(videoExtractor, "video/")
            val audioTrackIndex = findTrack(audioExtractor, "audio/")
            if (videoTrackIndex < 0) throw IOException("The downloaded video track has no readable video stream")
            if (audioTrackIndex < 0) throw IOException("The downloaded audio track has no readable audio stream")

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))
            val outputAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackIndex))
            muxer.start()
            started = true

            copySelectedTrack(videoExtractor, videoTrackIndex, muxer, outputVideoTrack)
            copySelectedTrack(audioExtractor, audioTrackIndex, muxer, outputAudioTrack)
            muxer.stop()
            started = false

            if (!outputFile.isFile || outputFile.length() <= 0L) {
                throw IOException("The muxer produced an empty output file")
            }
        } catch (error: Exception) {
            runCatching { outputFile.delete() }
            if (error is IOException) throw error
            throw IOException("Could not combine the downloaded audio and video tracks: " +
                (error.message ?: error.javaClass.simpleName), error)
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = runCatching {
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            }.getOrNull()
            if (mime?.startsWith(prefix, ignoreCase = true) == true) return index
        }
        return -1
    }

    private fun copySelectedTrack(
        extractor: MediaExtractor,
        inputTrack: Int,
        muxer: MediaMuxer,
        outputTrack: Int
    ) {
        extractor.selectTrack(inputTrack)
        val format = extractor.getTrackFormat(inputTrack)
        val configuredSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
        } else 0
        var buffer = ByteBuffer.allocateDirect(maxOf(DEFAULT_SAMPLE_BUFFER_BYTES, configuredSize))
        val info = MediaCodec.BufferInfo()

        while (true) {
            val sampleSize = extractor.sampleSize
            if (sampleSize < 0L) break
            if (sampleSize > Int.MAX_VALUE.toLong()) throw IOException("A media sample is too large to mux")
            val needed = sampleSize.toInt()
            if (buffer.capacity() < needed) {
                buffer = ByteBuffer.allocateDirect(needed + 64 * 1024)
            }
            buffer.clear()
            val read = extractor.readSampleData(buffer, 0)
            if (read < 0) break

            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else 0
            info.set(0, read, extractor.sampleTime.coerceAtLeast(0L), flags)
            buffer.position(0)
            buffer.limit(read)
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
        extractor.unselectTrack(inputTrack)
    }
}
