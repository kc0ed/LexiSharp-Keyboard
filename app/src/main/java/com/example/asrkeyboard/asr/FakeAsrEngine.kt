package com.example.asrkeyboard.asr

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Placeholder ASR engine. Simulates recognition after a short delay.
 */
class FakeAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    private var job: Job? = null
    override val isRunning: Boolean
        get() = job != null

    override fun start() {
        if (job != null) return
        job = scope.launch {
            delay(500)
            listener.onPartial("", "你好，这")
            delay(500)
            listener.onPartial("你好，这是", "示例语音文本")
            delay(400)
            listener.onFinal("你好，这是示例语音文本")
            job = null
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}
