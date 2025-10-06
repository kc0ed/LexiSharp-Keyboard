package com.example.asrkeyboard.store

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)

    var appKey: String
        get() = sp.getString(KEY_APP_KEY, "") ?: ""
        set(value) = sp.edit { putString(KEY_APP_KEY, value.trim()) }

    var accessKey: String
        get() = sp.getString(KEY_ACCESS_KEY, "") ?: ""
        set(value) = sp.edit { putString(KEY_ACCESS_KEY, value.trim()) }

    var resourceId: String
        get() = sp.getString(KEY_RESOURCE_ID, DEFAULT_RESOURCE) ?: DEFAULT_RESOURCE
        set(value) = sp.edit { putString(KEY_RESOURCE_ID, value.trim()) }

    var endpoint: String
        get() = sp.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        set(value) = sp.edit { putString(KEY_ENDPOINT, value.trim()) }

    var connectId: String
        get() = sp.getString(KEY_CONNECT_ID, UUID.randomUUID().toString()) ?: UUID.randomUUID().toString()
        set(value) = sp.edit { putString(KEY_CONNECT_ID, value) }

    var trimFinalTrailingPunct: Boolean
        get() = sp.getBoolean(KEY_TRIM_FINAL_TRAILING_PUNCT, false)
        set(value) = sp.edit { putBoolean(KEY_TRIM_FINAL_TRAILING_PUNCT, value) }

    var showImeSwitcherButton: Boolean
        get() = sp.getBoolean(KEY_SHOW_IME_SWITCHER_BUTTON, true)
        set(value) = sp.edit { putBoolean(KEY_SHOW_IME_SWITCHER_BUTTON, value) }

    // --- LLM post-processing settings ---
    var postProcessEnabled: Boolean
        get() = sp.getBoolean(KEY_POSTPROC_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_POSTPROC_ENABLED, value) }

    var llmEndpoint: String
        get() = sp.getString(KEY_LLM_ENDPOINT, DEFAULT_LLM_ENDPOINT) ?: DEFAULT_LLM_ENDPOINT
        set(value) = sp.edit { putString(KEY_LLM_ENDPOINT, value.trim()) }

    var llmApiKey: String
        get() = sp.getString(KEY_LLM_API_KEY, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_API_KEY, value.trim()) }

    var llmModel: String
        get() = sp.getString(KEY_LLM_MODEL, DEFAULT_LLM_MODEL) ?: DEFAULT_LLM_MODEL
        set(value) = sp.edit { putString(KEY_LLM_MODEL, value.trim()) }

    var llmTemperature: Float
        get() = sp.getFloat(KEY_LLM_TEMPERATURE, DEFAULT_LLM_TEMPERATURE)
        set(value) = sp.edit { putFloat(KEY_LLM_TEMPERATURE, value) }

    var llmPrompt: String
        get() = sp.getString(KEY_LLM_PROMPT, DEFAULT_LLM_PROMPT) ?: DEFAULT_LLM_PROMPT
        set(value) = sp.edit { putString(KEY_LLM_PROMPT, value) }

    fun hasVolcKeys(): Boolean = appKey.isNotBlank() && accessKey.isNotBlank()
    fun hasLlmKeys(): Boolean = llmApiKey.isNotBlank() && llmEndpoint.isNotBlank() && llmModel.isNotBlank()

    companion object {
        private const val KEY_APP_KEY = "app_key"
        private const val KEY_ACCESS_KEY = "access_key"
        private const val KEY_RESOURCE_ID = "resource_id"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_CONNECT_ID = "connect_id"
        private const val KEY_TRIM_FINAL_TRAILING_PUNCT = "trim_final_trailing_punct"
        private const val KEY_SHOW_IME_SWITCHER_BUTTON = "show_ime_switcher_button"
        private const val KEY_POSTPROC_ENABLED = "postproc_enabled"
        private const val KEY_LLM_ENDPOINT = "llm_endpoint"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_LLM_TEMPERATURE = "llm_temperature"
        private const val KEY_LLM_PROMPT = "llm_prompt"

        const val DEFAULT_RESOURCE = "volc.bigasr.sauc.duration"
        const val DEFAULT_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"

        // Reasonable OpenAI-format defaults
        const val DEFAULT_LLM_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_LLM_MODEL = "gpt-4o-mini"
        const val DEFAULT_LLM_TEMPERATURE = 0.2f
        const val DEFAULT_LLM_PROMPT = "你是一个文本清理与润色助手。请在尽量保留用户原意的前提下：1) 去除口头禅与明显的口误；2) 修复语法或错别字；3) 不要添加事实信息；4) 保持口语化与自然流畅；5) 若文本为多句，适度分句并规范标点。仅输出处理后的文本。"
    }
}
