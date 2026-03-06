# Android Transcribe App

An Android demo app that uses the AWS SDK for Kotlin to perform both **real-time streaming** and **batch** transcription via Amazon Transcribe.

## Features

- **Streaming transcription** — stream audio from the microphone or bundled test files to Amazon Transcribe Streaming and display results in real time.
- **Batch transcription** — select an S3 bucket, upload a test audio file, and start a batch transcription job. When the job completes, the transcript is automatically fetched from S3 and displayed with speaker and language breakdowns.
- **Speaker identification (diarization)** — optional for both realtime and batch modes. Labels each segment with a speaker ID (e.g. `spk_0`, `spk_1`). Batch mode allows configuring max speakers (default 5).
- **Multi-language detection** — optional for both realtime and batch. Supports manual language selection, single-language auto-detect, and multi-language identification with candidate language picker.
- **Audio preview** — preview bundled test audio files before transcribing.
- **Job management** — check job status and list recent transcription jobs.
- **Two test audio files:**
  - `test_audio.pcm` — single speaker, English
  - `test_audio_multi_speaker.wav` — multiple speakers, English + Chinese

## Prerequisites

- Android Studio
- JDK 11+
- Android SDK with API level 34 (compileSdk) and minimum API 26
- An AWS account with access to Amazon Transcribe and Amazon S3
- An IAM user (or role) with the following permissions:
  - `transcribe:StartStreamTranscription`
  - `transcribe:StartTranscriptionJob`
  - `transcribe:GetTranscriptionJob`
  - `transcribe:ListTranscriptionJobs`
  - `s3:ListAllMyBuckets`
  - `s3:PutObject`
  - `s3:GetObject`

## Setup

### 1. Open the project

Open this folder in Android Studio. It will detect the Gradle project automatically.

### 2. Configure AWS credentials

Edit `local.properties` in the project root (create it if it doesn't exist) and add your values:

```properties
AWS_REGION=us-east-1
AWS_ACCESS_KEY=YOUR_ACCESS_KEY_HERE
AWS_SECRET_KEY=YOUR_SECRET_KEY_HERE
```

These are injected into `BuildConfig` at compile time. `local.properties` is already in `.gitignore` so it will not be committed.

Alternatively, you can enter credentials at runtime in the app's **Settings** tab.

### 3. Sync and build

1. Click **File > Sync Project with Gradle Files** (or let Android Studio sync automatically).
2. Build the project: **Build > Make Project**, or from the terminal:

```bash
./gradlew assembleDebug
```

### 4. Run

Deploy to an emulator (API 26+) or a physical device. The app requires:

- **Internet** — to reach AWS services.
- **Microphone** (optional) — only needed if you select "Microphone" as the streaming source. The app will prompt for permission at launch.

## Usage

### Streaming Transcription

1. Select an audio source: **Test File (Single Speaker, EN)**, **Test File (Multi-Speaker, EN+ZH)**, or **Microphone**.
2. Tap **Preview** to listen to the selected test file before transcribing.
3. Choose a language mode:
   - **Manual** — select a specific language.
   - **Auto-detect** — automatically identifies the language.
   - **Multi-language** — select 2+ candidate languages for multi-language streams.
4. Optionally enable **Speaker Identification** to label each segment by speaker.
5. Tap **Start Streaming**. Partial and final transcription results appear in the text area, with speaker labels and detected language tags when enabled.
6. Tap **Stop Streaming** to end the session.

### Batch Transcription

1. The bucket spinner loads your S3 buckets on startup. Tap **Refresh** to reload.
2. Select a test file: single-speaker (EN) or multi-speaker (EN+ZH). Tap **Preview** to listen first.
3. Select a bucket and tap **Upload Test File to S3**.
4. Configure the job:
   - **Language mode** — Manual, Auto-detect, or Multi-language.
   - **Speaker Identification** — toggle on and set max speakers (default 5).
   - **Output** — selected bucket or service-managed.
5. Optionally enter a job name, or leave blank to auto-generate one.
6. Tap **Start Transcription Job**.
7. Tap **Check Job Status** — when the job completes, the transcript JSON is automatically fetched from S3 and displayed with:
   - Full transcript text
   - Speaker segments with timestamps (if diarization was enabled)
   - Language segments (if multi-language detection was enabled)
8. Tap **List Recent Jobs** to see the last 10 transcription jobs.

## Project Structure

```
app/
  src/main/
    kotlin/.../
      MainActivity.kt                  # Host activity with bottom navigation
      audio/AudioPreviewPlayer.kt      # Audio playback for test file preview
      audio/AudioUtils.kt              # PCM-to-WAV conversion, S3 URI parsing
      aws/AwsClientFactory.kt          # AWS SDK client builders
      aws/AwsPrefs.kt                  # Credential storage (SharedPreferences + BuildConfig)
      ui/batch/BatchFragment.kt        # Batch transcription UI
      ui/realtime/RealtimeFragment.kt  # Streaming transcription UI
      ui/settings/SettingsFragment.kt  # AWS credential configuration UI
    res/layout/
      activity_main.xml                # Main layout with bottom navigation
      fragment_batch.xml               # Batch tab layout
      fragment_realtime.xml            # Realtime tab layout
      fragment_settings.xml            # Settings tab layout
    assets/
      test_audio.pcm                   # 16kHz mono 16-bit PCM (single speaker, EN)
      test_audio_multi_speaker.wav     # 16kHz mono 16-bit WAV (multi-speaker, EN+ZH)
    AndroidManifest.xml
build.gradle                           # Root build file (Kotlin & AGP versions)
app/build.gradle                       # App dependencies (AWS SDK, AndroidX)
local.properties                       # AWS credentials (gitignored)
settings.gradle
```
