package com.example.androidtranscribe.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioUtils {

    const val SAMPLE_RATE = 16000
    const val CHUNK_SIZE_BYTES = 1024

    /**
     * Decode a compressed audio asset (e.g. m4a) to raw 16-bit PCM bytes.
     * Copies the asset to a temp file so MediaExtractor can read it.
     */
    fun decodeAssetToPcm(context: Context, assetFileName: String): ByteArray {
        val tempFile = File(context.cacheDir, assetFileName)
        context.assets.open(assetFileName).use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        }

        val extractor = MediaExtractor()
        extractor.setDataSource(tempFile.absolutePath)

        var audioTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrackIndex = i
                break
            }
        }
        require(audioTrackIndex >= 0) { "No audio track found in $assetFileName" }

        extractor.selectTrack(audioTrackIndex)
        val format = extractor.getTrackFormat(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false

        while (true) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                val buf = codec.getOutputBuffer(outIdx)!!
                val chunk = ByteArray(info.size)
                buf.get(chunk)
                buf.clear()
                out.write(chunk)
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }

        codec.stop()
        codec.release()
        extractor.release()
        tempFile.delete()

        return out.toByteArray()
    }

    fun wrapPcmInWav(pcmData: ByteArray): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val headerSize = 44

        val buffer = ByteBuffer.allocate(headerSize + dataSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray())

        // fmt sub-chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(SAMPLE_RATE)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())

        // data sub-chunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        buffer.put(pcmData)

        return buffer.array()
    }

    fun toS3Uri(url: String?): String? {
        if (url == null) return null
        val pathStyle = Regex("^https?://s3[.-][^/]+\\.amazonaws\\.com/([^/]+)/(.+)$")
        pathStyle.find(url)?.let { return "s3://${it.groupValues[1]}/${it.groupValues[2]}" }
        val virtualStyle = Regex("^https?://(.+)\\.s3[.-][^/]+\\.amazonaws\\.com/(.+)$")
        virtualStyle.find(url)?.let { return "s3://${it.groupValues[1]}/${it.groupValues[2]}" }
        return url
    }

    /** Parse "s3://bucket/key" into (bucket, key), or null if invalid. */
    fun parseS3Uri(s3Uri: String): Pair<String, String>? {
        val match = Regex("^s3://([^/]+)/(.+)$").find(s3Uri) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }
}
