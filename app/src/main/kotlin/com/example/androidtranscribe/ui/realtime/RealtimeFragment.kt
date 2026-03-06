package com.example.androidtranscribe.ui.realtime

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import aws.sdk.kotlin.services.transcribestreaming.model.AudioEvent
import aws.sdk.kotlin.services.transcribestreaming.model.AudioStream
import aws.sdk.kotlin.services.transcribestreaming.model.LanguageCode
import aws.sdk.kotlin.services.transcribestreaming.model.MediaEncoding
import aws.sdk.kotlin.services.transcribestreaming.model.StartStreamTranscriptionRequest
import aws.sdk.kotlin.services.transcribestreaming.model.TranscriptResultStream
import com.example.androidtranscribe.R
import com.example.androidtranscribe.audio.AudioUtils
import com.example.androidtranscribe.aws.AwsClientFactory
import com.example.androidtranscribe.databinding.FragmentRealtimeBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RealtimeFragment : Fragment() {

    private var _binding: FragmentRealtimeBinding? = null
    private val binding get() = _binding!!

    private var audioRecord: AudioRecord? = null
    private var isStreaming = false
    private var streamingJob: Job? = null

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* permission result handled at usage time */ }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRealtimeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sources = arrayOf(
            getString(R.string.source_test_file),
            getString(R.string.source_microphone),
        )
        binding.spinnerSource.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, sources,
        )

        binding.btnStartStreaming.setOnClickListener { toggleStreaming() }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroyView() {
        stopStreaming()
        _binding = null
        super.onDestroyView()
    }

    private fun toggleStreaming() {
        if (!isStreaming) startStreaming() else stopStreaming()
    }

    private fun startStreaming() {
        val useMic = binding.spinnerSource.selectedItem.toString() ==
            getString(R.string.source_microphone)

        if (useMic && ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            showToast(getString(R.string.msg_mic_permission_required))
            return
        }

        isStreaming = true
        binding.btnStartStreaming.text = getString(R.string.btn_stop_streaming)
        binding.spinnerSource.isEnabled = false
        @Suppress("SetTextI18n")
        binding.txtStreamingResult.text = ""

        if (useMic) {
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(
                AudioUtils.SAMPLE_RATE, channelConfig, encoding,
            )
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioUtils.SAMPLE_RATE, channelConfig, encoding, bufferSize,
            )
            audioRecord?.startRecording()
        }

        val handler = CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                activity?.runOnUiThread {
                    stopStreaming()
                    showToast(getString(R.string.msg_streaming_error, e.message))
                }
            }
        }

        streamingJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO + handler) {
            val audioFlow = if (useMic) buildMicFlow() else buildFileFlow()
            val statusMsg = if (useMic) "Speak now." else "Streaming file..."
            streamTranscribe(audioFlow, statusMsg)
        }
    }

    private fun buildMicFlow(): Flow<AudioStream> = flow {
        val buffer = ByteArray(AudioUtils.CHUNK_SIZE_BYTES)
        while (isStreaming) {
            val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (bytesRead > 0) {
                emit(AudioStream.AudioEvent(AudioEvent { audioChunk = buffer.copyOf(bytesRead) }))
            }
        }
    }

    private fun buildFileFlow(): Flow<AudioStream> = flow {
        val inputStream = requireContext().assets.open("test_audio.pcm")
        val buffer = ByteArray(AudioUtils.CHUNK_SIZE_BYTES)
        var totalSent = 0
        var bytesRead = inputStream.read(buffer)
        while (bytesRead > 0 && isStreaming) {
            emit(AudioStream.AudioEvent(AudioEvent { audioChunk = buffer.copyOf(bytesRead) }))
            totalSent += bytesRead
            delay(30)
            bytesRead = inputStream.read(buffer)
        }
        inputStream.close()
        withContext(Dispatchers.Main) {
            _binding?.txtStreamingResult?.append(
                getString(R.string.msg_file_sent, totalSent) + "\n",
            )
        }
    }

    private suspend fun streamTranscribe(audioFlow: Flow<AudioStream>, statusMsg: String) {
        val client = AwsClientFactory.transcribeStreaming(requireContext())
        try {
            val request = StartStreamTranscriptionRequest {
                languageCode = LanguageCode.EnUs
                mediaEncoding = MediaEncoding.Pcm
                mediaSampleRateHertz = AudioUtils.SAMPLE_RATE
                audioStream = audioFlow
            }

            withContext(Dispatchers.Main) {
                _binding?.txtStreamingResult?.append(
                    getString(R.string.msg_connecting) + "\n",
                )
            }

            client.startStreamTranscription(request) { resp ->
                withContext(Dispatchers.Main) {
                    _binding?.txtStreamingResult?.append("[Connected. $statusMsg]\n")
                }

                val resultStream = resp.transcriptResultStream
                if (resultStream == null) {
                    withContext(Dispatchers.Main) {
                        _binding?.txtStreamingResult?.append(
                            "[Error: transcriptResultStream is null]\n",
                        )
                    }
                    return@startStreamTranscription
                }

                resultStream.collect { event ->
                    if (event is TranscriptResultStream.TranscriptEvent) {
                        event.value.transcript?.results?.forEach { result ->
                            val text = result.alternatives?.firstOrNull()?.transcript
                            if (!text.isNullOrEmpty()) {
                                val prefix = if (result.isPartial) "[partial] " else "[final] "
                                withContext(Dispatchers.Main) {
                                    _binding?.txtStreamingResult?.append("$prefix$text\n")
                                    _binding?.scrollStreaming?.fullScroll(View.FOCUS_DOWN)
                                }
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                _binding?.txtStreamingResult?.append(getString(R.string.msg_done) + "\n")
            }
        } catch (e: CancellationException) {
            // Normal cancellation when user stops streaming.
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _binding?.txtStreamingResult?.append("\n[Error: ${e.message}]\n")
                showToast(getString(R.string.msg_streaming_error, e.message))
            }
        } finally {
            withContext(Dispatchers.Main) { stopStreaming() }
            client.close()
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        streamingJob?.cancel()
        _binding?.btnStartStreaming?.text = getString(R.string.btn_start_streaming)
        _binding?.spinnerSource?.isEnabled = true
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
