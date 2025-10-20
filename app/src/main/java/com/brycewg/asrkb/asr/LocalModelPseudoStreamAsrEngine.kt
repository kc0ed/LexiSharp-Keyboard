package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 sherpa-onnx OfflineRecognizer 的“伪流式”本地识别：
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

    private val running = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private var audioJob: Job? = null
    private var currentStream: Any? = null
    private val streamLock = Any()
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
        if (!SenseVoiceOnnxBridge.isOnnxAvailable()) {
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

                val ok = SenseVoiceOnnxBridge.prepare(
                    assetManager = null,
                    tokens = tokensPath,
                    model = modelPath,
                    language = try { prefs.svLanguage } catch (_: Throwable) { "auto" },
                    useItn = try { prefs.svUseItn } catch (_: Throwable) { false },
                    provider = "cpu",
                    numThreads = try { prefs.svNumThreads } catch (_: Throwable) { 2 },
                    keepAliveMs = keepMs,
                    alwaysKeep = alwaysKeep,
                    onLoadStart = { try { (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi)?.onLocalModelLoadStart() } catch (_: Throwable) { } },
                    onLoadDone = { try { (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi)?.onLocalModelLoadDone() } catch (_: Throwable) { } },
                )
                if (!ok || closing.get()) return@launch

                val stream = SenseVoiceOnnxBridge.createStreamOrNull()
                if (stream == null || closing.get()) {
                    if (stream != null) try { SenseVoiceOnnxBridge.releaseStream(stream) } catch (_: Throwable) { }
                    listener.onError(context.getString(R.string.error_local_asr_not_ready))
                    return@launch
                }
                currentStream = stream
                running.set(true)
                startCaptureAndDecode(stream)
            } catch (t: Throwable) {
                try { listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")) } catch (_: Throwable) { }
            }
        }
    }

    override fun stop() {
        if (!running.get()) return
        closing.set(true)
        running.set(false)
        audioJob?.cancel()
        audioJob = null
        val s = currentStream
        if (s != null) {
            try {
                // 最后一轮 decode 获取最终文本
                val finalText = synchronized(streamLock) {
                    // 若 deliverChunk 仍在解码，此处等待其完成，随后执行最终解码
                    SenseVoiceOnnxBridge.decodeAndGetText(s)
                }
                if (finalText != null) listener.onFinal(finalText.trim()) else listener.onFinal("")
            } catch (_: Throwable) {
                // ignore
            } finally {
                synchronized(streamLock) {
                    try { SenseVoiceOnnxBridge.releaseStream(s) } catch (_: Throwable) { }
                }
                currentStream = null
                closing.set(false)
            }
        }
    }

    private fun startCaptureAndDecode(stream: Any) {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            // 双重校验录音权限
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                listener.onError(context.getString(R.string.error_record_permission_denied))
                running.set(false)
                return@launch
            }

            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            // 将帧间隔从 ~200ms 缩小到 ~120ms，以降低预览延迟
            val targetChunkMs = 120
            val frameBytes = ((sampleRate * targetChunkMs) / 1000) * 2 // 16-bit 单声道
            val bufferSize = maxOf(minBuffer, frameBytes)
            var recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (_: Throwable) { null }
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                try { recorder?.release() } catch (_: Throwable) {}
                recorder = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                } catch (_: Throwable) { null }
                if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                    listener.onError(context.getString(R.string.error_audio_init_failed))
                    stop(); return@launch
                }
            }

            // 至此 recorder 非空且已初始化，使用非空局部变量 rec 以规避智能转换问题
            var rec: AudioRecord = recorder!!

            try {
                rec.startRecording()
            } catch (_: SecurityException) {
                listener.onError(context.getString(R.string.error_record_permission_denied))
                stop(); return@launch
            } catch (t: Throwable) {
                listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
                stop(); return@launch
            }

            val buf = ByteArray(frameBytes)
            val silence = if (prefs.autoStopOnSilenceEnabled)
                SilenceDetector(sampleRate, prefs.autoStopSilenceWindowMs, prefs.autoStopSilenceSensitivity)
            else null

            // 预热（与其他流式实现保持一致）
            run {
                val compat = try { prefs.audioCompatPreferMic } catch (_: Throwable) { false }
                if (compat) {
                    val warmupFrames = 3
                    repeat(warmupFrames) {
                        val n = try { rec.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
                        if (n > 0) deliverChunk(stream, buf, n)
                    }
                } else {
                    val pre = try { rec.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
                    val hasSignal = pre > 0 && hasNonZeroAmplitude(buf, pre)
                    if (pre > 0) deliverChunk(stream, buf, pre)
                    if (!hasSignal) {
                        try { rec.stop() } catch (_: Throwable) {}
                        try { rec.release() } catch (_: Throwable) {}
                        val alt = try {
                            AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                sampleRate,
                                channelConfig,
                                audioFormat,
                                bufferSize
                            )
                        } catch (_: Throwable) { null }
                        if (alt == null || alt.state != AudioRecord.STATE_INITIALIZED) {
                            listener.onError(context.getString(R.string.error_audio_init_failed))
                            stop(); return@launch
                        }
                        rec = alt
                        try { rec.startRecording() } catch (_: Throwable) { }
                        val pre2 = try { rec.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
                        if (pre2 > 0) deliverChunk(stream, buf, pre2)
                    }
                }
            }

            // 主循环：采集 -> 推入 -> 解码 -> 输出预览
            while (running.get()) {
                val n = try { rec.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
                if (n > 0) {
                    if (silence?.shouldStop(buf, n) == true) {
                        try { listener.onStopped() } catch (_: Throwable) { }
                        stop(); break
                    }
                    deliverChunk(stream, buf, n)
                }
            }

            try { rec.stop() } catch (_: Throwable) {}
            try { rec.release() } catch (_: Throwable) {}
        }
    }

    private fun deliverChunk(stream: Any, bytes: ByteArray, len: Int) {
        if (!running.get() || closing.get()) return
        if (currentStream !== stream) return
        val floats = pcmToFloatArray(bytes, len)
        if (floats.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        var text: String? = null
        try {
            synchronized(streamLock) {
                if (!running.get() || closing.get()) return
                if (currentStream !== stream) return
                try { SenseVoiceOnnxBridge.acceptWaveform(stream, floats, sampleRate) } catch (_: Throwable) { }
                val shouldDecode = closing.get() || (now - lastDecodeUptimeMs) >= decodeIntervalMs
                if (shouldDecode) {
                    text = try { SenseVoiceOnnxBridge.decodeAndGetText(stream) } catch (_: Throwable) { null }
                    lastDecodeUptimeMs = now
                }
            }
        } catch (_: Throwable) { }
        if (!text.isNullOrBlank() && running.get() && !closing.get()) {
            val trimmed = text!!.trim()
            val needEmit = (now - lastEmitUptimeMs) >= emitIntervalMs && trimmed != lastEmittedText
            if (needEmit) {
                try { listener.onPartial(trimmed) } catch (_: Throwable) { }
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
