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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Soniox WebSocket 实时 ASR 引擎实现。
 * 连接后首包发送 JSON 配置（含 api_key + 音频参数），随后流式发送 PCM 二进制帧；
 * 服务端返回 tokens 队列，已最终的 tokens 追加缓存，非最终 tokens 用于增量预览。
 */
class SonioxStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private var ws: WebSocket? = null
    private var audioJob: Job? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val finalTextBuffer = StringBuilder()

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        // 权限校验
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            listener.onError(context.getString(R.string.error_record_permission_denied))
            return
        }
        if (prefs.sonioxApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_soniox_key))
            return
        }
        running.set(true)
        openWebSocketAndStartAudio()
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        try { ws?.send("") } catch (_: Throwable) { }
        scope.launch(Dispatchers.IO) {
            delay(150)
            try { ws?.close(1000, "stop") } catch (_: Throwable) { }
            ws = null
        }
        audioJob?.cancel()
        audioJob = null
    }

    private fun openWebSocketAndStartAudio() {
        finalTextBuffer.setLength(0)
        val req = Request.Builder()
            .url(Prefs.SONIOX_WS_URL)
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    // 发送配置
                    val config = buildConfigJson()
                    webSocket.send(config)
                    // 启动录音并发送音频
                    startCaptureAndSendAudio(webSocket)
                } catch (t: Throwable) {
                    listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
                    stop()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Soniox 文档为文本 JSON 响应；此处保底兼容
                try { handleMessage(bytes.utf8()) } catch (_: Throwable) { }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (running.get()) listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // no-op
            }
        })
    }

    private fun startCaptureAndSendAudio(webSocket: WebSocket) {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val readSize = maxOf(minBuffer, (sampleRate / 5) * 2) // ~200ms
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    readSize
                )
            } catch (t: Throwable) {
                listener.onError(context.getString(R.string.error_audio_init_cannot, t.message ?: ""))
                stop(); return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError(context.getString(R.string.error_audio_init_failed))
                stop(); return@launch
            }
            try {
                recorder.startRecording()
                val buf = ByteArray(readSize)
                while (running.get()) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) {
                        webSocket.send(ByteString.of(*buf.copyOfRange(0, n)))
                    }
                }
            } catch (t: Throwable) {
                listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
            } finally {
                try { recorder.stop() } catch (_: Throwable) {}
                try { recorder.release() } catch (_: Throwable) {}
            }
        }
    }

    private fun buildConfigJson(): String {
        val o = JSONObject().apply {
            put("api_key", prefs.sonioxApiKey)
            put("model", "stt-rt-preview")
            // 原始 PCM；若改为 auto，可不传采样率/通道
            put("audio_format", "pcm_s16le")
            put("sample_rate", sampleRate)
            put("num_channels", 1)
            // 辅助能力（尽量低延迟）
            put("enable_endpoint_detection", true)
            put("enable_language_identification", true)
            // put("enable_speaker_diarization", true) // 如需说话人区分
        }
        return o.toString()
    }

    private fun handleMessage(json: String) {
        try {
            val o = JSONObject(json)
            // 错误响应
            if (o.has("error_code")) {
                val code = o.optInt("error_code")
                val msg = o.optString("error_message")
                listener.onError("ASR Error $code: $msg")
                return
            }

            val tokens = o.optJSONArray("tokens")
            val nonFinal = StringBuilder()
            if (tokens != null) {
                for (i in 0 until tokens.length()) {
                    val t = tokens.optJSONObject(i) ?: continue
                    val text = t.optString("text")
                    if (text.isEmpty()) continue
                    val isFinal = t.optBoolean("is_final", false)
                    if (isFinal) {
                        finalTextBuffer.append(text)
                    } else {
                        nonFinal.append(text)
                    }
                }
            }

            val preview = finalTextBuffer.toString() + nonFinal.toString()
            if (preview.isNotEmpty()) listener.onPartial(preview)

            val finished = o.optBoolean("finished", false)
            if (finished) {
                val finalText = finalTextBuffer.toString().ifBlank { preview }
                if (finalText.isNotBlank()) listener.onFinal(finalText)
                running.set(false)
            }
        } catch (_: Throwable) {
            // ignore malformed
        }
    }
}

