package com.example.androidtranscribe.aws

import android.content.Context
import android.content.SharedPreferences
import com.example.androidtranscribe.BuildConfig

object AwsPrefs {

    private const val FILE_NAME = "aws_credentials"
    private const val KEY_REGION = "region"
    private const val KEY_ACCESS_KEY = "access_key"
    private const val KEY_SECRET_KEY = "secret_key"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    fun getRegion(context: Context): String {
        val saved = getPrefs(context).getString(KEY_REGION, null)
        return if (!saved.isNullOrBlank()) saved else BuildConfig.AWS_REGION
    }

    fun getAccessKey(context: Context): String {
        val saved = getPrefs(context).getString(KEY_ACCESS_KEY, null)
        return if (!saved.isNullOrBlank()) saved else BuildConfig.AWS_ACCESS_KEY
    }

    fun getSecretKey(context: Context): String {
        val saved = getPrefs(context).getString(KEY_SECRET_KEY, null)
        return if (!saved.isNullOrBlank()) saved else BuildConfig.AWS_SECRET_KEY
    }

    fun save(context: Context, region: String, accessKey: String, secretKey: String) {
        getPrefs(context).edit()
            .putString(KEY_REGION, region)
            .putString(KEY_ACCESS_KEY, accessKey)
            .putString(KEY_SECRET_KEY, secretKey)
            .apply()
    }

    fun isConfigured(context: Context): Boolean {
        return getAccessKey(context).isNotBlank() && getSecretKey(context).isNotBlank()
    }
}
