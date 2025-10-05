package com.example.asrkeyboard.store

import android.content.Context
import java.util.UUID

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)

    var appKey: String
        get() = sp.getString(KEY_APP_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_APP_KEY, value.trim()).apply()

    var accessKey: String
        get() = sp.getString(KEY_ACCESS_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_ACCESS_KEY, value.trim()).apply()

    var resourceId: String
        get() = sp.getString(KEY_RESOURCE_ID, DEFAULT_RESOURCE) ?: DEFAULT_RESOURCE
        set(value) = sp.edit().putString(KEY_RESOURCE_ID, value.trim()).apply()

    var endpoint: String
        get() = sp.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        set(value) = sp.edit().putString(KEY_ENDPOINT, value.trim()).apply()

    var connectId: String
        get() = sp.getString(KEY_CONNECT_ID, UUID.randomUUID().toString()) ?: UUID.randomUUID().toString()
        set(value) = sp.edit().putString(KEY_CONNECT_ID, value).apply()

    var continuousMode: Boolean
        get() = sp.getBoolean(KEY_CONTINUOUS, false)
        set(value) = sp.edit().putBoolean(KEY_CONTINUOUS, value).apply()

    fun hasVolcKeys(): Boolean = appKey.isNotBlank() && accessKey.isNotBlank()

    companion object {
        private const val KEY_APP_KEY = "app_key"
        private const val KEY_ACCESS_KEY = "access_key"
        private const val KEY_RESOURCE_ID = "resource_id"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_CONNECT_ID = "connect_id"
        private const val KEY_CONTINUOUS = "continuous"

        const val DEFAULT_RESOURCE = "volc.bigasr.sauc.duration"
        const val DEFAULT_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    }
}
