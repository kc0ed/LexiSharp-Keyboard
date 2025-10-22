package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * 基于 Silero VAD（sherpa-onnx）的语音活动检测与判停器。
 *
 * 相比基于音量阈值的 SilenceDetector，VAD 使用 AI 模型进行语音/静音判断，
 * 能够更准确地区分语音、呼吸声、环境噪音，减少误判。
 *
 * ## 工作原理
 * - 将 PCM 音频块转换为归一化的 FloatArray
 * - 调用 sherpa-onnx Vad 模型进行实时语音活动检测
 * - 累计连续非语音时长，超过窗口阈值时触发判停
 *
 * ## 使用示例
 * ```kotlin
 * val detector = VadDetector(context, windowMs = 1500, sensitivityLevel = 5)
 * if (detector.shouldStop(audioChunk, audioChunk.size)) {
 *     // 触发判停
 * }
 * detector.reset() // 重新开始录音时重置状态
 * ```
 *
 * @param context Android Context，用于访问 AssetManager
 * @param sampleRate 音频采样率（Hz），必须与 PCM 数据一致
 * @param windowMs 连续非语音的时长阈值（毫秒），超过此值判定为静音
 * @param sensitivityLevel 灵敏度档位（1-10），值越大越容易判定为静音
 */
class VadDetector(
    private val context: Context,
    private val sampleRate: Int,
    private val windowMs: Int,
    sensitivityLevel: Int
) {
    companion object {
        private const val TAG = "VadDetector"

        // 灵敏度档位总数（与设置滑块一致）
        const val LEVELS: Int = 10

        // 灵敏度映射到 VAD 的 minSilenceDuration 参数（单位：秒）
        // 调整为更宽松的分段：低档位给更长的静音要求，减少“提前中断”。
        // 规则：sensitivityLevel 越高，minSilenceDuration 越小（更敏感）。
        private val MIN_SILENCE_DURATION_MAP: FloatArray = floatArrayOf(
            0.55f, // 1  非常不敏感：至少 0.60s 静音才算非语音
            0.50f, // 2
            0.42f, // 3
            0.35f, // 4
            0.30f, // 5
            0.25f, // 6
            0.20f, // 7（默认附近）
            0.16f, // 8
            0.12f, // 9
            0.08f  // 10 更敏感
        )
    }

    private var vad: Vad? = null
    private var silentMsAcc: Int = 0
    private val minSilenceDuration: Float
    private val threshold: Float
    private val speechHangoverMs: Int
    private var speechHangoverRemainingMs: Int = 0
    // 录音开始阶段的初期防抖（仅在首次检测到语音之前生效）
    private val initialDebounceMs: Int
    private var initialDebounceRemainingMs: Int = 0
    private var hasDetectedSpeech: Boolean = false

    init {
        val lvl = sensitivityLevel.coerceIn(1, LEVELS)
        minSilenceDuration = MIN_SILENCE_DURATION_MAP[lvl - 1]

        // 将灵敏度映射到 VAD 置信阈值：
        // 10 档：低(1-3)=0.40，中(4-7)=0.50，高(8-10)=0.60
        threshold = when (lvl) {
            in 1..3 -> 0.40f
            in 4..7 -> 0.50f
            else -> 0.60f
        }

        // 短暂静音“挂起”时间：在检测到语音后，容忍这段时间内的瞬时静音，避免过早累计。
        // 档位越低，挂起越长以更保守地保持语音状态。
        speechHangoverMs = when (lvl) {
            in 1..3 -> 300
            in 4..7 -> 250
            else -> 200
        }

        // 初期防抖：刚开始录音时，允许一小段时间不累计静音，避免尚未开口或微弱启动噪声导致的提前停。
        // 比语音挂起略保守一点（低档位更长）。
        initialDebounceMs = when (lvl) {
            in 1..3 -> 300
            in 4..7 -> 200
            else -> 100
        }
        initialDebounceRemainingMs = initialDebounceMs

        try {
            initVad()
            Log.i(
                TAG,
                "VadDetector initialized: windowMs=$windowMs, sensitivity=$lvl, minSilenceDuration=$minSilenceDuration, threshold=$threshold, hangoverMs=$speechHangoverMs, initialDebounceMs=$initialDebounceMs"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize VAD, will fallback to no detection", t)
        }
    }

    /**
     * 初始化 sherpa-onnx Vad
     */
    private fun initVad() {
        // 1. 构建 SileroVadModelConfig
        val sileroConfig = SileroVadModelConfig(
            model = "vad/silero_vad.onnx",  // 模型路径（相对于 assets）
            threshold = threshold,           // 按灵敏度映射的语音检测阈值
            minSilenceDuration = minSilenceDuration, // 根据灵敏度映射
            minSpeechDuration = 0.25f,       // 最小语音持续时长
            windowSize = 512                 // VAD 窗口大小
        )

        // 2. 构建 VadModelConfig
        val vadConfig = VadModelConfig(
            sileroVadModelConfig = sileroConfig,
            sampleRate = sampleRate,
            numThreads = 1,
            provider = "cpu",
            debug = false
        )

        // 3. 创建 Vad 实例
        vad = Vad(
            assetManager = context.assets,
            config = vadConfig
        )

        Log.d(TAG, "Vad instance created successfully")
    }

    /**
     * 处理音频帧，判断是否应停止录音。
     *
     * @param buf PCM 音频数据（16-bit LE）
     * @param len 有效数据长度（字节）
     * @return 如果连续非语音时长超过窗口阈值，返回 true
     */
    fun shouldStop(buf: ByteArray, len: Int): Boolean {
        val vad = this.vad ?: return false

        try {
            val frameMs = if (sampleRate > 0) ((len / 2) * 1000) / sampleRate else 0

            // 1. 将 PCM ByteArray 转换为 FloatArray（归一化到 -1.0 ~ 1.0）
            val samples = pcmToFloatArray(buf, len)
            if (samples.isEmpty()) return false

            // 2. 调用 Vad.acceptWaveform(FloatArray)
            vad.acceptWaveform(samples)

            // 3. 调用 Vad.isSpeechDetected(): Boolean
            val isSpeech = vad.isSpeechDetected()

            // 4. 调用 Vad.clear() 清除内部状态（准备下一帧）
            vad.clear()

            // 5. 初期防抖 + 语音挂起 平滑处理，再进行累计非语音时长
            if (isSpeech) {
                silentMsAcc = 0
                hasDetectedSpeech = true
                speechHangoverRemainingMs = speechHangoverMs
            } else {
                // 初期防抖：尚未检测到语音前，不累计静音
                if (!hasDetectedSpeech && initialDebounceRemainingMs > 0) {
                    initialDebounceRemainingMs -= frameMs
                    if (initialDebounceRemainingMs < 0) initialDebounceRemainingMs = 0
                    return false
                }

                // 语音挂起：检测到语音后的一小段时间内，不累计静音
                if (speechHangoverRemainingMs > 0) {
                    speechHangoverRemainingMs -= frameMs
                    if (speechHangoverRemainingMs < 0) speechHangoverRemainingMs = 0
                    // 挂起期内直接返回
                    return false
                }

                silentMsAcc += frameMs
                if (silentMsAcc >= windowMs) {
                    Log.d(TAG, "Silence window reached: ${silentMsAcc}ms >= ${windowMs}ms")
                    return true
                }
            }

            return false
        } catch (t: Throwable) {
            Log.e(TAG, "Error during VAD detection", t)
            return false
        }
    }

    /**
     * 重置 VAD 状态（用于新录音会话）
     */
    fun reset() {
        silentMsAcc = 0
        try {
            hasDetectedSpeech = false
            initialDebounceRemainingMs = initialDebounceMs
            speechHangoverRemainingMs = 0
            vad?.reset()
            Log.d(TAG, "VAD reset successfully")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to reset VAD", t)
        }
    }

    /**
     * 释放 VAD 资源
     */
    fun release() {
        try {
            vad?.release()
            Log.d(TAG, "VAD released successfully")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release VAD", t)
        } finally {
            vad = null
        }
    }

    /**
     * 将 PCM 16-bit LE ByteArray 转换为归一化的 FloatArray
     *
     * @param pcm PCM 音频数据
     * @param len 有效数据长度（字节）
     * @return 归一化的音频样本（-1.0 到 1.0）
     */
    private fun pcmToFloatArray(pcm: ByteArray, len: Int): FloatArray {
        if (len <= 0) return FloatArray(0)

        val numSamples = len / 2 // 16-bit = 2 bytes per sample
        val samples = FloatArray(numSamples)

        var i = 0
        var sampleIdx = 0
        while (i + 1 < len && sampleIdx < numSamples) {
            // Little Endian: 低字节在前
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt() and 0xFF
            val pcmValue = (hi shl 8) or lo  // 0..65535

            // 转为有符号 -32768..32767
            val signed = if (pcmValue < 0x8000) pcmValue else pcmValue - 0x10000

            // 归一化到 -1.0 ~ 1.0
            // 使用 32768.0f 避免 -32768 除法溢出，并限制范围
            var normalized = signed / 32768.0f
            if (normalized > 1.0f) normalized = 1.0f
            else if (normalized < -1.0f) normalized = -1.0f

            samples[sampleIdx] = normalized

            i += 2
            sampleIdx++
        }

        return samples
    }
}
