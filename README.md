# Android Transcribe App

An Android demo app that uses the AWS SDK for Kotlin to perform both **real-time streaming** and **batch** transcription via Amazon Transcribe.

## Features

- **Streaming transcription** — stream audio from the microphone or a bundled test file to Amazon Transcribe Streaming and display results in real time.
- **Batch transcription** — select an S3 bucket, upload a test audio file, and start a batch transcription job. Optionally write results back to the same bucket or let the service manage the output.
- **Job management** — check job status and list recent transcription jobs.

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

## Setup

### 1. Open the project

Open this folder in Android Studio. It will detect the Gradle project automatically.

### 2. Configure AWS credentials

Edit `gradle.properties` in the project root and fill in your values:

```properties
AWS_REGION=us-east-1
AWS_ACCESS_KEY=YOUR_ACCESS_KEY_HERE
AWS_SECRET_KEY=YOUR_SECRET_KEY_HERE
```

These are injected into `BuildConfig` at compile time. **Do not commit this file with real credentials.**

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

1. Select **Test File** (bundled PCM audio) or **Microphone** from the dropdown.
2. Tap **Start Streaming**. Partial and final transcription results appear in the text area.
3. Tap **Stop Streaming** to end the session.

### Batch Transcription

1. The bucket spinner loads your S3 buckets on startup. Tap **Refresh** to reload.
2. Select a bucket and tap **Upload Test File to S3**. This converts the bundled PCM file to WAV and uploads it to `s3://<bucket>/transcribe-test/test_audio.wav`.
3. Choose an output destination:
   - **Selected bucket** (default) — transcription results are written to the same S3 bucket.
   - **Service-managed** — AWS manages the output location and provides a presigned URL.
4. Optionally enter a job name, or leave blank to auto-generate one.
5. Tap **Start Transcription Job**.
6. Tap **Check Job Status** to poll for completion and view the transcript URI.
7. Tap **List Recent Jobs** to see the last 10 transcription jobs.

## Project Structure

```
app/
  src/main/
    kotlin/.../MainActivity.kt   # All app logic (streaming, batch, S3 upload)
    res/layout/activity_main.xml # Single-activity UI layout
    assets/test_audio.pcm        # Bundled 16kHz mono 16-bit PCM test audio
    AndroidManifest.xml
build.gradle                     # Root build file (Kotlin & AGP versions)
app/build.gradle                 # App dependencies (AWS SDK, AndroidX)
gradle.properties                # AWS credentials (placeholder)
settings.gradle
```