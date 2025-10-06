package com.example.asrkeyboard.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.example.asrkeyboard.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Base64

/**
 * Non-streaming ASR engine using Volcengine "recognize/flash" API.
 * Behavior: start() begins recording PCM; stop() finalizes and uploads one request; only calls onFinal.
 */
class VolcFileAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener
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
        // Flip running flag; job will exit its loop and continue to upload
        running.set(false)
    }

    private fun startRecordThenRecognize() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            // Permission check
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                listener.onError("录音权限未授予")
                running.set(false)
                return@launch
            }

            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            // Read ~200ms per chunk
            val chunkBytes = ((sampleRate / 5) * 2)
            val bufferSize = maxOf(minBuffer, chunkBytes)
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (t: Throwable) {
                listener.onError("无法初始化录音: ${t.message}")
                running.set(false)
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError("录音初始化失败")
                running.set(false)
                return@launch
            }

            val pcmBuffer = ByteArrayOutputStream()
            try {
                recorder.startRecording()
                val buf = ByteArray(chunkBytes)
                // Soft cap to avoid excessive memory in case of very long holds (~5 minutes max)
                val maxBytes = 5 * 60 * sampleRate * 2
                while (true) {
                    if (!running.get()) break
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        pcmBuffer.write(buf, 0, read)
                        if (pcmBuffer.size() >= maxBytes) {
                            // Auto stop if exceeding cap
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                listener.onError("录音错误: ${t.message}")
            } finally {
                try { recorder.stop() } catch (_: Throwable) {}
                try { recorder.release() } catch (_: Throwable) {}
            }

            // Prepare audio and upload if we have data
            val pcmBytes = pcmBuffer.toByteArray()
            if (pcmBytes.isEmpty()) {
                listener.onError("空音频")
                return@launch
            }

            try {
                val wav = pcmToWav(pcmBytes, sampleRate, 1, 16)
                val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
                val json = buildRequestJson(b64)
                val reqBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val resourceId = selectFileResourceId(prefs.resourceId)
                val request = Request.Builder()
                    .url(FILE_ENDPOINT)
                    .addHeader("X-Api-App-Key", prefs.appKey)
                    .addHeader("X-Api-Access-Key", prefs.accessKey)
                    .addHeader("X-Api-Resource-Id", resourceId)
                    .addHeader("X-Api-Request-Id", UUID.randomUUID().toString())
                    .addHeader("X-Api-Sequence", "-1")
                    .post(reqBody)
                    .build()
                val resp = http.newCall(request).execute()
                resp.use { r ->
                    if (!r.isSuccessful) {
                        val msg = r.header("X-Api-Message") ?: r.message
                        listener.onError("识别请求失败: HTTP ${r.code}${if (msg.isNullOrBlank()) "" else ": $msg"}")
                        return@launch
                    }
                    val bodyStr = r.body?.string() ?: ""
                    val text = try {
                        val obj = JSONObject(bodyStr)
                        if (obj.has("result")) {
                            obj.getJSONObject("result").optString("text", "")
                        } else ""
                    } catch (_: Throwable) { "" }
                    if (text.isNotBlank()) {
                        listener.onFinal(text)
                    } else {
                        listener.onError("识别返回为空")
                    }
                }
            } catch (t: Throwable) {
                listener.onError("识别失败: ${t.message}")
            }
        }
    }

    private fun buildRequestJson(base64Audio: String): String {
        val user = JSONObject().apply {
            put("uid", prefs.appKey)
        }
        val audio = JSONObject().apply {
            put("data", base64Audio)
        }
        val request = JSONObject().apply {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
        }
        val root = JSONObject().apply {
            put("user", user)
            put("audio", audio)
            put("request", request)
        }
        return root.toString()
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val headerSize = 44
        val dataSize = pcm.size
        val totalDataLen = dataSize + 36
        val out = ByteArrayOutputStream(headerSize + dataSize)
        // RIFF header
        out.write("RIFF".toByteArray())
        out.write(intToBytesLE(totalDataLen))
        out.write("WAVE".toByteArray())
        // fmt subchunk
        out.write("fmt ".toByteArray())
        out.write(intToBytesLE(16)) // Subchunk1Size for PCM
        out.write(shortToBytesLE(1)) // AudioFormat = 1 (PCM)
        out.write(shortToBytesLE(channels))
        out.write(intToBytesLE(sampleRate))
        out.write(intToBytesLE(byteRate))
        out.write(shortToBytesLE((channels * bitsPerSample / 8))) // BlockAlign
        out.write(shortToBytesLE(bitsPerSample))
        // data subchunk
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

    companion object {
        private const val FILE_ENDPOINT = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
        private const val DEFAULT_FILE_RESOURCE = "volc.bigasr.auc_turbo"
    }

    private fun selectFileResourceId(current: String): String {
        val c = current.trim()
        if (c.isEmpty()) return DEFAULT_FILE_RESOURCE
        val lower = c.lowercase()
        return if (lower.contains("sauc") || lower == Prefs.DEFAULT_RESOURCE.lowercase()) DEFAULT_FILE_RESOURCE else c
    }

    
}
