package com.example.androidtranscribe.aws

import android.content.Context
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.transcribe.TranscribeClient
import aws.sdk.kotlin.services.transcribestreaming.TranscribeStreamingClient

object AwsClientFactory {

    private fun credentials(context: Context) = StaticCredentialsProvider {
        accessKeyId = AwsPrefs.getAccessKey(context)
        secretAccessKey = AwsPrefs.getSecretKey(context)
    }

    private fun region(context: Context) = AwsPrefs.getRegion(context)

    fun s3(context: Context): S3Client = S3Client {
        region = region(context)
        credentialsProvider = credentials(context)
    }

    fun transcribe(context: Context): TranscribeClient = TranscribeClient {
        region = region(context)
        credentialsProvider = credentials(context)
    }

    fun transcribeStreaming(context: Context): TranscribeStreamingClient = TranscribeStreamingClient {
        region = region(context)
        credentialsProvider = credentials(context)
    }
}
