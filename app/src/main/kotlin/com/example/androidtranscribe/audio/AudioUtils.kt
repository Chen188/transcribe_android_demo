package com.example.androidtranscribe.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioUtils {

    const val SAMPLE_RATE = 16000
    const val CHUNK_SIZE_BYTES = 1024

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
}
