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
import com.example.androidtranscribe.audio.AudioPreviewPlayer
import com.example.androidtranscribe.audio.AudioUtils
import com.example.androidtranscribe.aws.AwsClientFactory
import com.example.androidtranscribe.databinding.FragmentRealtimeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private val previewPlayer = AudioPreviewPlayer()

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* permission result handled at usage time */ }

    private val supportedLanguages = linkedMapOf(
        "English (US)" to LanguageCode.EnUs,
        "English (UK)" to LanguageCode.EnGb,
        "English (AU)" to LanguageCode.EnAu,
        "Spanish (US)" to LanguageCode.EsUs,
        "Spanish (ES)" to LanguageCode.EsEs,
        "French (FR)" to LanguageCode.FrFr,
        "French (CA)" to LanguageCode.FrCa,
        "German" to LanguageCode.DeDe,
        "Italian" to LanguageCode.ItIt,
        "Portuguese (BR)" to LanguageCode.PtBr,
        "Portuguese (PT)" to LanguageCode.PtPt,
        "Japanese" to LanguageCode.JaJp,
        "Korean" to LanguageCode.KoKr,
        "Chinese (Simplified)" to LanguageCode.ZhCn,
        "Chinese (Traditional)" to LanguageCode.ZhTw,
        "Hindi" to LanguageCode.HiIn,
        "Arabic (SA)" to LanguageCode.ArSa,
        "Russian" to LanguageCode.RuRu,
        "Dutch" to LanguageCode.NlNl,
        "Turkish" to LanguageCode.TrTr,
        "Thai" to LanguageCode.ThTh,
        "Indonesian" to LanguageCode.IdId,
        "Vietnamese" to LanguageCode.ViVn,
        "Hebrew" to LanguageCode.HeIl,
        "Malay" to LanguageCode.MsMy,
    )
    private val languageNames get() = supportedLanguages.keys.toList()
    private val languageCodes get() = supportedLanguages.values.toList()

    private val selectedCandidateIndices = mutableSetOf<Int>()

    companion object {
        private const val LANGUAGE_MODE_MANUAL = 0
        private const val LANGUAGE_MODE_AUTO_DETECT = 1
        private const val LANGUAGE_MODE_MULTI = 2
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRealtimeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Audio source
        val sources = arrayOf(
            getString(R.string.source_test_file),
            getString(R.string.source_test_file_multi),
            getString(R.string.source_microphone),
        )
        binding.spinnerSource.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, sources,
        )
        binding.spinnerSource.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long,
                ) {
                    val isMic = sources[pos] == getString(R.string.source_microphone)
                    binding.btnPreview.visibility = if (isMic) View.GONE else View.VISIBLE
                    if (isMic && previewPlayer.isPlaying) {
                        previewPlayer.stop()
                        binding.btnPreview.text = getString(R.string.btn_preview)
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

        // Language spinner (manual mode)
        binding.spinnerLanguage.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            languageNames,
        )

        // Language mode spinner
        val languageModes = arrayOf(
            getString(R.string.language_mode_manual),
            getString(R.string.language_mode_auto_detect),
            getString(R.string.language_mode_multi),
        )
        binding.spinnerLanguageMode.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            languageModes,
        )
        binding.spinnerLanguageMode.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long,
                ) {
                    binding.layoutManualLanguage.visibility =
                        if (pos == LANGUAGE_MODE_MANUAL) View.VISIBLE else View.GONE
                    binding.layoutCandidateLanguages.visibility =
                        if (pos == LANGUAGE_MODE_MULTI) View.VISIBLE else View.GONE
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

        binding.txtSelectLanguages.setOnClickListener { showLanguagePickerDialog() }

        binding.btnPreview.setOnClickListener { togglePreview() }
        binding.btnStartStreaming.setOnClickListener { toggleStreaming() }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showLanguagePickerDialog() {
        val names = languageNames.toTypedArray()
        val checked = BooleanArray(names.size) { it in selectedCandidateIndices }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.dialog_title_languages))
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked) selectedCandidateIndices.add(which)
                else selectedCandidateIndices.remove(which)
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> updateSelectedLanguagesLabel() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateSelectedLanguagesLabel() {
        if (selectedCandidateIndices.isEmpty()) {
            binding.txtSelectLanguages.text = getString(R.string.btn_select_languages)
            return
        }
        val names = selectedCandidateIndices.sorted().map { languageNames[it] }
        binding.txtSelectLanguages.text = names.joinToString(", ")
    }

    override fun onDestroyView() {
        previewPlayer.stop()
        stopStreaming()
        _binding = null
        super.onDestroyView()
    }

    private fun togglePreview() {
        if (previewPlayer.isPlaying) {
            previewPlayer.stop()
            binding.btnPreview.text = getString(R.string.btn_preview)
            return
        }
        val selected = binding.spinnerSource.selectedItem.toString()
        val fileName = if (selected == getString(R.string.source_test_file_multi)) {
            "test_audio_multi_speaker.wav"
        } else {
            "test_audio.pcm"
        }
        binding.btnPreview.text = getString(R.string.btn_stop_preview)
        viewLifecycleOwner.lifecycleScope.launch {
            previewPlayer.play(requireContext(), fileName) {
                _binding?.btnPreview?.text = getString(R.string.btn_preview)
            }
        }
    }

    private fun toggleStreaming() {
        if (!isStreaming) startStreaming() else stopStreaming()
    }

    private fun startStreaming() {
        val selected = binding.spinnerSource.selectedItem.toString()
        val useMic = selected == getString(R.string.source_microphone)
        val useMultiSpeakerFile = selected == getString(R.string.source_test_file_multi)

        if (useMic && ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            showToast(getString(R.string.msg_mic_permission_required))
            return
        }

        val languageMode = binding.spinnerLanguageMode.selectedItemPosition
        if (languageMode == LANGUAGE_MODE_MULTI && selectedCandidateIndices.size < 2) {
            showToast(getString(R.string.msg_select_at_least_two))
            return
        }

        previewPlayer.stop()
        binding.btnPreview.text = getString(R.string.btn_preview)
        val speakerIdEnabled = binding.switchSpeakerId.isChecked

        isStreaming = true
        binding.btnStartStreaming.text = getString(R.string.btn_stop_streaming)
        binding.spinnerSource.isEnabled = false
        binding.spinnerLanguageMode.isEnabled = false
        binding.spinnerLanguage.isEnabled = false
        binding.txtSelectLanguages.isEnabled = false
        binding.switchSpeakerId.isEnabled = false
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
            val audioFlow = when {
                useMic -> buildMicFlow()
                useMultiSpeakerFile -> buildFileFlow("test_audio_multi_speaker.wav", wavHeader = true)
                else -> buildFileFlow("test_audio.pcm", wavHeader = false)
            }
            val statusMsg = if (useMic) "Speak now." else "Streaming file..."
            streamTranscribe(audioFlow, statusMsg, speakerIdEnabled, languageMode)
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

    private fun buildFileFlow(fileName: String, wavHeader: Boolean): Flow<AudioStream> = flow {
        val inputStream = requireContext().assets.open(fileName)
        if (wavHeader) {
            inputStream.skip(44) // skip WAV header to get raw PCM
        }
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

    private suspend fun streamTranscribe(
        audioFlow: Flow<AudioStream>,
        statusMsg: String,
        speakerIdEnabled: Boolean,
        languageMode: Int,
    ) {
        val client = AwsClientFactory.transcribeStreaming(requireContext())
        try {
            val request = StartStreamTranscriptionRequest {
                mediaEncoding = MediaEncoding.Pcm
                mediaSampleRateHertz = AudioUtils.SAMPLE_RATE
                audioStream = audioFlow
                when (languageMode) {
                    LANGUAGE_MODE_AUTO_DETECT -> {
                        identifyLanguage = true
                        languageOptions = languageCodes
                            .map { it.value }.joinToString(",")
                    }
                    LANGUAGE_MODE_MULTI -> {
                        identifyMultipleLanguages = true
                        languageOptions = selectedCandidateIndices.sorted()
                            .joinToString(",") { languageCodes[it].value }
                    }
                    else -> {
                        languageCode = languageCodes[
                            binding.spinnerLanguage.selectedItemPosition
                        ]
                    }
                }
                if (speakerIdEnabled) {
                    showSpeakerLabel = true
                }
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
                            val alt = result.alternatives?.firstOrNull()
                            val text = alt?.transcript
                            if (!text.isNullOrEmpty()) {
                                val langTag = result.languageCode?.value?.let { " [$it]" } ?: ""
                                val display = if (!result.isPartial && speakerIdEnabled) {
                                    formatWithSpeakers(alt, langTag)
                                } else {
                                    val prefix = if (result.isPartial) "[partial]" else "[final]"
                                    "$prefix$langTag $text"
                                }
                                withContext(Dispatchers.Main) {
                                    _binding?.txtStreamingResult?.append("$display\n")
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

    private fun formatWithSpeakers(
        alt: aws.sdk.kotlin.services.transcribestreaming.model.Alternative,
        langTag: String,
    ): String {
        val items = alt.items
        if (items.isNullOrEmpty()) return "[final]$langTag ${alt.transcript}"

        val segments = mutableListOf<Pair<String, StringBuilder>>()
        var curSpeaker: String? = null
        for (item in items) {
            val speaker = item.speaker
            val content = item.content ?: continue
            if (speaker != curSpeaker && speaker != null) {
                segments.add("spk_$speaker" to StringBuilder(content))
                curSpeaker = speaker
            } else if (segments.isNotEmpty()) {
                val isPunctuation = item.type is aws.sdk.kotlin.services.transcribestreaming.model.ItemType.Punctuation
                if (isPunctuation) {
                    segments.last().second.append(content)
                } else {
                    segments.last().second.append(" ").append(content)
                }
            } else {
                segments.add("spk_?" to StringBuilder(content))
            }
        }

        if (segments.isEmpty()) return "[final]$langTag ${alt.transcript}"
        return segments.joinToString("\n") { (spk, text) -> "[final]$langTag [$spk] $text" }
    }

    private fun stopStreaming() {
        isStreaming = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        streamingJob?.cancel()
        _binding?.btnStartStreaming?.text = getString(R.string.btn_start_streaming)
        _binding?.spinnerSource?.isEnabled = true
        _binding?.spinnerLanguageMode?.isEnabled = true
        _binding?.spinnerLanguage?.isEnabled = true
        _binding?.txtSelectLanguages?.isEnabled = true
        _binding?.switchSpeakerId?.isEnabled = true
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
