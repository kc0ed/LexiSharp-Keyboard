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
 * Soniox 异步文件 ASR 引擎实现。
 * 录音 -> WAV -> 上传 /v1/files -> 创建转写 /v1/transcriptions -> 轮询完成 -> 拉取转写文本。
 */
class SonioxFileAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    private val onRequestDuration: ((Long) -> Unit)? = null
) : StreamingAsrEngine {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
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

            val apiKey = prefs.sonioxApiKey
            if (apiKey.isBlank()) {
                listener.onError(context.getString(R.string.error_missing_soniox_key))
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
                // 首次探测：若无有效音频，回退 MIC
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
                // 软限制最大约 30 分钟
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
                val tmp = File.createTempFile("asr_soniox_", ".wav", context.cacheDir)
                FileOutputStream(tmp).use { it.write(wav) }

                val t0 = System.nanoTime()
                // 1) 上传文件 -> 返回 file_id
                val fileId = uploadAudioFile(apiKey, tmp)
                // 2) 创建转写 -> 返回 transcription_id
                val transcriptionId = createTranscription(apiKey, fileId)
                // 3) 轮询直到完成
                waitUntilCompleted(apiKey, transcriptionId)
                // 4) 获取转写结果文本
                val text = getTranscriptionText(apiKey, transcriptionId)

                if (text.isNotBlank()) {
                    val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                    try { onRequestDuration?.invoke(dt) } catch (_: Throwable) {}
                    listener.onFinal(text)
                } else {
                    listener.onError(context.getString(R.string.error_asr_empty_result))
                }
            } catch (t: Throwable) {
                listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
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

    private fun uploadAudioFile(apiKey: String, file: File): String {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("audio/wav".toMediaType())
            )
            .build()
        val req = Request.Builder()
            .url(Prefs.SONIOX_FILES_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipart)
            .build()
        val resp = http.newCall(req).execute()
        resp.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val detail = formatHttpDetail(r.message, extractErrorHint(body))
                throw RuntimeException(context.getString(R.string.error_request_failed_http, r.code, detail))
            }
            val id = try { JSONObject(body).optString("id").trim() } catch (_: Throwable) { "" }
            if (id.isBlank()) throw RuntimeException("uploadAudio: empty file id")
            return id
        }
    }

    private fun createTranscription(apiKey: String, fileId: String): String {
        val cfg = JSONObject().apply {
            put("file_id", fileId)
            put("model", "stt-async-preview")
            // 可选能力：语言提示/语言识别/说话人分离等
            put("enable_language_identification", true)
            val langs = prefs.getSonioxLanguages()
            if (langs.isNotEmpty()) {
                val arr = org.json.JSONArray()
                langs.forEach { arr.put(it) }
                put("language_hints", arr)
            }
        }
        val req = Request.Builder()
            .url(Prefs.SONIOX_TRANSCRIPTIONS_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .post(cfg.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val resp = http.newCall(req).execute()
        resp.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val detail = formatHttpDetail(r.message, extractErrorHint(body))
                throw RuntimeException(context.getString(R.string.error_request_failed_http, r.code, detail))
            }
            val id = try { JSONObject(body).optString("id").trim() } catch (_: Throwable) { "" }
            if (id.isBlank()) throw RuntimeException("createTranscription: empty id")
            return id
        }
    }

    private suspend fun waitUntilCompleted(apiKey: String, transcriptionId: String) {
        while (true) {
            val req = Request.Builder()
                .url(Prefs.SONIOX_TRANSCRIPTIONS_ENDPOINT + "/" + transcriptionId)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val resp = http.newCall(req).execute()
            resp.use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val detail = formatHttpDetail(r.message, extractErrorHint(body))
                    throw RuntimeException(context.getString(R.string.error_request_failed_http, r.code, detail))
                }
                val status = try { JSONObject(body).optString("status").lowercase() } catch (_: Throwable) { "" }
                when (status) {
                    "completed" -> return
                    "error" -> {
                        val err = try { JSONObject(body).optString("error_message") } catch (_: Throwable) { "" }
                        throw RuntimeException("Soniox error: $err")
                    }
                }
            }
            // 1 秒轮询
            delay(1000)
        }
    }

    private fun getTranscriptionText(apiKey: String, transcriptionId: String): String {
        val req = Request.Builder()
            .url(Prefs.SONIOX_TRANSCRIPTIONS_ENDPOINT + "/" + transcriptionId + "/transcript")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
        val resp = http.newCall(req).execute()
        resp.use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val detail = formatHttpDetail(r.message, extractErrorHint(body))
                throw RuntimeException(context.getString(R.string.error_request_failed_http, r.code, detail))
            }
            return parseTokensToText(body)
        }
    }

    private fun extractErrorHint(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val o = JSONObject(body)
            when {
                o.has("error_message") -> o.optString("error_message").trim()
                o.has("message") -> o.optString("message").trim()
                else -> body.take(200).trim()
            }
        } catch (_: Throwable) {
            body.take(200).trim()
        }
    }

    private fun parseTokensToText(body: String): String {
        return try {
            val o = JSONObject(body)
            val arr = o.optJSONArray("tokens") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val text = t.optString("text")
                if (text.isNotEmpty()) sb.append(text)
            }
            sb.toString().trim()
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
