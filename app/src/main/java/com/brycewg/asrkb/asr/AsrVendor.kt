package com.brycewg.asrkb.asr

enum class AsrVendor(val id: String) {
    Volc("volc"),
    SiliconFlow("siliconflow"),
    ElevenLabs("elevenlabs"),
    OpenAI("openai"),
    DashScope("dashscope");

    companion object {
        fun fromId(id: String?): AsrVendor = when (id?.lowercase()) {
            SiliconFlow.id -> SiliconFlow
            ElevenLabs.id -> ElevenLabs
            OpenAI.id -> OpenAI
            DashScope.id -> DashScope
            else -> Volc
        }
    }
}
