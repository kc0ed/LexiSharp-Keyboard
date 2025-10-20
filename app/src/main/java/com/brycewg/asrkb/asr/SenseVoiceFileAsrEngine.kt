package com.brycewg.asrkb.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope

/**
 * 本地 SenseVoice（通过 sherpa-onnx）非流式文件识别引擎。
 * 目前为占位实现：当 sherpa-onnx 依赖与模型未接入时，给出友好提示而不发起录音后的无效识别。
 * 后续接入模型与 AAR/so 后，可在 [recognize] 中补充实际推理调用。
 */
class SenseVoiceFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration) {

    // 本地 SenseVoice：为降低内存占用，主动限制为 5 分钟
    override val maxRecordDurationMillis: Int = 5 * 60 * 1000

    interface LocalModelLoadUi {
        fun onLocalModelLoadStart()
        fun onLocalModelLoadDone()
    }

    private fun showToast(resId: Int) {
        try {
            Handler(Looper.getMainLooper()).post {
                try { Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    private fun notifyLoadStart() {
        val ui = (listener as? LocalModelLoadUi)
        if (ui != null) {
            try { ui.onLocalModelLoadStart() } catch (_: Throwable) { }
        } else {
            showToast(R.string.sv_loading_model)
        }
    }

    private fun notifyLoadDone() {
        val ui = (listener as? LocalModelLoadUi)
        if (ui != null) {
            try { ui.onLocalModelLoadDone() } catch (_: Throwable) { }
        }
    }

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        // 若未集成 sherpa-onnx Kotlin/so，则直接报错以避免无意义的录音
        if (!SenseVoiceOnnxBridge.isOnnxAvailable()) {
            try { listener.onError(context.getString(R.string.error_local_asr_not_ready)) } catch (_: Throwable) {}
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        val t0 = System.currentTimeMillis()
        try {
            if (!SenseVoiceOnnxBridge.isOnnxAvailable()) {
                listener.onError(context.getString(R.string.error_local_asr_not_ready))
                return
            }
            // 模型目录：固定为外部专属目录（不可配置）；外部不可用时回退内部目录
            val base = try { context.getExternalFilesDir(null) } catch (_: Throwable) { null } ?: context.filesDir
            val probeRoot = java.io.File(base, "sensevoice")
            // 优先在所选版本目录下查找；若缺失则回退到根下任意含 tokens 的目录（兼容旧版）
            val variant = try { prefs.svModelVariant } catch (_: Throwable) { "small-int8" }
            val variantDir = when (variant) {
                "small-full" -> java.io.File(probeRoot, "small-full")
                else -> java.io.File(probeRoot, "small-int8")
            }
            val auto = findSvModelDir(variantDir) ?: findSvModelDir(probeRoot)
            if (auto == null) {
                listener.onError(context.getString(R.string.error_sensevoice_model_missing))
                return
            }
            val dir = auto.absolutePath

            // 准备模型文件（优先 int8 回退 float32）
            val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
            val int8File = java.io.File(dir, "model.int8.onnx")
            val f32File = java.io.File(dir, "model.onnx")
            val modelFile = when {
                int8File.exists() -> int8File
                f32File.exists() -> f32File
                else -> null
            }
            val modelPath = modelFile?.absolutePath
            val minBytes = 8L * 1024L * 1024L // 粗略下限，避免明显的截断文件
            if (modelPath == null || !java.io.File(tokensPath).exists() || (modelFile?.length() ?: 0L) < minBytes) {
                listener.onError(context.getString(R.string.error_sensevoice_model_missing))
                return
            }

            // PCM16LE -> FloatArray(-1..1)
            val samples = pcmToFloatArray(pcm)
            if (samples.isEmpty()) {
                listener.onError(context.getString(R.string.error_audio_empty))
                return
            }

            // 反射调用 sherpa-onnx Kotlin API
            // 注意：当从绝对路径加载模型/词表时，必须将 assetManager 设为 null
            // 参考 sherpa-onnx 提示 https://github.com/k2-fsa/sherpa-onnx/issues/2562
            // 在需要创建新识别器时，向用户提示“加载中/完成”
            val keepMinutes = try { prefs.svKeepAliveMinutes } catch (_: Throwable) { -1 }
            val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
            val alwaysKeep = keepMinutes < 0

            val text = SenseVoiceOnnxBridge.decodeOffline(
                assetManager = null,
                tokens = tokensPath,
                model = modelPath,
                language = try { prefs.svLanguage } catch (_: Throwable) { "auto" },
                useItn = try { prefs.svUseItn } catch (_: Throwable) { false },
                provider = "cpu",
                numThreads = try { prefs.svNumThreads } catch (_: Throwable) { 2 },
                samples = samples,
                sampleRate = sampleRate,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = { notifyLoadStart() },
                onLoadDone = { notifyLoadDone() }
            )

            if (text.isNullOrBlank()) {
                listener.onError(context.getString(R.string.error_asr_empty_result))
            } else {
                listener.onFinal(text.trim())
            }
        } catch (t: Throwable) {
            listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
        } finally {
            val dt = System.currentTimeMillis() - t0
            try { onRequestDuration?.invoke(dt) } catch (_: Throwable) { }
        }
    }

    private fun pcmToFloatArray(pcm: ByteArray): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val n = pcm.size / 2
        val out = FloatArray(n)
        val bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < n) {
            val s = bb.short.toInt()
            // 32768f 防止 -32768 溢出；限制到 [-1, 1]
            var f = s / 32768.0f
            if (f > 1f) f = 1f else if (f < -1f) f = -1f
            out[i] = f
            i++
        }
        return out
    }

    // 目录探测改为统一使用顶层 findSvModelDir
}

// 公开卸载入口：供设置页在清除模型后释放本地识别器内存
fun unloadSenseVoiceRecognizer() {
    try { SenseVoiceOnnxBridge.unload() } catch (_: Throwable) { }
}

// 预加载：根据当前配置尝试构建本地识别器，便于降低首次点击等待
fun preloadSenseVoiceIfConfigured(
    context: Context,
    prefs: Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false
) {
    try {
        if (!SenseVoiceOnnxBridge.isOnnxAvailable()) return
        // 保护：若“保留时长=不保留(0)”，则跳过预加载，避免立刻卸载造成空转
        val keepMinutesGuard = try { prefs.svKeepAliveMinutes } catch (_: Throwable) { -1 }
        if (keepMinutesGuard == 0) return
        val base = try { context.getExternalFilesDir(null) } catch (_: Throwable) { null } ?: context.filesDir
        val probeRoot = java.io.File(base, "sensevoice")
        val variant = try { prefs.svModelVariant } catch (_: Throwable) { "small-int8" }
        val variantDir = when (variant) {
            "small-full" -> java.io.File(probeRoot, "small-full")
            else -> java.io.File(probeRoot, "small-int8")
        }
        val auto = findSvModelDir(variantDir) ?: findSvModelDir(probeRoot) ?: return
        val dir = auto.absolutePath
        val tokensPath = java.io.File(dir, "tokens.txt").absolutePath
        val int8File = java.io.File(dir, "model.int8.onnx")
        val f32File = java.io.File(dir, "model.onnx")
        val modelFile = when {
            int8File.exists() -> int8File
            f32File.exists() -> f32File
            else -> return
        }
        val modelPath = modelFile.absolutePath
        val minBytes = 8L * 1024L * 1024L
        if (!java.io.File(tokensPath).exists() || modelFile.length() < minBytes) return
        val keepMinutes = try { prefs.svKeepAliveMinutes } catch (_: Throwable) { -1 }
        val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
        val alwaysKeep = keepMinutes < 0
        SenseVoiceOnnxBridge.prepare(
            assetManager = null,
            tokens = tokensPath,
            model = modelPath,
            language = try { prefs.svLanguage } catch (_: Throwable) { "auto" },
            useItn = try { prefs.svUseItn } catch (_: Throwable) { false },
            provider = "cpu",
            numThreads = try { prefs.svNumThreads } catch (_: Throwable) { 2 },
            keepAliveMs = keepMs,
            alwaysKeep = alwaysKeep,
            onLoadStart = {
                try { onLoadStart?.invoke() } catch (_: Throwable) { }
                if (!suppressToastOnStart) {
                    // 确保在主线程弹出 Toast，避免后台线程直接触发导致异常或卡顿
                    try {
                        val mh = Handler(Looper.getMainLooper())
                        mh.post {
                            try { Toast.makeText(context, context.getString(R.string.sv_loading_model), Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
                        }
                    } catch (_: Throwable) { }
                }
            },
            onLoadDone = onLoadDone,
        )
    } catch (_: Throwable) { }
}

// 顶层工具：在指定根目录下寻找包含 tokens.txt 的模型目录（最多一层）
fun findSvModelDir(root: java.io.File?): java.io.File? {
    if (root == null || !root.exists()) return null
    val direct = java.io.File(root, "tokens.txt")
    if (direct.exists()) return root
    val subs = root.listFiles() ?: return null
    for (f in subs) {
        if (f.isDirectory) {
            val t = java.io.File(f, "tokens.txt")
            if (t.exists()) return f
        }
    }
    return null
}

/**
 * 通过反射探测 sherpa-onnx 是否可用；未引入依赖时不产生编译时引用。
 */
private object SenseVoiceOnnxBridge {
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var pendingUnload: Runnable? = null
    @Volatile private var cachedKey: String? = null
    @Volatile private var cachedRecognizer: Any? = null
    @Volatile private var cachedStreamClass: Class<*>? = null
    @Volatile private var clsOfflineRecognizer: Class<*>? = null
    @Volatile private var clsOfflineRecognizerConfig: Class<*>? = null
    @Volatile private var clsOfflineModelConfig: Class<*>? = null
    @Volatile private var clsOfflineSenseVoiceModelConfig: Class<*>? = null

    fun isOnnxAvailable(): Boolean {
        return try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun unload() {
        val recognizer = cachedRecognizer
        cachedRecognizer = null
        cachedKey = null
        cachedStreamClass = null
        if (recognizer != null) {
            try {
                // 优先尝试 release 方法
                val m = (clsOfflineRecognizer ?: recognizer.javaClass).getMethod("release")
                m.invoke(recognizer)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 通过反射完成一次离线解码。依赖于 sherpa-onnx Kotlin API 在运行时可用。
     */
    fun decodeOffline(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        model: String,
        language: String,
        useItn: Boolean,
        provider: String,
        numThreads: Int,
        samples: FloatArray,
        sampleRate: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): String? {
        try {
            if (clsOfflineRecognizer == null) {
                clsOfflineRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
                clsOfflineRecognizerConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
                clsOfflineModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
                clsOfflineSenseVoiceModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig")
            }

            // sense_voice 配置
            val senseVoice = try {
                // 优先使用 (model: String, language: String, useItn: Boolean) 构造
                clsOfflineSenseVoiceModelConfig!!.getDeclaredConstructor(String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                    .newInstance(model, language, useItn)
            } catch (_: Throwable) {
                // 回退仅模型路径构造 + 反射设置属性
                val inst = clsOfflineSenseVoiceModelConfig!!.getDeclaredConstructor(String::class.java).newInstance(model)
                trySetField(inst, "language", language)
                trySetField(inst, "useItn", useItn)
                inst
            }

            // modelConfig：尝试无参构造后逐项设置
            val modelConfig = clsOfflineModelConfig!!.getDeclaredConstructor().newInstance()
            trySetField(modelConfig, "tokens", tokens)
            trySetField(modelConfig, "numThreads", numThreads)
            trySetField(modelConfig, "provider", provider)
            trySetField(modelConfig, "debug", false)
            // Kotlin 属性名可能为 senseVoice
            if (!trySetField(modelConfig, "senseVoice", senseVoice)) {
                // 兼容下划线命名（toString 显示）
                trySetField(modelConfig, "sense_voice", senseVoice)
            }

            // recognizerConfig：尝试无参构造后设置 modelConfig
            val recConfig = clsOfflineRecognizerConfig!!.getDeclaredConstructor().newInstance()
            if (!trySetField(recConfig, "modelConfig", modelConfig)) {
                trySetField(recConfig, "model_config", modelConfig)
            }

            // 构造缓存 key
            val key = listOf(tokens, model, language, useItn.toString(), provider, numThreads.toString()).joinToString("|")
            var recognizer = cachedRecognizer
            if (cachedKey != key || recognizer == null) {
                try { onLoadStart?.invoke() } catch (_: Throwable) { }
                // 创建新的 recognizer，并缓存；不主动 release 以避免部分版本 double-free 问题
                // 当 assetManager 为空（从绝对路径加载）时，优先尝试无 assetManager 的构造函数
                val ctor = if (assetManager == null) {
                    try {
                        clsOfflineRecognizer!!.getDeclaredConstructor(clsOfflineRecognizerConfig)
                    } catch (_: Throwable) {
                        // 回退到带 assetManager 的构造（以 null 传入）
                        clsOfflineRecognizer!!.getDeclaredConstructor(android.content.res.AssetManager::class.java, clsOfflineRecognizerConfig)
                    }
                } else {
                    try {
                        clsOfflineRecognizer!!.getDeclaredConstructor(android.content.res.AssetManager::class.java, clsOfflineRecognizerConfig)
                    } catch (_: Throwable) {
                        clsOfflineRecognizer!!.getDeclaredConstructor(clsOfflineRecognizerConfig)
                    }
                }
                recognizer = if (ctor.parameterCount == 2) ctor.newInstance(assetManager, recConfig)
                             else ctor.newInstance(recConfig)
                cachedRecognizer = recognizer
                cachedKey = key
                cachedStreamClass = null
                try { onLoadDone?.invoke() } catch (_: Throwable) { }
            }

            // createStream -> acceptWaveform -> decode -> getResult
            val stream = clsOfflineRecognizer!!.getMethod("createStream").invoke(recognizer)
            val clsStream = (cachedStreamClass ?: stream.javaClass).also { cachedStreamClass = it }
            try {
                clsStream.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType).invoke(stream, samples, sampleRate)
            } catch (_: Throwable) {
                // 兼容仅 FloatArray 的重载
                clsStream.getMethod("acceptWaveform", FloatArray::class.java).invoke(stream, samples)
            }
            clsOfflineRecognizer!!.getMethod("decode", clsStream).invoke(recognizer, stream)
            val result = clsOfflineRecognizer!!.getMethod("getResult", clsStream).invoke(recognizer, stream)
            val text = tryGetStringField(result, "text") ?: result?.toString()

            // 释放资源（若有）
            try { clsStream.getMethod("release").invoke(stream) } catch (_: Throwable) { }
            // recognizer 长驻进程缓存，不主动 release

            scheduleAutoUnload(keepAliveMs, alwaysKeep)
            return text
        } catch (_: Throwable) {
            return null
        }
    }

    fun isPrepared(): Boolean {
        return cachedRecognizer != null
    }

    @Synchronized
    private fun scheduleAutoUnload(keepAliveMs: Long, alwaysKeep: Boolean) {
        // 取消上一次计划
        val old = pendingUnload
        if (old != null) {
            try { mainHandler.removeCallbacks(old) } catch (_: Throwable) { }
            pendingUnload = null
        }
        if (alwaysKeep) return
        if (keepAliveMs <= 0L) {
            unload(); return
        }
        val r = Runnable { try { unload() } catch (_: Throwable) { } }
        pendingUnload = r
        try { mainHandler.postDelayed(r, keepAliveMs) } catch (_: Throwable) { }
    }

    /**
     * 仅预加载（不解码），用于打开键盘等场景的预热。
     */
    fun prepare(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        model: String,
        language: String,
        useItn: Boolean,
        provider: String,
        numThreads: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): Boolean {
        return try {
            if (clsOfflineRecognizer == null) {
                clsOfflineRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
                clsOfflineRecognizerConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
                clsOfflineModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
                clsOfflineSenseVoiceModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig")
            }
            val senseVoice = try {
                clsOfflineSenseVoiceModelConfig!!.getDeclaredConstructor(String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                    .newInstance(model, language, useItn)
            } catch (_: Throwable) {
                val inst = clsOfflineSenseVoiceModelConfig!!.getDeclaredConstructor(String::class.java).newInstance(model)
                trySetField(inst, "language", language)
                trySetField(inst, "useItn", useItn)
                inst
            }
            val modelConfig = clsOfflineModelConfig!!.getDeclaredConstructor().newInstance()
            trySetField(modelConfig, "tokens", tokens)
            trySetField(modelConfig, "numThreads", numThreads)
            trySetField(modelConfig, "provider", provider)
            trySetField(modelConfig, "debug", false)
            if (!trySetField(modelConfig, "senseVoice", senseVoice)) {
                trySetField(modelConfig, "sense_voice", senseVoice)
            }
            val recConfig = clsOfflineRecognizerConfig!!.getDeclaredConstructor().newInstance()
            if (!trySetField(recConfig, "modelConfig", modelConfig)) {
                trySetField(recConfig, "model_config", modelConfig)
            }
            val key = listOf(tokens, model, language, useItn.toString(), provider, numThreads.toString()).joinToString("|")
            var recognizer = cachedRecognizer
            if (cachedKey != key || recognizer == null) {
                try { onLoadStart?.invoke() } catch (_: Throwable) { }
                val ctor = if (assetManager == null) {
                    try { clsOfflineRecognizer!!.getDeclaredConstructor(clsOfflineRecognizerConfig) }
                    catch (_: Throwable) { clsOfflineRecognizer!!.getDeclaredConstructor(android.content.res.AssetManager::class.java, clsOfflineRecognizerConfig) }
                } else {
                    try { clsOfflineRecognizer!!.getDeclaredConstructor(android.content.res.AssetManager::class.java, clsOfflineRecognizerConfig) }
                    catch (_: Throwable) { clsOfflineRecognizer!!.getDeclaredConstructor(clsOfflineRecognizerConfig) }
                }
                recognizer = if (ctor.parameterCount == 2) ctor.newInstance(assetManager, recConfig) else ctor.newInstance(recConfig)
                cachedRecognizer = recognizer
                cachedKey = key
                cachedStreamClass = null
                try { onLoadDone?.invoke() } catch (_: Throwable) { }
            }
            scheduleAutoUnload(keepAliveMs, alwaysKeep)
            true
        } catch (_: Throwable) { false }
    }

    private fun trySetField(target: Any, name: String, value: Any?): Boolean {
        return try {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(target, value)
            true
        } catch (_: Throwable) {
            try {
                val m = target.javaClass.getMethod("set" + name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }, value?.javaClass ?: Any::class.java)
                m.invoke(target, value)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun tryGetStringField(target: Any?, name: String): String? {
        if (target == null) return null
        return try {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            (f.get(target) as? String)
        } catch (_: Throwable) { null }
    }
}

// 判断是否已有缓存的本地识别器（已加载模型）
fun isSenseVoicePrepared(): Boolean {
    return try { SenseVoiceOnnxBridge.isPrepared() } catch (_: Throwable) { false }
}
