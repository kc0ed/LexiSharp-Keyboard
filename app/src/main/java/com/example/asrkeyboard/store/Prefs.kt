package com.example.asrkeyboard.store

import android.content.Context
import androidx.core.content.edit
import com.example.asrkeyboard.asr.AsrVendor

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)

    // Volcengine credentials
    var appKey: String
        get() = sp.getString(KEY_APP_KEY, "") ?: ""
        set(value) = sp.edit { putString(KEY_APP_KEY, value.trim()) }

    var accessKey: String
        get() = sp.getString(KEY_ACCESS_KEY, "") ?: ""
        set(value) = sp.edit { putString(KEY_ACCESS_KEY, value.trim()) }

    var trimFinalTrailingPunct: Boolean
        get() = sp.getBoolean(KEY_TRIM_FINAL_TRAILING_PUNCT, false)
        set(value) = sp.edit { putBoolean(KEY_TRIM_FINAL_TRAILING_PUNCT, value) }

    var showImeSwitcherButton: Boolean
        get() = sp.getBoolean(KEY_SHOW_IME_SWITCHER_BUTTON, true)
        set(value) = sp.edit { putBoolean(KEY_SHOW_IME_SWITCHER_BUTTON, value) }

    // Auto switch away on password fields
    var autoSwitchOnPassword: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH_ON_PASSWORD, true)
        set(value) = sp.edit { putBoolean(KEY_AUTO_SWITCH_ON_PASSWORD, value) }

    // 麦克风按钮触觉反馈
    var micHapticEnabled: Boolean
        get() = sp.getBoolean(KEY_MIC_HAPTIC_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_MIC_HAPTIC_ENABLED, value) }

    // LLM后处理设置
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

    // Deprecated: single prompt. Kept for backward compatibility/migration.
    var llmPrompt: String
        get() = sp.getString(KEY_LLM_PROMPT, DEFAULT_LLM_PROMPT) ?: DEFAULT_LLM_PROMPT
        set(value) = sp.edit { putString(KEY_LLM_PROMPT, value) }

    // Multiple prompt presets with titles and active selection
    var promptPresetsJson: String
        get() = sp.getString(KEY_LLM_PROMPT_PRESETS, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_PROMPT_PRESETS, value) }

    var activePromptId: String
        get() = sp.getString(KEY_LLM_PROMPT_ACTIVE_ID, "") ?: ""
        set(value) = sp.edit { putString(KEY_LLM_PROMPT_ACTIVE_ID, value) }

    fun getPromptPresets(): List<PromptPreset> {
        // Migrate from legacy single prompt if no presets set yet
        if (promptPresetsJson.isBlank()) {
            val defaults = buildDefaultPromptPresets(legacy = llmPrompt)
            setPromptPresets(defaults)
            // set first as active if not set
            if (activePromptId.isBlank()) activePromptId = defaults.firstOrNull()?.id ?: ""
            return defaults
        }
        return try {
            val arr = org.json.JSONArray(promptPresetsJson)
            val list = mutableListOf<PromptPreset>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
                val title = o.optString("title").ifBlank { "未命名预设" }
                val content = o.optString("content")
                list.add(PromptPreset(id, title, content))
            }
            if (list.isEmpty()) buildDefaultPromptPresets() else list
        } catch (_: Throwable) {
            buildDefaultPromptPresets()
        }
    }

    fun setPromptPresets(list: List<PromptPreset>) {
        val arr = org.json.JSONArray()
        list.forEach { p ->
            val o = org.json.JSONObject()
            o.put("id", p.id)
            o.put("title", p.title)
            o.put("content", p.content)
            arr.put(o)
        }
        promptPresetsJson = arr.toString()
        // Ensure active id is valid
        if (list.none { it.id == activePromptId }) {
            activePromptId = list.firstOrNull()?.id ?: ""
        }
    }

    val activePromptContent: String
        get() {
            val id = activePromptId
            val presets = getPromptPresets()
            val found = presets.firstOrNull { it.id == id }
            return found?.content ?: (if (llmPrompt.isNotBlank()) llmPrompt else DEFAULT_LLM_PROMPT)
        }

    // SiliconFlow credentials
    var sfApiKey: String
        get() = sp.getString(KEY_SF_API_KEY, "") ?: ""
        set(value) = sp.edit { putString(KEY_SF_API_KEY, value.trim()) }

    var sfModel: String
        get() = sp.getString(KEY_SF_MODEL, DEFAULT_SF_MODEL) ?: DEFAULT_SF_MODEL
        set(value) = sp.edit { putString(KEY_SF_MODEL, value.trim()) }

    // ElevenLabs credentials
    var elevenApiKey: String
        get() = sp.getString(KEY_ELEVEN_API_KEY, "") ?: ""
        set(value) = sp.edit { putString(KEY_ELEVEN_API_KEY, value.trim()) }

    var elevenModelId: String
        get() = sp.getString(KEY_ELEVEN_MODEL_ID, "") ?: ""
        set(value) = sp.edit { putString(KEY_ELEVEN_MODEL_ID, value.trim()) }

    // Selected ASR vendor
    var asrVendor: AsrVendor
        get() = AsrVendor.fromId(sp.getString(KEY_ASR_VENDOR, AsrVendor.Volc.id))
        set(value) = sp.edit { putString(KEY_ASR_VENDOR, value.id) }

    fun hasVolcKeys(): Boolean = appKey.isNotBlank() && accessKey.isNotBlank()
    fun hasSfKeys(): Boolean = sfApiKey.isNotBlank()
    fun hasElevenKeys(): Boolean = elevenApiKey.isNotBlank()
    fun hasAsrKeys(): Boolean = when (asrVendor) {
        AsrVendor.Volc -> hasVolcKeys()
        AsrVendor.SiliconFlow -> hasSfKeys()
        AsrVendor.ElevenLabs -> hasElevenKeys()
    }
    fun hasLlmKeys(): Boolean = llmApiKey.isNotBlank() && llmEndpoint.isNotBlank() && llmModel.isNotBlank()

    // Custom punctuation buttons (4 slots)
    var punct1: String
        get() = (sp.getString(KEY_PUNCT_1, DEFAULT_PUNCT_1) ?: DEFAULT_PUNCT_1).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_1, value.trim()) }

    var punct2: String
        get() = (sp.getString(KEY_PUNCT_2, DEFAULT_PUNCT_2) ?: DEFAULT_PUNCT_2).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_2, value.trim()) }

    var punct3: String
        get() = (sp.getString(KEY_PUNCT_3, DEFAULT_PUNCT_3) ?: DEFAULT_PUNCT_3).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_3, value.trim()) }

    var punct4: String
        get() = (sp.getString(KEY_PUNCT_4, DEFAULT_PUNCT_4) ?: DEFAULT_PUNCT_4).trim()
        set(value) = sp.edit { putString(KEY_PUNCT_4, value.trim()) }

    companion object {
        private const val KEY_APP_KEY = "app_key"
        private const val KEY_ACCESS_KEY = "access_key"
        private const val KEY_TRIM_FINAL_TRAILING_PUNCT = "trim_final_trailing_punct"
        private const val KEY_SHOW_IME_SWITCHER_BUTTON = "show_ime_switcher_button"
        private const val KEY_AUTO_SWITCH_ON_PASSWORD = "auto_switch_on_password"
        private const val KEY_MIC_HAPTIC_ENABLED = "mic_haptic_enabled"
        private const val KEY_POSTPROC_ENABLED = "postproc_enabled"
        private const val KEY_LLM_ENDPOINT = "llm_endpoint"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_LLM_TEMPERATURE = "llm_temperature"
        private const val KEY_LLM_PROMPT = "llm_prompt"
        private const val KEY_LLM_PROMPT_PRESETS = "llm_prompt_presets"
        private const val KEY_LLM_PROMPT_ACTIVE_ID = "llm_prompt_active_id"
        private const val KEY_ASR_VENDOR = "asr_vendor"
        private const val KEY_SF_API_KEY = "sf_api_key"
        private const val KEY_SF_MODEL = "sf_model"
        private const val KEY_ELEVEN_API_KEY = "eleven_api_key"
        private const val KEY_ELEVEN_MODEL_ID = "eleven_model_id"
        private const val KEY_PUNCT_1 = "punct_1"
        private const val KEY_PUNCT_2 = "punct_2"
        private const val KEY_PUNCT_3 = "punct_3"
        private const val KEY_PUNCT_4 = "punct_4"

        const val DEFAULT_ENDPOINT = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
        const val SF_ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
        const val DEFAULT_SF_MODEL = "FunAudioLLM/SenseVoiceSmall"

        // Reasonable OpenAI-format defaults
        const val DEFAULT_LLM_ENDPOINT = "https://api.openai.com/v1"
        const val DEFAULT_LLM_MODEL = "gpt-4o-mini"
        const val DEFAULT_LLM_TEMPERATURE = 0.2f
        const val DEFAULT_LLM_PROMPT = "# 角色\n\n你是一个顶级的 ASR（自动语音识别）后处理专家。\n\n# 任务\n\n你的任务是接收一段由 ASR 系统转录的原始文本，并将其精炼成一段通顺、准确、书面化的文本。你需要严格遵循以下规则，仅输出修正后的最终文本。\n\n# 规则\n\n1.  **去除无关填充词**: 彻底删除所有无意义的语气词、犹豫词和口头禅。\n    - **示例**: \"嗯\"、\"啊\"、\"呃\"、\"那个\"、\"然后\"、\"就是说\"等。\n2.  **合并重复与修正口误**: 当说话者重复单词、短语或进行自我纠正时，你需要整合这些内容，只保留其最终的、最清晰的意图。\n    - **重复示例**: 将\"我想...我想去...\"修正为\"我想去...\"。\n    - **口误修正示例**: 将\"我们明天去上海，哦不对，去苏州开会\"修正为\"我们明天去苏州开会\"。\n3.  **修正识别错误**: 根据上下文语境，纠正明显不符合逻辑的同音、近音词汇。\n    - **同音词示例**: 将\"请大家准时参加明天的『会意』\"修正为\"请大家准时参加明天的『会议』\"\n4.  **保持语义完整性**: 确保修正后的文本忠实于说话者的原始意图，不要进行主观臆断或添加额外信息。\n\n# 示例\n\n- **原始文本**: \"嗯...那个...我想确认一下，我们明天，我们明天的那个会意，啊不对，会议，时间是不是...是不是上午九点？\"\n- **修正后文本**: \"我想确认一下，我们明天的那个会议时间是不是上午九点？\"\n  请根据以上所有规则，处理给定文本"

        // Defaults for punctuation buttons
        const val DEFAULT_PUNCT_1 = "，"
        const val DEFAULT_PUNCT_2 = "。"
        const val DEFAULT_PUNCT_3 = "！"
        const val DEFAULT_PUNCT_4 = "？"

        private fun buildDefaultPromptPresets(legacy: String = DEFAULT_LLM_PROMPT): List<PromptPreset> {
            val p1 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "默认精炼",
                content = legacy.ifBlank { DEFAULT_LLM_PROMPT }
            )
            val p2 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "仅纠错不改写",
                content = "仅在不改变原意的前提下进行最小必要的纠错：修正错别字、标点、大小写与明显的口误。不要重写或美化句式，不要添加或省略信息。输出纠正后的文本。"
            )
            val p3 = PromptPreset(
                id = java.util.UUID.randomUUID().toString(),
                title = "保留口语风格",
                content = "在尽量保持口语风格的前提下，去除明显的口头禅与重复，统一人名/地名等专有名词的写法。尽量不改变原句结构。只输出处理后的文本。"
            )
            return listOf(p1, p2, p3)
        }
    }
}
