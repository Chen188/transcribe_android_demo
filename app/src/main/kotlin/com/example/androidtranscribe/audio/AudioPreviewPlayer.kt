package com.example.androidtranscribe.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays raw PCM audio from asset files (supports both .pcm and .wav with header skip).
 */
class AudioPreviewPlayer {

    private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null

    val isPlaying: Boolean get() = playJob?.isActive == true

    suspend fun play(context: Context, fileName: String, onDone: () -> Unit) = coroutineScope {
        stop()
        val isWav = fileName.endsWith(".wav", ignoreCase = true)

        val bufSize = AudioTrack.getMinBufferSize(
            AudioUtils.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioUtils.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()

        playJob = launch(Dispatchers.IO) {
            val stream = context.assets.open(fileName)
            try {
                if (isWav) stream.skip(44)
                val buffer = ByteArray(4096)
                var read = stream.read(buffer)
                while (read > 0 && isActive) {
                    track.write(buffer, 0, read)
                    read = stream.read(buffer)
                }
            } finally {
                stream.close()
                track.stop()
                track.release()
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        try {
            audioTrack?.stop()
        } catch (_: IllegalStateException) { }
        try {
            audioTrack?.release()
        } catch (_: IllegalStateException) { }
        audioTrack = null
    }
}
