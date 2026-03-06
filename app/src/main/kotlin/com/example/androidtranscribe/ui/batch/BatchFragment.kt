package com.example.androidtranscribe.ui.batch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.transcribe.model.GetTranscriptionJobRequest
import aws.sdk.kotlin.services.transcribe.model.LanguageCode
import aws.sdk.kotlin.services.transcribe.model.ListTranscriptionJobsRequest
import aws.sdk.kotlin.services.transcribe.model.Media
import aws.sdk.kotlin.services.transcribe.model.MediaFormat
import aws.sdk.kotlin.services.transcribe.model.Settings
import aws.sdk.kotlin.services.transcribe.model.StartTranscriptionJobRequest
import aws.sdk.kotlin.services.transcribe.model.TranscriptionJobStatus
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.decodeToString
import com.example.androidtranscribe.R
import com.example.androidtranscribe.audio.AudioPreviewPlayer
import com.example.androidtranscribe.audio.AudioUtils
import com.example.androidtranscribe.aws.AwsClientFactory
import com.example.androidtranscribe.databinding.FragmentBatchBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class BatchFragment : Fragment() {

    private var _binding: FragmentBatchBinding? = null
    private val binding get() = _binding!!

    private var uploadedS3Uri: String? = null
    private val previewPlayer = AudioPreviewPlayer()

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
        _binding = FragmentBatchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Test file spinner
        val testFiles = arrayOf(
            getString(R.string.test_file_single),
            getString(R.string.test_file_multi),
        )
        binding.spinnerTestFile.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, testFiles,
        )

        // Bucket spinner (empty until loaded)
        binding.spinnerBucket.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            emptyList<String>(),
        )

        // Language spinner (single-language mode)
        binding.spinnerLanguage.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            languageNames,
        )

        // Language mode spinner (Manual / Auto-detect / Multi-language)
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
                    binding.txtLanguageModeHint.visibility =
                        if (pos == LANGUAGE_MODE_MANUAL) View.GONE else View.VISIBLE
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }

        binding.txtSelectLanguages.setOnClickListener { showLanguagePickerDialog() }

        // Output spinner
        val outputOptions = arrayOf(
            getString(R.string.output_selected_bucket),
            getString(R.string.output_service_managed),
        )
        binding.spinnerOutput.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, outputOptions,
        )

        // Speaker identification
        binding.spinnerMaxSpeakers.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            (2..10).map { it.toString() },
        )
        binding.spinnerMaxSpeakers.setSelection(3) // default to 5 speakers
        binding.switchSpeakerId.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutMaxSpeakers.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnPreview.setOnClickListener { togglePreview() }
        binding.btnRefresh.setOnClickListener { loadBuckets() }
        binding.btnUpload.setOnClickListener { uploadTestFile() }
        binding.btnReset.setOnClickListener { resetUpload() }
        binding.btnStartBatch.setOnClickListener { startBatchJob() }
        binding.btnCheckStatus.setOnClickListener { checkJobStatus() }
        binding.btnListJobs.setOnClickListener { listJobs() }

        loadBuckets()
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
        _binding = null
        super.onDestroyView()
    }

    private fun togglePreview() {
        if (previewPlayer.isPlaying) {
            previewPlayer.stop()
            binding.btnPreview.text = getString(R.string.btn_preview)
            return
        }
        val fileName = if (binding.spinnerTestFile.selectedItemPosition == 1) {
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

    // ==================== S3 Upload ====================

    private fun loadBuckets() {
        viewLifecycleOwner.lifecycleScope.launch {
            val s3Client: aws.sdk.kotlin.services.s3.S3Client
            try {
                s3Client = AwsClientFactory.s3(requireContext())
            } catch (e: Exception) {
                showToast(getString(R.string.msg_bucket_load_failed, e.message ?: e.toString()))
                return@launch
            }
            try {
                val buckets = withContext(Dispatchers.IO) {
                    s3Client.listBuckets().buckets ?: emptyList()
                }
                val names = buckets.mapNotNull { it.name }
                if (names.isEmpty()) {
                    showToast(getString(R.string.msg_no_buckets))
                    return@launch
                }
                binding.spinnerBucket.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    names,
                )
            } catch (e: Exception) {
                showToast(getString(R.string.msg_bucket_load_failed, e.message ?: e.toString()))
            } finally {
                withContext(Dispatchers.IO) { s3Client.close() }
            }
        }
    }

    private fun uploadTestFile() {
        val bucket = binding.spinnerBucket.selectedItem?.toString()
        if (bucket.isNullOrEmpty()) {
            showToast(getString(R.string.msg_select_bucket))
            return
        }

        val isMultiSpeaker = binding.spinnerTestFile.selectedItemPosition == 1

        viewLifecycleOwner.lifecycleScope.launch {
            val s3Client = AwsClientFactory.s3(requireContext())
            try {
                val wavBytes = if (isMultiSpeaker) {
                    withContext(Dispatchers.IO) {
                        requireContext().assets.open("test_audio_multi_speaker.wav").readBytes()
                    }
                } else {
                    val pcmBytes = withContext(Dispatchers.IO) {
                        requireContext().assets.open("test_audio.pcm").readBytes()
                    }
                    AudioUtils.wrapPcmInWav(pcmBytes)
                }
                val key = if (isMultiSpeaker) {
                    "transcribe-test/test_audio_multi_speaker.wav"
                } else {
                    "transcribe-test/test_audio.wav"
                }

                withContext(Dispatchers.IO) {
                    s3Client.putObject(PutObjectRequest {
                        this.bucket = bucket
                        this.key = key
                        body = ByteStream.fromBytes(wavBytes)
                    })
                }

                uploadedS3Uri = "s3://$bucket/$key"
                binding.layoutUpload.visibility = View.GONE
                binding.layoutUploaded.visibility = View.VISIBLE
                binding.txtS3Uri.text = uploadedS3Uri
                showToast(getString(R.string.msg_uploaded_to, uploadedS3Uri))
            } catch (e: Exception) {
                showToast(getString(R.string.msg_upload_failed, e.message))
            } finally {
                withContext(Dispatchers.IO) { s3Client.close() }
            }
        }
    }

    private fun resetUpload() {
        uploadedS3Uri = null
        binding.layoutUpload.visibility = View.VISIBLE
        binding.layoutUploaded.visibility = View.GONE
        @Suppress("SetTextI18n")
        binding.txtS3Uri.text = ""
    }

    // ==================== Batch Transcription ====================

    private fun startBatchJob() {
        viewLifecycleOwner.lifecycleScope.launch {
            val s3Uri = uploadedS3Uri
            var jobName = binding.edtJobName.text.toString().trim()

            if (s3Uri.isNullOrEmpty()) {
                showToast(getString(R.string.msg_upload_first))
                return@launch
            }
            if (jobName.isEmpty()) {
                jobName = "android-job-${UUID.randomUUID().toString().take(8)}"
                binding.edtJobName.setText(jobName)
            }

            val useSelectedBucket =
                binding.spinnerOutput.selectedItem?.toString() ==
                    getString(R.string.output_selected_bucket)
            val outputBucket = if (useSelectedBucket) {
                binding.spinnerBucket.selectedItem?.toString()
            } else {
                null
            }

            val languageMode = binding.spinnerLanguageMode.selectedItemPosition

            if (languageMode == LANGUAGE_MODE_MULTI && selectedCandidateIndices.size < 2) {
                showToast(getString(R.string.msg_select_at_least_two))
                return@launch
            }

            val client = AwsClientFactory.transcribe(requireContext())
            try {
                val request = StartTranscriptionJobRequest {
                    transcriptionJobName = jobName
                    media = Media { mediaFileUri = s3Uri }
                    mediaFormat = when {
                        s3Uri.endsWith(".mp3") -> MediaFormat.Mp3
                        s3Uri.endsWith(".mp4") -> MediaFormat.Mp4
                        s3Uri.endsWith(".flac") -> MediaFormat.Flac
                        s3Uri.endsWith(".ogg") -> MediaFormat.Ogg
                        else -> MediaFormat.Wav
                    }
                    when (languageMode) {
                        LANGUAGE_MODE_AUTO_DETECT -> {
                            identifyLanguage = true
                        }
                        LANGUAGE_MODE_MULTI -> {
                            identifyMultipleLanguages = true
                            languageOptions = selectedCandidateIndices.sorted()
                                .map { languageCodes[it] }
                        }
                        else -> {
                            languageCode = languageCodes[
                                binding.spinnerLanguage.selectedItemPosition
                            ]
                        }
                    }
                    if (binding.switchSpeakerId.isChecked) {
                        settings = Settings {
                            showSpeakerLabels = true
                            maxSpeakerLabels = binding.spinnerMaxSpeakers.selectedItem
                                .toString().toInt()
                        }
                    }
                    if (outputBucket != null) {
                        outputBucketName = outputBucket
                    }
                }
                val response = withContext(Dispatchers.IO) {
                    client.startTranscriptionJob(request)
                }

                val status = response.transcriptionJob?.transcriptionJobStatus
                binding.txtBatchResult.text = getString(R.string.msg_job_status, jobName, status)
                showToast(getString(R.string.msg_job_started))
            } catch (e: Exception) {
                binding.txtBatchResult.text = getString(R.string.msg_error_start_job, e.message)
            } finally {
                withContext(Dispatchers.IO) { client.close() }
            }
        }
    }

    private fun checkJobStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val jobName = binding.edtJobName.text.toString().trim()
            if (jobName.isEmpty()) {
                showToast(getString(R.string.msg_enter_job_name))
                return@launch
            }

            val client = AwsClientFactory.transcribe(requireContext())
            try {
                val request = GetTranscriptionJobRequest { transcriptionJobName = jobName }
                val response = withContext(Dispatchers.IO) {
                    client.getTranscriptionJob(request)
                }

                val job = response.transcriptionJob
                val status = job?.transcriptionJobStatus
                val sb = StringBuilder()
                sb.appendLine("Job: $jobName")
                sb.appendLine("Status: $status")

                job?.identifyMultipleLanguages?.let { multi ->
                    if (multi) {
                        sb.appendLine("Multi-language detection: enabled")
                    }
                }
                job?.languageCodes?.let { codes ->
                    if (codes.isNotEmpty()) {
                        sb.appendLine("Detected languages:")
                        codes.forEach { lc ->
                            val pct = lc.durationInSeconds?.let { " (${it}s)" } ?: ""
                            sb.appendLine("  ${lc.languageCode}$pct")
                        }
                    }
                }
                job?.identifiedLanguageScore?.let { score ->
                    sb.appendLine("Language confidence: ${"%.1f".format(score * 100)}%")
                }

                when (status) {
                    TranscriptionJobStatus.Completed -> {
                        val uri = AudioUtils.toS3Uri(job.transcript?.transcriptFileUri)
                        sb.appendLine("Transcript URI: $uri")
                        binding.txtBatchResult.text = sb.toString()
                        // Fetch and display the transcript content
                        fetchAndDisplayTranscript(uri, sb)
                    }
                    TranscriptionJobStatus.Failed -> {
                        sb.appendLine("Failure reason: ${job.failureReason}")
                        binding.txtBatchResult.text = sb.toString()
                    }
                    else -> {
                        sb.appendLine("The job is still in progress. Check again shortly.")
                        binding.txtBatchResult.text = sb.toString()
                    }
                }
            } catch (e: Exception) {
                binding.txtBatchResult.text = getString(R.string.msg_error_generic, e.message)
            } finally {
                withContext(Dispatchers.IO) { client.close() }
            }
        }
    }

    private fun fetchAndDisplayTranscript(s3Uri: String?, header: StringBuilder) {
        if (s3Uri == null) return
        val parsed = AudioUtils.parseS3Uri(s3Uri) ?: return
        val (bucket, key) = parsed

        viewLifecycleOwner.lifecycleScope.launch {
            binding.txtBatchResult.text = header.toString() + "\nLoading transcript..."
            val s3Client = AwsClientFactory.s3(requireContext())
            try {
                val jsonStr = withContext(Dispatchers.IO) {
                    s3Client.getObject(
                        aws.sdk.kotlin.services.s3.model.GetObjectRequest {
                            this.bucket = bucket
                            this.key = key
                        },
                    ) { output ->
                        output.body?.decodeToString() ?: ""
                    }
                }
                val sb = StringBuilder(header)
                sb.appendLine()
                parseTranscriptJson(jsonStr, sb)
                binding.txtBatchResult.text = sb.toString()
            } catch (e: Exception) {
                binding.txtBatchResult.text =
                    header.toString() + "\nFailed to load transcript: ${e.message}"
            } finally {
                withContext(Dispatchers.IO) { s3Client.close() }
            }
        }
    }

    private fun parseTranscriptJson(jsonStr: String, sb: StringBuilder) {
        val root = org.json.JSONObject(jsonStr)
        val results = root.optJSONObject("results") ?: return

        // Full transcript
        val transcripts = results.optJSONArray("transcripts")
        if (transcripts != null && transcripts.length() > 0) {
            val text = transcripts.getJSONObject(0).optString("transcript", "")
            sb.appendLine("━━━ Transcript ━━━")
            sb.appendLine(text)
        }

        val items = results.optJSONArray("items") ?: return

        // Collect speaker labels and language codes from items
        val allSpeakers = mutableSetOf<String>()
        val allLanguages = mutableSetOf<String>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            item.optString("speaker_label", "").let { if (it.isNotEmpty()) allSpeakers.add(it) }
            item.optString("language_code", "").let { if (it.isNotEmpty()) allLanguages.add(it) }
        }

        // Speaker segments
        if (allSpeakers.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("━━━ Speakers (${allSpeakers.size}) ━━━")

            data class Segment(val speaker: String, val startTime: String, val words: StringBuilder)

            val segments = mutableListOf<Segment>()
            var curSpeaker: String? = null
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val type = item.optString("type", "")
                val speaker = item.optString("speaker_label", "")
                val content = item.optJSONArray("alternatives")
                    ?.optJSONObject(0)?.optString("content", "") ?: ""

                if (type == "pronunciation") {
                    if (speaker != curSpeaker) {
                        val startTime = item.optString("start_time", "?")
                        segments.add(Segment(speaker, startTime, StringBuilder(content)))
                        curSpeaker = speaker
                    } else if (segments.isNotEmpty()) {
                        segments.last().words.append(" ").append(content)
                    }
                } else if (type == "punctuation" && segments.isNotEmpty()) {
                    segments.last().words.append(content)
                }
            }
            for (seg in segments) {
                sb.appendLine("[${seg.startTime}s] ${seg.speaker}: ${seg.words}")
            }
        }

        // Language segments
        if (allLanguages.size > 1) {
            sb.appendLine()
            sb.appendLine("━━━ Languages (${allLanguages.joinToString()}) ━━━")

            var curLang: String? = null
            val langSegments = mutableListOf<Pair<String, StringBuilder>>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val type = item.optString("type", "")
                val lang = item.optString("language_code", "")
                val content = item.optJSONArray("alternatives")
                    ?.optJSONObject(0)?.optString("content", "") ?: ""

                if (type == "pronunciation") {
                    if (lang != curLang) {
                        langSegments.add(lang to StringBuilder(content))
                        curLang = lang
                    } else if (langSegments.isNotEmpty()) {
                        langSegments.last().second.append(" ").append(content)
                    }
                } else if (type == "punctuation" && langSegments.isNotEmpty()) {
                    langSegments.last().second.append(content)
                }
            }
            for ((lang, text) in langSegments) {
                sb.appendLine("[$lang] $text")
            }
        }
    }

    private fun listJobs() {
        viewLifecycleOwner.lifecycleScope.launch {
            val client = AwsClientFactory.transcribe(requireContext())
            try {
                val request = ListTranscriptionJobsRequest { maxResults = 10 }
                val response = withContext(Dispatchers.IO) {
                    client.listTranscriptionJobs(request)
                }

                val sb = StringBuilder("Recent Transcription Jobs:\n\n")
                response.transcriptionJobSummaries?.forEach { summary ->
                    sb.appendLine("Name: ${summary.transcriptionJobName}")
                    sb.appendLine("  Status: ${summary.transcriptionJobStatus}")
                    sb.appendLine("  Created: ${summary.creationTime}")
                    sb.appendLine()
                }

                if (response.transcriptionJobSummaries.isNullOrEmpty()) {
                    sb.append(getString(R.string.msg_no_jobs))
                }

                binding.txtBatchResult.text = sb.toString()
            } catch (e: Exception) {
                binding.txtBatchResult.text = getString(R.string.msg_error_generic, e.message)
            } finally {
                withContext(Dispatchers.IO) { client.close() }
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
