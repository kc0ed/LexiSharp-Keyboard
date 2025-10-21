package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 sherpa-onnx OfflineRecognizer 的"伪流式"本地识别：
 * - 录音中每 ~200ms 投送一帧 PCM -> Float 到离线流，并调用 decode 获取当前转写，回调 onPartial。
 * - 停止录音后进行一次最终 decode 并回调 onFinal。
 * - 依赖 LocalModelOnnxBridge 以反射方式调用，避免编译期耦合。
 */
class LocalModelPseudoStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    companion object {
        private const val TAG = "LocalModelPseudoStreamAsrEngine"
    }

    private val running = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val svManager = SenseVoiceOnnxManager.getInstance()
    private var audioJob: Job? = null
    private var currentStream: Any? = null
    private val streamMutex = Mutex()
    private var lastDecodeUptimeMs: Long = 0L
    private var lastEmitUptimeMs: Long = 0L
    // 控制解码与预览的最小间隔（降低 JNI 频繁分配与主线程压力）
    private val decodeIntervalMs: Long = 400L
    private val emitIntervalMs: Long = 250L
    private var lastEmittedText: String? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    override val isRunning: Boolean
        get() = running.get()

    private var loadJob: Job? = null
    override fun start() {
        if (running.get()) return
        closing.set(false)
        // 快速权限/依赖校验（主线程上尽快返回）
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            listener.onError(context.getString(R.string.error_record_permission_denied))
            return
        }
        if (!svManager.isOnnxAvailable()) {
            listener.onError(context.getString(R.string.error_local_asr_not_ready))
            return
        }
        // 后台加载模型并创建 stream，避免阻塞主线程
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.Default) {
            try {
                val base = try { context.getExternalFilesDir(null) } catch (_: Throwable) { null } ?: context.filesDir
                val probeRoot = java.io.File(base, "sensevoice")
                val variant = try { prefs.svModelVariant } catch (_: Throwable) { "small-int8" }
                val variantDir = if (variant == "small-full") java.io.File(probeRoot, "small-full") else java.io.File(probeRoot, "small-int8")
                val auto = findSvModelDir(variantDir) ?: findSvModelDir(probeRoot)
                if (auto == null) {
                    listener.onError(context.getString(R.string.error_sensevoice_model_missing)); return@launch
                }
                val dir = auto.absolutePath
                val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
                val int8File = java.io.File(dir, "model.int8.onnx")
                val f32File = java.io.File(dir, "model.onnx")
                val modelFile = when {
                    int8File.exists() -> int8File
                    f32File.exists() -> f32File
                    else -> null
                }
                val modelPath = modelFile?.absolutePath
                val minBytes = 8L * 1024L * 1024L
                if (modelPath == null || !java.io.File(tokensPath).exists() || (modelFile?.length() ?: 0L) < minBytes) {
                    listener.onError(context.getString(R.string.error_sensevoice_model_missing)); return@launch
                }

                val keepMinutes = try { prefs.svKeepAliveMinutes } catch (_: Throwable) { -1 }
                val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
                val alwaysKeep = keepMinutes < 0
                if (closing.get()) return@launch

                Log.d(TAG, "Preparing SenseVoice model at $modelPath")
                val ok = svManager.prepare(
                    assetManager = null,
                    tokens = tokensPath,
                    model = modelPath,
                    language = try { prefs.svLanguage } catch (_: Throwable) { "auto" },
                    useItn = try { prefs.svUseItn } catch (_: Throwable) { false },
                    provider = "cpu",
                    numThreads = try { prefs.svNumThreads } catch (_: Throwable) { 2 },
                    keepAliveMs = keepMs,
                    alwaysKeep = alwaysKeep,
                    onLoadStart = { try { (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi)?.onLocalModelLoadStart() } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify load start", t)
                    } },
                    onLoadDone = { try { (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi)?.onLocalModelLoadDone() } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify load done", t)
                    } },
                )
                if (!ok || closing.get()) {
                    Log.w(TAG, "Model preparation failed or cancelled")
                    return@launch
                }

                Log.d(TAG, "Creating recognition stream")
                val stream = svManager.createStreamOrNull()
                if (stream == null || closing.get()) {
                    if (stream != null) try {
                        svManager.releaseStream(stream)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to release stream", t)
                    }
                    listener.onError(context.getString(R.string.error_local_asr_not_ready))
                    return@launch
                }
                currentStream = stream
                running.set(true)
                Log.d(TAG, "Starting audio capture and decode")
                startCaptureAndDecode(stream)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start engine: ${t.message}", t)
                try {
                    listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
                } catch (notifyError: Throwable) {
                    Log.e(TAG, "Failed to notify error", notifyError)
                }
            }
        }
    }

    override fun stop() {
        if (!running.get()) return
        Log.d(TAG, "Stopping engine")
        closing.set(true)
        running.set(false)
        audioJob?.cancel()
        audioJob = null
        val s = currentStream
        if (s != null) {
            // 在后台协程中完成最终解码与资源释放，避免在主线程阻塞
            scope.launch(Dispatchers.Default) {
                try {
                    val finalText = streamMutex.withLock { svManager.decodeAndGetText(s) }
                    Log.d(TAG, "Final result length: ${finalText?.length ?: 0}")
                    try {
                        if (!finalText.isNullOrBlank()) {
                            listener.onFinal(finalText.trim())
                        } else {
                            listener.onFinal("")
                        }
                    } catch (notifyError: Throwable) {
                        Log.e(TAG, "Failed to notify final result", notifyError)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to get final result", t)
                    try { listener.onFinal("") } catch (_: Throwable) { /* ignore */ }
                } finally {
                    try {
                        streamMutex.withLock { svManager.releaseStream(s) }
                        Log.d(TAG, "Released recognition stream")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to release stream", t)
                    }
                    currentStream = null
                    closing.set(false)
                }
            }
        }
    }

    /**
     * 启动音频采集并进行实时解码
     *
     * 使用 AudioCaptureManager 简化音频采集逻辑，包括：
     * - 权限检查和 AudioRecord 初始化
     * - 音频源回退（VOICE_RECOGNITION -> MIC）
     * - 预热逻辑（兼容模式 vs 非兼容模式）
     */
    private fun startCaptureAndDecode(stream: Any) {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            // 将帧间隔从 ~200ms 缩小到 ~120ms，以降低预览延迟
            val targetChunkMs = 120
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                chunkMillis = targetChunkMs,
                prefs = prefs
            )

            if (!audioManager.hasPermission()) {
                Log.e(TAG, "Missing RECORD_AUDIO permission")
                listener.onError(context.getString(R.string.error_record_permission_denied))
                running.set(false)
                return@launch
            }

            val silenceDetector = if (prefs.autoStopOnSilenceEnabled)
                SilenceDetector(sampleRate, prefs.autoStopSilenceWindowMs, prefs.autoStopSilenceSensitivity)
            else null

            try {
                Log.d(TAG, "Starting audio capture with chunk size ${targetChunkMs}ms")
                audioManager.startCapture().collect { audioChunk ->
                    if (!running.get()) return@collect

                    // 静音自动判停
                    if (silenceDetector?.shouldStop(audioChunk, audioChunk.size) == true) {
                        Log.d(TAG, "Silence detected, stopping recording")
                        try { listener.onStopped() } catch (t: Throwable) {
                            Log.e(TAG, "Failed to notify stopped", t)
                        }
                        stop()
                        return@collect
                    }

                    // 投送音频块到离线流并尝试解码
                    deliverChunk(stream, audioChunk, audioChunk.size)
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Audio streaming cancelled: ${t.message}")
                } else {
                    Log.e(TAG, "Audio streaming failed: ${t.message}", t)
                    listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
                }
            }
        }
    }

    private suspend fun deliverChunk(stream: Any, bytes: ByteArray, len: Int) {
        if (!running.get() || closing.get()) return
        if (currentStream !== stream) return
        val floats = pcmToFloatArray(bytes, len)
        if (floats.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        var text: String? = null
        try {
            streamMutex.withLock {
                if (!running.get() || closing.get()) return
                if (currentStream !== stream) return
                try {
                    svManager.acceptWaveform(stream, floats, sampleRate)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to accept waveform", t)
                }
                val shouldDecode = closing.get() || (now - lastDecodeUptimeMs) >= decodeIntervalMs
                if (shouldDecode) {
                    text = try {
                        svManager.decodeAndGetText(stream)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to decode text", t)
                        null
                    }
                    lastDecodeUptimeMs = now
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in deliverChunk", t)
        }
        if (!text.isNullOrBlank() && running.get() && !closing.get()) {
            val trimmed = text!!.trim()
            val needEmit = (now - lastEmitUptimeMs) >= emitIntervalMs && trimmed != lastEmittedText
            if (needEmit) {
                try {
                    listener.onPartial(trimmed)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to notify partial result", t)
                }
                lastEmitUptimeMs = now
                lastEmittedText = trimmed
            }
        }
    }

    private fun pcmToFloatArray(src: ByteArray, len: Int): FloatArray {
        if (len <= 1) return FloatArray(0)
        val n = len / 2
        val out = FloatArray(n)
        val bb = ByteBuffer.wrap(src, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < n) {
            val s = bb.short.toInt()
            var f = s / 32768.0f
            if (f > 1f) f = 1f else if (f < -1f) f = -1f
            out[i] = f
            i++
        }
        return out
    }
}
