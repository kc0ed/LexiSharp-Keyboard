package com.brycewg.asrkb.ui.floatingball

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.asr.*
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrAccessibilityService.FocusContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ASR 会话管理器
 * 负责 ASR 引擎的生命周期管理、结果处理和超时兜底
 */
class AsrSessionManager(
    private val context: Context,
    private val prefs: Prefs,
    private val serviceScope: CoroutineScope,
    private val listener: AsrSessionListener
) : StreamingAsrEngine.Listener {

    companion object {
        private const val TAG = "AsrSessionManager"
        private const val PROCESSING_TIMEOUT_MS = 8000L
    }

    interface AsrSessionListener {
        fun onSessionStateChanged(state: FloatingBallState)
        fun onResultCommitted(text: String, success: Boolean)
        fun onError(message: String)
    }

    private var asrEngine: StreamingAsrEngine? = null
    private val postproc = LlmPostProcessor()

    // 会话上下文
    private var focusContext: FocusContext? = null
    private var lastPartialForPreview: String? = null
    private var markerInserted: Boolean = false
    private var markerChar: String? = null

    // 超时控制
    private var processingTimeoutJob: Job? = null
    private var hasCommittedResult: Boolean = false

    /** 开始录音 */
    fun startRecording() {
        Log.d(TAG, "startRecording called")

        // 检查本地 SenseVoice 模型（如果需要）
        if (!checkSenseVoiceModel()) {
            listener.onError(context.getString(com.brycewg.asrkb.R.string.error_sensevoice_model_missing))
            return
        }

        // 清理上次会话
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel previous timeout job", e)
        }
        processingTimeoutJob = null
        hasCommittedResult = false

        // Telegram 占位符修复
        tryFixTelegramPlaceholderIfNeeded()

        // 构建引擎
        asrEngine = buildEngineForCurrentMode()
        Log.d(TAG, "ASR engine created: ${asrEngine?.javaClass?.simpleName}")

        // 记录焦点上下文
        focusContext = com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusContext()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                focusContext = com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusContext()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to refresh focus context", e)
            }
        }, 120)
        lastPartialForPreview = null

        // 启动引擎
        listener.onSessionStateChanged(FloatingBallState.Recording)
        asrEngine?.start()
    }

    /** 停止录音 */
    fun stopRecording() {
        Log.d(TAG, "stopRecording called")
        asrEngine?.stop()

        // 进入处理阶段
        listener.onSessionStateChanged(FloatingBallState.Processing)

        // 启动超时兜底
        startProcessingTimeout()
    }

    /** 清理会话 */
    fun cleanup() {
        try {
            asrEngine?.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop ASR engine", e)
        }
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel timeout job", e)
        }
    }

    // ==================== StreamingAsrEngine.Listener ====================

    override fun onFinal(text: String) {
        Log.d(TAG, "onFinal called with text: $text")
        serviceScope.launch {
            try {
                processingTimeoutJob?.cancel()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to cancel timeout job in onFinal", e)
            }
            processingTimeoutJob = null

            // 若已由兜底提交，忽略后续 onFinal
            if (hasCommittedResult && asrEngine?.isRunning != true) {
                Log.w(TAG, "Result already committed by fallback; ignoring residual onFinal")
                return@launch
            }

            var finalText = text
            val stillRecording = (asrEngine?.isRunning == true)

            // AI 后处理
            if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
                Log.d(TAG, "Starting AI post-processing (stillRecording=$stillRecording)")
                if (!stillRecording) {
                    listener.onSessionStateChanged(FloatingBallState.Processing)
                }

                val raw = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(text) else text
                finalText = try {
                    postproc.process(raw, prefs).ifBlank { raw }
                } catch (e: Throwable) {
                    Log.e(TAG, "Post-processing failed", e)
                    raw
                }

                if (prefs.trimFinalTrailingPunct) {
                    finalText = trimTrailingPunctuation(finalText)
                }
                Log.d(TAG, "Post-processing completed: $finalText")
            } else if (prefs.trimFinalTrailingPunct) {
                finalText = trimTrailingPunctuation(text)
            }

            // 更新状态
            if (asrEngine?.isRunning == true) {
                listener.onSessionStateChanged(FloatingBallState.Recording)
            } else {
                listener.onSessionStateChanged(FloatingBallState.Idle)
            }

            // 插入文本
            if (finalText.isNotEmpty()) {
                val success = insertTextToFocus(finalText)
                listener.onResultCommitted(finalText, success)
            } else {
                Log.w(TAG, "Final text is empty")
                listener.onError(context.getString(com.brycewg.asrkb.R.string.asr_error_empty_result))
            }

            // 清理会话上下文
            focusContext = null
            lastPartialForPreview = null
            markerInserted = false
            markerChar = null
        }
    }

    override fun onStopped() {
        serviceScope.launch {
            listener.onSessionStateChanged(FloatingBallState.Processing)
            startProcessingTimeout()
        }
    }

    override fun onPartial(text: String) {
        if (text.isEmpty() || lastPartialForPreview == text) return
        val ctx = focusContext ?: return
        val toWrite = ctx.prefix + text + ctx.suffix
        Log.d(TAG, "onPartial preview: $text")

        serviceScope.launch {
            com.brycewg.asrkb.ui.AsrAccessibilityService.insertTextSilent(toWrite)
            val prefixLenForCursor = stripMarkersIfAny(ctx.prefix).length
            val desiredCursor = (prefixLenForCursor + text.length).coerceAtLeast(0)
            com.brycewg.asrkb.ui.AsrAccessibilityService.setSelectionSilent(desiredCursor)
        }
        lastPartialForPreview = text
    }

    override fun onError(message: String) {
        Log.e(TAG, "onError called: $message")
        serviceScope.launch {
            try {
                processingTimeoutJob?.cancel()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to cancel timeout job in onError", e)
            }
            processingTimeoutJob = null

            listener.onSessionStateChanged(FloatingBallState.Error(message))
            listener.onError(message)

            // 清理会话上下文
            focusContext = null
            lastPartialForPreview = null
        }
    }

    // ==================== 私有辅助方法 ====================

    private fun checkSenseVoiceModel(): Boolean {
        if (prefs.asrVendor != AsrVendor.SenseVoice) return true

        val prepared = try {
            com.brycewg.asrkb.asr.isSenseVoicePrepared()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check SenseVoice preparation", e)
            false
        }
        if (prepared) return true

        // 检查模型文件
        val base = try {
            context.getExternalFilesDir(null)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get external files dir", e)
            context.filesDir
        }
        val probeRoot = java.io.File(base, "sensevoice")
        val variant = try {
            prefs.svModelVariant
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get SenseVoice variant", e)
            "small-int8"
        }
        val variantDir = if (variant == "small-full") {
            java.io.File(probeRoot, "small-full")
        } else {
            java.io.File(probeRoot, "small-int8")
        }
        val found = com.brycewg.asrkb.asr.findSvModelDir(variantDir)
            ?: com.brycewg.asrkb.asr.findSvModelDir(probeRoot)
        return found != null
    }

    private fun buildEngineForCurrentMode(): StreamingAsrEngine? {
        return when (prefs.asrVendor) {
            AsrVendor.Volc -> if (prefs.hasVolcKeys()) {
                if (prefs.volcStreamingEnabled) {
                    VolcStreamAsrEngine(context, serviceScope, prefs, this)
                } else {
                    VolcFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = { })
                }
            } else null
            AsrVendor.SiliconFlow -> if (prefs.hasSfKeys()) {
                SiliconFlowFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = { })
            } else null
            AsrVendor.ElevenLabs -> if (prefs.hasElevenKeys()) {
                ElevenLabsFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = { })
            } else null
            AsrVendor.OpenAI -> if (prefs.hasOpenAiKeys()) {
                OpenAiFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = { })
            } else null
            AsrVendor.DashScope -> if (prefs.hasDashKeys()) {
                DashscopeFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = { })
            } else null
            AsrVendor.Gemini -> if (prefs.hasGeminiKeys()) {
                GeminiFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = { })
            } else null
            AsrVendor.Soniox -> if (prefs.hasSonioxKeys()) {
                if (prefs.sonioxStreamingEnabled) {
                    SonioxStreamAsrEngine(context, serviceScope, prefs, this)
                } else {
                    SonioxFileAsrEngine(context, serviceScope, prefs, this, onRequestDuration = { })
                }
            } else null
            AsrVendor.SenseVoice -> {
                com.brycewg.asrkb.asr.SenseVoiceFileAsrEngine(context, serviceScope, prefs, this) {}
            }
        }
    }

    private fun startProcessingTimeout() {
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel previous timeout job", e)
        }
        processingTimeoutJob = serviceScope.launch {
            delay(PROCESSING_TIMEOUT_MS)
            if (!hasCommittedResult) {
                handleProcessingTimeout()
            }
        }
    }

    private suspend fun handleProcessingTimeout() {
        val candidate = lastPartialForPreview?.trim().orEmpty()
        Log.w(TAG, "Finalize timeout; fallback with preview='$candidate'")
        if (candidate.isEmpty()) {
            Log.w(TAG, "Fallback has no candidate text; only clear state")
            listener.onSessionStateChanged(FloatingBallState.Idle)
            return
        }

        var textOut = candidate
        if (prefs.trimFinalTrailingPunct) textOut = trimTrailingPunctuation(textOut)
        if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
            try {
                textOut = postproc.process(textOut, prefs).ifBlank { textOut }
            } catch (e: Throwable) {
                Log.e(TAG, "Post-processing failed in timeout fallback", e)
            }
            if (prefs.trimFinalTrailingPunct) textOut = trimTrailingPunctuation(textOut)
        }

        val success = insertTextToFocus(textOut)
        Log.d(TAG, "Fallback inserted=$success text='$textOut'")
        listener.onResultCommitted(textOut, success)
        hasCommittedResult = true

        listener.onSessionStateChanged(FloatingBallState.Idle)
        focusContext = null
        lastPartialForPreview = null
        markerInserted = false
        markerChar = null
    }

    private fun insertTextToFocus(text: String): Boolean {
        val ctx = focusContext ?: com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusContext()
        var toWrite = if (ctx != null) ctx.prefix + text + ctx.suffix else text
        toWrite = stripMarkersIfAny(toWrite)
        Log.d(TAG, "Inserting text: $toWrite (previewCtx=${ctx != null})")

        val pkg = com.brycewg.asrkb.ui.AsrAccessibilityService.getActiveWindowPackage()
        val isTg = pkg != null && isTelegramLikePackage(pkg)
        val writeCompat = try {
            prefs.floatingWriteTextCompatEnabled
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get write compat preference", e)
            true
        }
        val compatTarget = pkg != null && isPackageInCompatTargets(pkg)

        if (isTg && markerInserted) {
            toWrite = text
        }

        val wrote: Boolean = if (writeCompat && compatTarget) {
            val success = com.brycewg.asrkb.ui.AsrAccessibilityService.selectAllAndPasteSilent(toWrite)
            if (!success) {
                com.brycewg.asrkb.ui.AsrAccessibilityService.insertText(context, toWrite)
            } else {
                success
            }
        } else {
            com.brycewg.asrkb.ui.AsrAccessibilityService.insertText(context, toWrite)
        }

        if (wrote) {
            try {
                prefs.addAsrChars(text.length)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to add ASR chars", e)
            }
            val prefixLenForCursor = if (isTg && markerInserted) 0 else stripMarkersIfAny(ctx?.prefix ?: "").length
            val desiredCursor = (prefixLenForCursor + text.length).coerceAtLeast(0)
            com.brycewg.asrkb.ui.AsrAccessibilityService.setSelectionSilent(desiredCursor)
        }

        return wrote
    }

    private fun tryFixTelegramPlaceholderIfNeeded() {
        markerInserted = false
        markerChar = null
        val pkg = com.brycewg.asrkb.ui.AsrAccessibilityService.getActiveWindowPackage() ?: return
        val compat = try {
            prefs.floatingWriteTextCompatEnabled
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get write compat preference", e)
            true
        }
        if (!compat || !isTelegramLikePackage(pkg) || !isPackageInCompatTargets(pkg)) return

        val candidates = listOf("\u2060", "\u200B")
        for (m in candidates) {
            val ok = com.brycewg.asrkb.ui.AsrAccessibilityService.pasteTextSilent(m)
            if (ok) {
                markerInserted = true
                markerChar = m
                Log.d(TAG, "Telegram fix: injected marker ${Integer.toHexString(m.codePointAt(0))}")
                break
            }
        }
    }

    private fun stripMarkersIfAny(s: String): String {
        var out = s
        markerChar?.let { if (it.isNotEmpty()) out = out.replace(it, "") }
        out = out.replace("\u2060", "")
        out = out.replace("\u200B", "")
        return out
    }

    private fun isTelegramLikePackage(pkg: String): Boolean {
        if (pkg.startsWith("org.telegram")) return true
        if (pkg == "nu.gpu.nagram") return true
        return false
    }

    private fun isPackageInCompatTargets(pkg: String): Boolean {
        val raw = try {
            prefs.floatingWriteCompatPackages
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get compat packages", e)
            ""
        }
        val rules = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return rules.any { it == pkg }
    }

    private fun trimTrailingPunctuation(s: String): String {
        if (s.isEmpty()) return s
        val regex = Regex("[\\p{Punct}，。！？；、：]+$")
        return s.replace(regex, "")
    }
}
