// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.example.androidtranscribe

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.transcribe.TranscribeClient
import aws.sdk.kotlin.services.transcribe.model.GetTranscriptionJobRequest
import aws.sdk.kotlin.services.transcribe.model.LanguageCode
import aws.sdk.kotlin.services.transcribe.model.ListTranscriptionJobsRequest
import aws.sdk.kotlin.services.transcribe.model.Media
import aws.sdk.kotlin.services.transcribe.model.MediaFormat
import aws.sdk.kotlin.services.transcribe.model.StartTranscriptionJobRequest
import aws.sdk.kotlin.services.transcribe.model.TranscriptionJobStatus
import aws.sdk.kotlin.services.transcribestreaming.TranscribeStreamingClient
import aws.sdk.kotlin.services.transcribestreaming.model.AudioEvent
import aws.sdk.kotlin.services.transcribestreaming.model.AudioStream
import aws.sdk.kotlin.services.transcribestreaming.model.MediaEncoding
import aws.sdk.kotlin.services.transcribestreaming.model.StartStreamTranscriptionRequest
import aws.sdk.kotlin.services.transcribestreaming.model.TranscriptResultStream
import aws.smithy.kotlin.runtime.content.ByteStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

// snippet-start:[transcribe.kotlin.android_transcribe_app]
class MainActivity : AppCompatActivity() {

    // AWS credentials are read from gradle.properties via BuildConfig.
    // Edit gradle.properties to set AWS_REGION, AWS_ACCESS_KEY, AWS_SECRET_KEY.
    private val awsRegion = BuildConfig.AWS_REGION
    private val accessKey = BuildConfig.AWS_ACCESS_KEY
    private val secretKey = BuildConfig.AWS_SECRET_KEY

    // Streaming state
    private var audioRecord: AudioRecord? = null
    private var isStreaming = false
    private var streamingJob: Job? = null

    // UI references
    private lateinit var spinnerSource: Spinner
    private lateinit var btnStartStreaming: Button
    private lateinit var txtStreamingResult: TextView
    private lateinit var scrollStreaming: ScrollView
    private lateinit var spinnerBucket: Spinner
    private lateinit var layoutUpload: LinearLayout
    private lateinit var layoutUploaded: LinearLayout
    private lateinit var txtS3Uri: TextView
    private lateinit var spinnerOutput: Spinner
    private lateinit var edtJobName: EditText
    private lateinit var btnStartBatch: Button
    private lateinit var btnCheckStatus: Button
    private lateinit var btnListJobs: Button
    private lateinit var txtBatchResult: TextView

    // S3 upload state
    private var uploadedS3Uri: String? = null

    companion object {
        private const val REQUEST_RECORD_AUDIO = 200
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE_BYTES = 1024
        private const val SOURCE_MIC = "Microphone"
        private const val SOURCE_FILE = "Test File"
        private const val OUTPUT_SELECTED_BUCKET = "Selected bucket"
        private const val OUTPUT_SERVICE_MANAGED = "Service-managed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerSource = findViewById(R.id.spinnerSource)
        btnStartStreaming = findViewById(R.id.btnStartStreaming)
        txtStreamingResult = findViewById(R.id.txtStreamingResult)
        scrollStreaming = findViewById(R.id.scrollStreaming)
        spinnerBucket = findViewById(R.id.spinnerBucket)
        layoutUpload = findViewById(R.id.layoutUpload)
        layoutUploaded = findViewById(R.id.layoutUploaded)
        txtS3Uri = findViewById(R.id.txtS3Uri)
        spinnerOutput = findViewById(R.id.spinnerOutput)
        edtJobName = findViewById(R.id.edtJobName)
        btnStartBatch = findViewById(R.id.btnStartBatch)
        btnCheckStatus = findViewById(R.id.btnCheckStatus)
        btnListJobs = findViewById(R.id.btnListJobs)
        txtBatchResult = findViewById(R.id.txtBatchResult)

        val sources = arrayOf(SOURCE_FILE, SOURCE_MIC)
        spinnerSource.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, sources,
        )

        val outputOptions = arrayOf(OUTPUT_SELECTED_BUCKET, OUTPUT_SERVICE_MANAGED)
        spinnerOutput.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, outputOptions,
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO,
            )
        }

        loadBuckets()
    }

    // ==================== Streaming Transcription ====================

    fun toggleStreaming(@Suppress("UNUSED_PARAMETER") view: View) {
        if (!isStreaming) {
            startStreaming()
        } else {
            stopStreaming()
        }
    }

    private fun startStreaming() {
        val useMic = spinnerSource.selectedItem.toString() == SOURCE_MIC

        if (useMic && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showToast("Microphone permission is required")
            return
        }

        isStreaming = true
        btnStartStreaming.text = "Stop Streaming"
        spinnerSource.isEnabled = false
        txtStreamingResult.text = ""

        if (useMic) {
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, channelConfig, encoding, bufferSize,
            )
            audioRecord?.startRecording()
        }

        val handler = CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                runOnUiThread {
                    stopStreaming()
                    showToast("Streaming error: ${e.message}")
                }
            }
        }

        streamingJob = lifecycleScope.launch(Dispatchers.IO + handler) {
            val audioFlow = if (useMic) buildMicFlow() else buildFileFlow()
            streamTranscribe(audioFlow, if (useMic) "Speak now." else "Streaming file...")
        }
    }

    private fun buildMicFlow(): Flow<AudioStream> = flow {
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        while (isStreaming) {
            val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (bytesRead > 0) {
                emit(AudioStream.AudioEvent(AudioEvent { audioChunk = buffer.copyOf(bytesRead) }))
            }
        }
    }

    private fun buildFileFlow(): Flow<AudioStream> = flow {
        val inputStream = assets.open("test_audio.pcm")
        val buffer = ByteArray(CHUNK_SIZE_BYTES)
        var totalSent = 0
        var bytesRead = inputStream.read(buffer)
        while (bytesRead > 0 && isStreaming) {
            emit(AudioStream.AudioEvent(AudioEvent { audioChunk = buffer.copyOf(bytesRead) }))
            totalSent += bytesRead
            delay(30) // ~32ms per 1024-byte chunk at 16kHz/16-bit
            bytesRead = inputStream.read(buffer)
        }
        inputStream.close()
        withContext(Dispatchers.Main) {
            txtStreamingResult.append("[File fully sent: $totalSent bytes]\n")
        }
    }

    private suspend fun streamTranscribe(audioFlow: Flow<AudioStream>, statusMsg: String) {
        val streamingClient = getStreamingClient()
        try {
            val request = StartStreamTranscriptionRequest {
                languageCode =
                    aws.sdk.kotlin.services.transcribestreaming.model.LanguageCode.EnUs
                mediaEncoding = MediaEncoding.Pcm
                mediaSampleRateHertz = SAMPLE_RATE
                audioStream = audioFlow
            }

            withContext(Dispatchers.Main) {
                txtStreamingResult.append("[Connecting to Transcribe Streaming...]\n")
            }

            streamingClient.startStreamTranscription(request) { resp ->
                withContext(Dispatchers.Main) {
                    txtStreamingResult.append("[Connected. $statusMsg]\n")
                }

                val resultStream = resp.transcriptResultStream
                if (resultStream == null) {
                    withContext(Dispatchers.Main) {
                        txtStreamingResult.append("[Error: transcriptResultStream is null]\n")
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
                                    txtStreamingResult.append("$prefix$text\n")
                                    scrollStreaming.fullScroll(View.FOCUS_DOWN)
                                }
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                txtStreamingResult.append("[Done]\n")
            }
        } catch (e: CancellationException) {
            // Normal cancellation when user stops streaming.
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                txtStreamingResult.append("\n[Error: ${e.message}]\n")
                showToast("Streaming error: ${e.message}")
            }
        } finally {
            withContext(Dispatchers.Main) { stopStreaming() }
            streamingClient.close()
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        streamingJob?.cancel()
        btnStartStreaming.text = "Start Streaming"
        spinnerSource.isEnabled = true
    }

    // ==================== Batch Transcription ====================

    fun startBatchJob(@Suppress("UNUSED_PARAMETER") view: View) {
        lifecycleScope.launch {
            val s3Uri = uploadedS3Uri
            var jobName = edtJobName.text.toString().trim()

            if (s3Uri.isNullOrEmpty()) {
                showToast("Upload a test file first")
                return@launch
            }
            if (jobName.isEmpty()) {
                jobName = "android-job-${UUID.randomUUID().toString().take(8)}"
                edtJobName.setText(jobName)
            }

            val useSelectedBucket =
                spinnerOutput.selectedItem?.toString() == OUTPUT_SELECTED_BUCKET
            val outputBucket = if (useSelectedBucket) {
                spinnerBucket.selectedItem?.toString()
            } else {
                null
            }

            val transcribeClient = getTranscribeClient()
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
                    languageCode = LanguageCode.EnUs
                    if (outputBucket != null) {
                        outputBucketName = outputBucket
                    }
                }
                val response = withContext(Dispatchers.IO) {
                    transcribeClient.startTranscriptionJob(request)
                }

                val status = response.transcriptionJob?.transcriptionJobStatus
                txtBatchResult.text = "Job '$jobName' started.\nStatus: $status"
                showToast("Transcription job started!")
            } catch (e: Exception) {
                txtBatchResult.text = "Error starting job: ${e.message}"
            } finally {
                withContext(Dispatchers.IO) { transcribeClient.close() }
            }
        }
    }

    fun checkJobStatus(@Suppress("UNUSED_PARAMETER") view: View) {
        lifecycleScope.launch {
            val jobName = edtJobName.text.toString().trim()
            if (jobName.isEmpty()) {
                showToast("Enter a job name first")
                return@launch
            }

            val transcribeClient = getTranscribeClient()
            try {
                val request = GetTranscriptionJobRequest { transcriptionJobName = jobName }
                val response = withContext(Dispatchers.IO) {
                    transcribeClient.getTranscriptionJob(request)
                }

                val job = response.transcriptionJob
                val status = job?.transcriptionJobStatus
                val sb = StringBuilder()
                sb.appendLine("Job: $jobName")
                sb.appendLine("Status: $status")

                when (status) {
                    TranscriptionJobStatus.Completed -> {
                        val uri = toS3Uri(job?.transcript?.transcriptFileUri)
                        sb.appendLine("Transcript URI:\n$uri")
                        sb.appendLine(
                            "\nDownload the JSON from the URI above to view the full transcript.",
                        )
                    }
                    TranscriptionJobStatus.Failed -> {
                        sb.appendLine("Failure reason: ${job?.failureReason}")
                    }
                    else -> {
                        sb.appendLine("The job is still in progress. Check again shortly.")
                    }
                }

                txtBatchResult.text = sb.toString()
            } catch (e: Exception) {
                txtBatchResult.text = "Error: ${e.message}"
            } finally {
                withContext(Dispatchers.IO) { transcribeClient.close() }
            }
        }
    }

    fun listJobs(@Suppress("UNUSED_PARAMETER") view: View) {
        lifecycleScope.launch {
            val transcribeClient = getTranscribeClient()
            try {
                val request = ListTranscriptionJobsRequest { maxResults = 10 }
                val response = withContext(Dispatchers.IO) {
                    transcribeClient.listTranscriptionJobs(request)
                }

                val sb = StringBuilder("Recent Transcription Jobs:\n\n")
                response.transcriptionJobSummaries?.forEach { summary ->
                    sb.appendLine("Name: ${summary.transcriptionJobName}")
                    sb.appendLine("  Status: ${summary.transcriptionJobStatus}")
                    sb.appendLine("  Created: ${summary.creationTime}")
                    sb.appendLine()
                }

                if (response.transcriptionJobSummaries.isNullOrEmpty()) {
                    sb.append("No transcription jobs found.")
                }

                txtBatchResult.text = sb.toString()
            } catch (e: Exception) {
                txtBatchResult.text = "Error: ${e.message}"
            } finally {
                withContext(Dispatchers.IO) { transcribeClient.close() }
            }
        }
    }

    // ==================== S3 Upload ====================

    private fun loadBuckets() {
        lifecycleScope.launch {
            val s3Client = getS3Client()
            try {
                val buckets = withContext(Dispatchers.IO) {
                    s3Client.listBuckets().buckets ?: emptyList()
                }
                val names = buckets.mapNotNull { it.name }
                if (names.isEmpty()) {
                    showToast("No S3 buckets found")
                    return@launch
                }
                spinnerBucket.adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    names,
                )
            } catch (e: Exception) {
                showToast("Failed to list buckets: ${e.message}")
            } finally {
                withContext(Dispatchers.IO) { s3Client.close() }
            }
        }
    }

    fun refreshBuckets(@Suppress("UNUSED_PARAMETER") view: View) {
        loadBuckets()
    }

    fun uploadTestFile(@Suppress("UNUSED_PARAMETER") view: View) {
        val bucket = spinnerBucket.selectedItem?.toString()
        if (bucket.isNullOrEmpty()) {
            showToast("Select a bucket first")
            return
        }

        lifecycleScope.launch {
            val s3Client = getS3Client()
            try {
                val pcmBytes = withContext(Dispatchers.IO) {
                    assets.open("test_audio.pcm").readBytes()
                }
                val wavBytes = wrapPcmInWav(pcmBytes)
                val key = "transcribe-test/test_audio.wav"

                withContext(Dispatchers.IO) {
                    s3Client.putObject(PutObjectRequest {
                        this.bucket = bucket
                        this.key = key
                        body = ByteStream.fromBytes(wavBytes)
                    })
                }

                uploadedS3Uri = "s3://$bucket/$key"
                layoutUpload.visibility = View.GONE
                layoutUploaded.visibility = View.VISIBLE
                txtS3Uri.text = uploadedS3Uri
                showToast("Uploaded to $uploadedS3Uri")
            } catch (e: Exception) {
                showToast("Upload failed: ${e.message}")
            } finally {
                withContext(Dispatchers.IO) { s3Client.close() }
            }
        }
    }

    fun resetUpload(@Suppress("UNUSED_PARAMETER") view: View) {
        uploadedS3Uri = null
        layoutUpload.visibility = View.VISIBLE
        layoutUploaded.visibility = View.GONE
        txtS3Uri.text = ""
    }

    private fun wrapPcmInWav(pcmData: ByteArray): ByteArray {
        val sampleRate = SAMPLE_RATE
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
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
        buffer.putInt(16) // sub-chunk size
        buffer.putShort(1) // PCM format
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())

        // data sub-chunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        buffer.put(pcmData)

        return buffer.array()
    }

    // ==================== AWS Client Helpers ====================

    private fun getS3Client(): S3Client {
        val staticCredentials = StaticCredentialsProvider {
            accessKeyId = accessKey
            secretAccessKey = secretKey
        }
        return S3Client {
            region = awsRegion
            credentialsProvider = staticCredentials
        }
    }

    private fun getTranscribeClient(): TranscribeClient {
        val staticCredentials = StaticCredentialsProvider {
            accessKeyId = accessKey
            secretAccessKey = secretKey
        }
        return TranscribeClient {
            region = awsRegion
            credentialsProvider = staticCredentials
        }
    }

    private fun getStreamingClient(): TranscribeStreamingClient {
        val staticCredentials = StaticCredentialsProvider {
            accessKeyId = accessKey
            secretAccessKey = secretKey
        }
        return TranscribeStreamingClient {
            region = awsRegion
            credentialsProvider = staticCredentials
        }
    }

    private fun toS3Uri(url: String?): String? {
        if (url == null) return null
        // https://s3.<region>.amazonaws.com/<bucket>/<key>
        val pathStyle = Regex("^https?://s3[.-][^/]+\\.amazonaws\\.com/([^/]+)/(.+)$")
        pathStyle.find(url)?.let { return "s3://${it.groupValues[1]}/${it.groupValues[2]}" }
        // https://<bucket>.s3.<region>.amazonaws.com/<key>
        val virtualStyle = Regex("^https?://(.+)\\.s3[.-][^/]+\\.amazonaws\\.com/(.+)$")
        virtualStyle.find(url)?.let { return "s3://${it.groupValues[1]}/${it.groupValues[2]}" }
        return url
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
// snippet-end:[transcribe.kotlin.android_transcribe_app]
