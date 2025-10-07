package com.example.asrkeyboard.asr

import com.example.asrkeyboard.store.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Simple OpenAI-format post-processor for ASR text cleanup.
 * Uses Chat Completions (preferred) and falls back to Responses-compatible parsing.
 */
class LlmPostProcessor(private val client: OkHttpClient? = null) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun resolveUrl(base: String): String {
        val raw = base.trim()
        if (raw.isEmpty()) return Prefs.DEFAULT_LLM_ENDPOINT.trimEnd('/') + "/chat/completions"
        val b = raw.trimEnd('/')
        val withScheme = if (b.startsWith("http://", ignoreCase = true) || b.startsWith("https://", ignoreCase = true)) b else "https://$b"
        // If user already provided a full path ending with /chat/completions or /responses, use as-is
        return if (withScheme.endsWith("/chat/completions") || withScheme.endsWith("/responses")) withScheme else "$withScheme/chat/completions"
    }

    suspend fun process(input: String, prefs: Prefs): String = withContext(Dispatchers.IO) {
        if (input.isBlank()) return@withContext input
        val apiKey = prefs.llmApiKey
        val endpoint = prefs.llmEndpoint
        val model = prefs.llmModel
        val temperature = prefs.llmTemperature.toDouble()
        val prompt = prefs.activePromptContent.ifBlank { Prefs.DEFAULT_LLM_PROMPT }

        val url = resolveUrl(endpoint)
        val reqJson = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", prompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", input)
                })
            })
        }.toString()

        val body = reqJson.toRequestBody(jsonMedia)
        val http = (client ?: OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build())
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            return@withContext input // Fallback to original on failure
        }
        val text = try {
            val s = resp.body?.string() ?: return@withContext input
            val obj = JSONObject(s)
            when {
                obj.has("choices") -> {
                    val choices = obj.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val msg = choices.getJSONObject(0).optJSONObject("message")
                        msg?.optString("content")?.ifBlank { input } ?: input
                    } else input
                }
                obj.has("output_text") -> obj.optString("output_text", input)
                else -> input
            }
        } catch (_: Throwable) {
            input
        } finally {
            resp.close()
        }
        return@withContext text
    }

    /**
     * Edit existing text with a natural language instruction using a Chat Completions-compatible API.
     * Returns the edited text; on any failure returns the original text unchanged.
     */
    suspend fun editText(original: String, instruction: String, prefs: Prefs): String = withContext(Dispatchers.IO) {
        if (original.isBlank() || instruction.isBlank()) return@withContext original
        val apiKey = prefs.llmApiKey
        val endpoint = prefs.llmEndpoint
        val model = prefs.llmModel
        val temperature = prefs.llmTemperature.toDouble()

        val url = resolveUrl(endpoint)
        val systemPrompt = """
            你是一个文本编辑助手。根据用户的“编辑指令”对提供的“原文”进行修改并输出最终结果：
            - 只输出修改后的文本，不要添加解释或前后包裹标记。
            - 如果指令要求对部分内容变更，请在整体语义合理的前提下进行最小必要修改。
            - 若指令含糊无法执行，请尽量按直觉优化原文的清晰度与可读性。
        """.trimIndent()

        val userContent = """
            指令：${instruction}
            原文：${original}
        """.trimIndent()

        val reqJson = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            })
        }.toString()

        val body = reqJson.toRequestBody(jsonMedia)
        val http = (client ?: OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            return@withContext original
        }
        val out = try {
            val s = resp.body?.string() ?: return@withContext original
            val obj = JSONObject(s)
            when {
                obj.has("choices") -> {
                    val choices = obj.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val msg = choices.getJSONObject(0).optJSONObject("message")
                        msg?.optString("content")?.ifBlank { original } ?: original
                    } else original
                }
                obj.has("output_text") -> obj.optString("output_text", original)
                else -> original
            }
        } catch (_: Throwable) { original } finally { resp.close() }
        return@withContext out
    }
}
