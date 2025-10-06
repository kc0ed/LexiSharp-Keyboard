package com.example.asrkeyboard.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.asrkeyboard.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.concurrent.TimeUnit

class VolcStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener
) : StreamingAsrEngine {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var audioJob: Job? = null
    private var running = AtomicBoolean(false) // user session is active

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        running.set(true)
        openWebSocketAndStart()
    }

    override fun stop() {
        running.set(false)
        // send last empty packet to flush
        try {
            val finalFrame = buildFrame(
                messageType = MSG_TYPE_AUDIO_ONLY,
                flags = FLAGS_LAST_NOSEQ,
                serialization = SERIAL_NONE,
                payload = byteArrayOf()
            )
            webSocket?.send(finalFrame.toByteString())
        } catch (_: Throwable) {}
        audioJob?.cancel()
        audioJob = null
        try { webSocket?.close(1000, "stop") } catch (_: Throwable) {}
        webSocket = null
    }

    private fun openWebSocketAndStart() {
        val connectId = prefs.connectId.ifBlank { UUID.randomUUID().toString() }.also { prefs.connectId = it }

        val request = Request.Builder()
            .url(prefs.endpoint)
            .addHeader("X-Api-App-Key", prefs.appKey)
            .addHeader("X-Api-Access-Key", prefs.accessKey)
            .addHeader("X-Api-Resource-Id", prefs.resourceId)
            .addHeader("X-Api-Connect-Id", connectId)
            .build()

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@VolcStreamAsrEngine.webSocket = webSocket
                // Send full client request first
                try {
                    val fullJson = buildFullClientRequestJson()
                    val payload = gzip(fullJson.toByteArray())
                    val frame = buildFrame(
                        messageType = MSG_TYPE_FULL_CLIENT_REQ,
                        flags = FLAGS_NOSEQ,
                        serialization = SERIAL_JSON,
                        payload = payload,
                        alreadyCompressed = true
                    )
                    webSocket.send(frame.toByteString())
                    // Start audio capture
                    startAudioLoop()
                } catch (t: Throwable) {
                    listener.onError("ASR启动失败: ${t.message}")
                    stop()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    handleServerFrame(bytes.toByteArray())
                } catch (t: Throwable) {
                    listener.onError("解析返回失败: ${t.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError("连接失败: ${t.message}")
                stop()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Server closing; keep running if in continuous mode; session restarts after final handling.
            }
        }

        client.newWebSocket(request, wsListener)
    }

    private fun startAudioLoop() {
        audioJob = scope.launch(Dispatchers.IO) {
            // Ensure RECORD_AUDIO permission is granted before accessing the mic
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                listener.onError("录音权限未授予")
                stop()
                return@launch
            }

            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val chunkBytes = ((sampleRate / 5) * 2) // 200ms -> 3200 samples -> 6400 bytes
            val bufferSize = maxOf(minBuffer, chunkBytes)
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (se: SecurityException) {
                listener.onError("无法访问麦克风: ${se.message}")
                stop()
                return@launch
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError("录音初始化失败")
                stop()
                return@launch
            }
            try {
                recorder.startRecording()
            } catch (se: SecurityException) {
                listener.onError("无法开始录音: ${se.message}")
                try { recorder.release() } catch (_: Throwable) {}
                stop()
                return@launch
            }
            val buf = ByteArray(chunkBytes)
            try {
                while (running.get()) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        val chunk = if (read == buf.size) buf else buf.copyOf(read)
                        val compressed = gzip(chunk)
                        val frame = buildFrame(
                            messageType = MSG_TYPE_AUDIO_ONLY,
                            flags = FLAGS_NOSEQ,
                            serialization = SERIAL_NONE,
                            payload = compressed,
                            alreadyCompressed = true
                        )
                        webSocket?.send(frame.toByteString())
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "audio loop error", t)
            } finally {
                try { recorder.stop() } catch (_: Throwable) {}
                try { recorder.release() } catch (_: Throwable) {}
            }
        }
    }

    private fun buildFullClientRequestJson(): String {
            val user = JSONObject().apply {
                put("uid", UUID.randomUUID().toString())
                put("platform", "Android")
                put("app_version", "1.0")
            }
        val audio = JSONObject().apply {
            put("format", "pcm")
            put("codec", "raw")
            put("rate", sampleRate)
            put("bits", 16)
            put("channel", 1)
        }
        val request = JSONObject().apply {
            put("model_name", "bigmodel")
            put("enable_nonstream", true) // 开启二遍识别
            put("enable_itn", true)
            put("enable_punc", true)
            put("enable_ddc", true) // 启用语义顺滑，减少重复
            // 使用服务端VAD进行分句：静音超过end_window_size即判停并输出definite
            put("end_window_size", END_WINDOW_MS)
            // 前1s内也允许根据静音判停，避免默认10s内不判停
            put("force_to_speech_time", FORCE_TO_SPEECH_MS)
            put("show_utterances", true)
        }
        val root = JSONObject().apply {
            put("user", user)
            put("audio", audio)
            put("request", request)
        }
        return root.toString()
    }

    private fun handleServerFrame(bytes: ByteArray) {
        if (bytes.size < 8) return
        // header0 is version/header size unit; not needed here
        val header1 = bytes[1].toInt() and 0xFF
        val header2 = bytes[2].toInt() and 0xFF
        // val header3 = bytes[3]
        val msgType = (header1 ushr 4) and 0x0F
        val flags = header1 and 0x0F
        // val serialization = (header2 ushr 4) and 0x0F
        val compression = header2 and 0x0F

        when (msgType) {
            MSG_TYPE_FULL_SERVER_RSP -> {
                // next 4 bytes: sequence, then 4 bytes: payload size
                if (bytes.size < 12) return
                val bb = ByteBuffer.wrap(bytes, 4, 8).order(ByteOrder.BIG_ENDIAN)
                bb.int // consume sequence, not used
                val payloadSize = bb.int
                val payloadStart = 12
                val payloadEnd = payloadStart + payloadSize
                if (payloadEnd > bytes.size) return
                val payload = bytes.copyOfRange(payloadStart, payloadEnd)
                val data = if (compression == COMP_GZIP) gunzip(payload) else payload
                val (textRaw, stableRaw) = parseResultTextAndStable(String(data))
                val text = textRaw?.let { dedupImmediateRepeatSuffix(it) }
                val stableFromServer = stableRaw?.let { dedupImmediateRepeatSuffix(it) }
                if (text != null) {
                    if (flags == FLAGS_LAST_RESULT) {
                        listener.onFinal(text)
                        try { webSocket?.close(1000, "final") } catch (_: Throwable) {}
                        // End session after delivering final result (no auto-restart)
                        running.set(false)
                    } else {
                        val stableText = stableFromServer ?: ""
                        val (stableOut, unstable) = splitStableUnstable(text, stableText)
                        listener.onPartial(stableOut, unstable)
                    }
                }
            }
            MSG_TYPE_ERROR -> {
                if (bytes.size < 12) return
                val bb = ByteBuffer.wrap(bytes, 4, 8).order(ByteOrder.BIG_ENDIAN)
                val code = bb.int
                val payloadSize = bb.int
                val payloadStart = 12
                val payloadEnd = payloadStart + payloadSize
                val info = if (payloadEnd <= bytes.size) String(bytes.copyOfRange(payloadStart, payloadEnd)) else ""
                listener.onError("ASR错误: $code $info")
                stop()
            }
            else -> {
                // ignore
            }
        }
    }

    private fun parseResultTextAndStable(json: String): Pair<String?, String?> {
        return try {
            val obj = JSONObject(json)
            if (!obj.has("result")) return Pair(null, null)
            val result = obj.getJSONObject("result")
            val fullText = result.optString("text", "")
            var stableText: String? = null
            if (result.has("utterances")) {
                val arr = result.getJSONArray("utterances")
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val u = arr.getJSONObject(i)
                    val definite = u.optBoolean("definite", false)
                    val utext = u.optString("text", "")
                    if (definite) {
                        sb.append(utext)
                    }
                }
                stableText = sb.toString()
            }
            Pair(fullText, stableText)
        } catch (_: Throwable) {
            Pair(null, null)
        }
    }

    private fun splitStableUnstable(text: String, stableCandidate: String): Pair<String, String> {
        // Prefer exact prefix split; fall back to longest common prefix to be robust to punctuation
        return if (text.startsWith(stableCandidate)) {
            Pair(stableCandidate, text.substring(stableCandidate.length))
        } else {
            val lcpLen = longestCommonPrefixLength(text, stableCandidate)
            Pair(stableCandidate.substring(0, lcpLen), text.substring(lcpLen))
        }
    }

    private fun longestCommonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    // Heuristic: if text ends with an immediate duplicate block (e.g., ABCDABCD),
    // and the block is alnum-heavy with reasonable length, drop the trailing copy.
    private fun dedupImmediateRepeatSuffix(s: String): String {
        if (s.length < 8) return s // too short to meaningfully dedup
        val maxCheck = s.length / 2
        // Try larger blocks first to avoid trimming small intended repeats
        for (blk in maxCheck downTo 4) {
            val end = s.length
            val mid = end - blk
            val start = end - 2 * blk
            if (start < 0) continue
            val a = s.substring(start, mid)
            val b = s.substring(mid, end)
            if (a == b && isMostlyAlphaNum(b)) {
                return s.substring(0, end - blk)
            }
        }
        return s
    }

    private fun isMostlyAlphaNum(str: String): Boolean {
        if (str.isEmpty()) return false
        var alnum = 0
        for (ch in str) {
            if (ch.isLetterOrDigit()) alnum++
        }
        return alnum * 2 >= str.length // >=50% alnum
    }

    private fun buildFrame(
        messageType: Int,
        flags: Int,
        serialization: Int,
        payload: ByteArray,
        alreadyCompressed: Boolean = false
    ): ByteArray {
        val compression = COMP_GZIP
        val compressedPayload = if (alreadyCompressed) payload else gzip(payload)
        val header0 = ((VERSION and 0x0F) shl 4) or (HEADER_SIZE_UNITS and 0x0F)
        val header1 = ((messageType and 0x0F) shl 4) or (flags and 0x0F)
        val header2 = ((serialization and 0x0F) shl 4) or (compression and 0x0F)
        val header3 = 0
        val payloadSize = compressedPayload.size
        val out = ByteArrayOutputStream(4 + 4 + payloadSize)
        out.write(byteArrayOf(header0.toByte(), header1.toByte(), header2.toByte(), header3.toByte()))
        out.write(intToBytesBE(payloadSize))
        out.write(compressedPayload)
        return out.toByteArray()
    }

    private fun intToBytesBE(v: Int): ByteArray {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(v)
        return bb.array()
    }

    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    private fun gunzip(input: ByteArray): ByteArray {
        val `is` = GZIPInputStream(ByteArrayInputStream(input))
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val r = `is`.read(buf)
            if (r <= 0) break
            bos.write(buf, 0, r)
        }
        return bos.toByteArray()
    }

    

    companion object {
        private const val TAG = "VolcStreamAsr"

        // Protocol constants
        private const val VERSION = 0x1
        private const val HEADER_SIZE_UNITS = 0x1 // 1 * 4 bytes = 4 bytes

        private const val MSG_TYPE_FULL_CLIENT_REQ = 0x1
        private const val MSG_TYPE_AUDIO_ONLY = 0x2
        private const val MSG_TYPE_FULL_SERVER_RSP = 0x9
        private const val MSG_TYPE_ERROR = 0xF

        private const val FLAGS_NOSEQ = 0x0
        private const val FLAGS_LAST_NOSEQ = 0x2
        private const val FLAGS_LAST_RESULT = 0x3

        private const val SERIAL_NONE = 0x0
        private const val SERIAL_JSON = 0x1

        private const val COMP_GZIP = 0x1

        private const val CHUNK_MS = 200
        private const val END_WINDOW_MS = 800 // 服务端VAD强制判停时间（ms）
        private const val FORCE_TO_SPEECH_MS = 1000 // 允许在前1s内根据静音判停
    }
}
