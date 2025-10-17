package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Base64
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 使用 SiliconFlow "audio/transcriptions" API 的非流式 ASR 引擎。
 */
class SiliconFlowFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration) {

    // SiliconFlow：未明确限制，本地限制为 20 分钟
    override val maxRecordDurationMillis: Int = 20 * 60 * 1000

    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.sfApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_siliconflow_key))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        try {
            val wav = pcmToWav(pcm)
            val apiKey = prefs.sfApiKey
            val t0 = System.nanoTime()
            if (prefs.sfUseOmni) {
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
                        val detail = formatHttpDetail(r.message, null)
                        listener.onError(
                            context.getString(R.string.error_request_failed_http, r.code, detail)
                        )
                        return
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
                        val detail = formatHttpDetail(r.message, null)
                        listener.onError(
                            context.getString(R.string.error_request_failed_http, r.code, detail)
                        )
                        return
                    }
                    val bodyStr = r.body?.string().orEmpty()
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

    private fun buildSfChatCompletionsBody(model: String, base64Wav: String, prompt: String): String {
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
        return JSONObject().apply {
            put("model", model)
            put("messages", org.json.JSONArray().apply { put(user) })
        }.toString()
    }

    private fun parseSfChatText(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val o = JSONObject(body)
            val arr = o.optJSONArray("choices") ?: return ""
            if (arr.length() == 0) return ""
            val c0 = arr.optJSONObject(0) ?: return ""
            val msg = c0.optJSONObject("message") ?: return ""
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
}
