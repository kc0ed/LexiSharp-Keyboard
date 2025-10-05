package com.example.asrkeyboard.asr

interface AsrEngine {
    val isRunning: Boolean
    fun start()
    fun stop()
}

interface StreamingAsrEngine : AsrEngine {
    interface Listener {
        fun onPartial(stableText: String, unstableText: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }
}
