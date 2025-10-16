package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import android.util.Base64
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 使用 SiliconFlow "audio/transcriptions" API 的非流式 ASR 引擎。
 * 行为：start() 开始录制 PCM；stop() 完成并上传一个请求；仅调用 onFinal。
 */
class SiliconFlowFileAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    private val onRequestDuration: ((Long) -> Unit)? = null
) : StreamingAsrEngine {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private var running = AtomicBoolean(false)
    private var audioJob: Job? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        running.set(true)
        startRecordThenRecognize()
    }

    override fun stop() {
        running.set(false)
    }

    private fun startRecordThenRecognize() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
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
            val chunkBytes = ((sampleRate / 5) * 2)
            val bufferSize = maxOf(minBuffer, chunkBytes)
            var recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (t: Throwable) {
                listener.onError(context.getString(R.string.error_audio_init_cannot, t.message ?: ""))
                running.set(false)
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                try { recorder.release() } catch (_: Throwable) {}
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
                    running.set(false)
                    return@launch
                }
                recorder = alt
            }

            val pcmBuffer = ByteArrayOutputStream()
            try {
                recorder.startRecording()
                val buf = ByteArray(chunkBytes)
                // 首次探测：若读不到有效音频，回退到 MIC
                run {
                    val pre = try { recorder.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
                    val hasSignal = pre > 0 && hasNonZeroAmplitude(buf, pre)
                    if (!hasSignal) {
                        try { recorder.stop() } catch (_: Throwable) {}
                        try { recorder.release() } catch (_: Throwable) {}
                        val alt = try {
                            AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                sampleRate,
                                channelConfig,
                                audioFormat,
                                bufferSize
                            )
                        } catch (_: Throwable) { null }
                        if (alt != null && alt.state == AudioRecord.STATE_INITIALIZED) {
                            recorder = alt
                            try { recorder.startRecording() } catch (_: Throwable) { }
                            val pre2 = try { recorder.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
                            if (pre2 > 0) pcmBuffer.write(buf, 0, pre2)
                        } else {
                            listener.onError(context.getString(R.string.error_audio_init_failed))
                            running.set(false)
                            return@launch
                        }
                    } else if (pre > 0) {
                        pcmBuffer.write(buf, 0, pre)
                    }
                }
                val maxBytes = 30 * 60 * sampleRate * 2
                val silence = if (prefs.autoStopOnSilenceEnabled)
                    SilenceDetector(sampleRate, prefs.autoStopSilenceWindowMs, prefs.autoStopSilenceSensitivity)
                else null
                while (true) {
                    if (!running.get()) break
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        pcmBuffer.write(buf, 0, read)
                        if (silence?.shouldStop(buf, read) == true) {
                            running.set(false)
                            try { listener.onStopped() } catch (_: Throwable) {}
                            break
                        }
                        if (pcmBuffer.size() >= maxBytes) break
                    }
                }
            } catch (t: Throwable) {
                listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
            } finally {
                try { recorder.stop() } catch (_: Throwable) {}
                try { recorder.release() } catch (_: Throwable) {}
            }

            val pcmBytes = pcmBuffer.toByteArray()
            if (pcmBytes.isEmpty()) {
                listener.onError(context.getString(R.string.error_audio_empty))
                return@launch
            }

            try {
                val wav = pcmToWav(pcmBytes)
                val apiKey = prefs.sfApiKey
                if (apiKey.isBlank()) {
                    listener.onError(context.getString(R.string.error_missing_siliconflow_key))
                    return@launch
                }

                val t0 = System.nanoTime()
                if (prefs.sfUseOmni) {
                    // 使用多模态 chat/completions，将音频以 data:// base64 形式内联
                    val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
                    val model = run {
                        val cur = prefs.sfModel
                        if (cur == Prefs.DEFAULT_SF_MODEL) Prefs.DEFAULT_SF_OMNI_MODEL else cur
                    }
                    val prompt = prefs.sfOmniPrompt.ifBlank { Prefs.DEFAULT_SF_OMNI_PROMPT }

                    val body = buildSfChatCompletionsBody(model, b64, prompt)
                    val request = Request.Builder()
                        .url(Prefs.SF_CHAT_COMPLETIONS_ENDPOINT)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json; charset=utf-8")
                        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    val resp = http.newCall(request).execute()
                    resp.use { r ->
                        val str = r.body?.string().orEmpty()
                        if (!r.isSuccessful) {
                            val msg = r.message
                            val detail = formatHttpDetail(msg, null)
                            listener.onError(
                                context.getString(R.string.error_request_failed_http, r.code, detail)
                            )
                            return@use
                        }
                        val text = parseSfChatText(str)
                        if (text.isNotBlank()) {
                            val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                            try { onRequestDuration?.invoke(dt) } catch (_: Throwable) {}
                            listener.onFinal(text)
                        } else {
                            listener.onError(context.getString(R.string.error_asr_empty_result))
                        }
                    }
                } else {
                    // 传统 /audio/transcriptions 接口（multipart）
                    val tmp = File.createTempFile("asr_", ".wav", context.cacheDir)
                    FileOutputStream(tmp).use { it.write(wav) }
                    val model = prefs.sfModel
                    val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("model", model)
                        .addFormDataPart(
                            "file",
                            "audio.wav",
                            tmp.asRequestBody("audio/wav".toMediaType())
                        )
                        .build()

                    val request = Request.Builder()
                        .url(Prefs.SF_ENDPOINT)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .post(multipart)
                        .build()

                    val resp = http.newCall(request).execute()
                    resp.use { r ->
                        if (!r.isSuccessful) {
                            val msg = r.message
                            val detail = formatHttpDetail(msg, null)
                            listener.onError(
                                context.getString(R.string.error_request_failed_http, r.code, detail)
                            )
                            return@use
                        }
                        val bodyStr = r.body?.string() ?: ""
                        val text = try {
                            val obj = JSONObject(bodyStr)
                            obj.optString("text", "")
                        } catch (_: Throwable) { "" }
                        if (text.isNotBlank()) {
                            val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                            try { onRequestDuration?.invoke(dt) } catch (_: Throwable) {}
                            listener.onFinal(text)
                        } else {
                            listener.onError(context.getString(R.string.error_asr_empty_result))
                        }
                    }
                }
            } catch (t: Throwable) {
                listener.onError(
                    context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
                )
            }
        }
    }

    private fun hasNonZeroAmplitude(buf: ByteArray, len: Int): Boolean {
        var i = 0
        while (i + 1 < len) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt() and 0xFF
            val s = (hi shl 8) or lo
            val v = if (s < 0x8000) s else s - 0x10000
            if (kotlin.math.abs(v) > 30) return true
            i += 2
        }
        return false
    }

    private fun buildSfChatCompletionsBody(model: String, base64Wav: String, prompt: String): String {
        // SiliconFlow 多模态消息格式：messages[0].content 中包含 audio_url + text
        val audioPart = JSONObject().apply {
            put("type", "audio_url")
            put("audio_url", JSONObject().apply {
                put("url", "data:audio/wav;base64,$base64Wav")
            })
        }
        val textPart = JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        }
        val user = JSONObject().apply {
            put("role", "user")
            put("content", org.json.JSONArray().apply {
                put(audioPart)
                put(textPart)
            })
        }
        val root = JSONObject().apply {
            put("model", model)
            put("messages", org.json.JSONArray().apply { put(user) })
        }
        return root.toString()
    }

    private fun parseSfChatText(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val o = JSONObject(body)
            val arr = o.optJSONArray("choices") ?: return ""
            if (arr.length() == 0) return ""
            val c0 = arr.optJSONObject(0) ?: return ""
            val msg = c0.optJSONObject("message") ?: return ""
            // 优先 content 字符串；若为数组则拼接文本片段
            val contentAny = msg.opt("content")
            when (contentAny) {
                is String -> contentAny.trim()
                is org.json.JSONArray -> {
                    val sb = StringBuilder()
                    for (i in 0 until contentAny.length()) {
                        val part = contentAny.optJSONObject(i) ?: continue
                        val type = part.optString("type")
                        if (type == "text" || type == "output_text") {
                            val t = part.optString("text").ifBlank { part.optString("content") }
                            if (t.isNotBlank()) sb.append(t)
                        }
                    }
                    sb.toString().trim()
                }
                else -> ""
            }
        } catch (_: Throwable) { "" }
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val headerSize = 44
        val dataSize = pcm.size
        val totalDataLen = dataSize + 36
        val out = ByteArrayOutputStream(headerSize + dataSize)
        out.write("RIFF".toByteArray())
        out.write(intToBytesLE(totalDataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToBytesLE(16))
        out.write(shortToBytesLE(1))
        out.write(shortToBytesLE(channels))
        out.write(intToBytesLE(sampleRate))
        out.write(intToBytesLE(byteRate))
        out.write(shortToBytesLE((channels * bitsPerSample / 8)))
        out.write(shortToBytesLE(bitsPerSample))
        out.write("data".toByteArray())
        out.write(intToBytesLE(dataSize))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intToBytesLE(v: Int): ByteArray {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(v)
        return bb.array()
    }

    private fun shortToBytesLE(v: Int): ByteArray {
        val bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(v.toShort())
        return bb.array()
    }
}
