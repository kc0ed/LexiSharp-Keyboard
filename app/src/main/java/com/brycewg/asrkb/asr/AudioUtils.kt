package com.brycewg.asrkb.asr

/**
 * 振幅非零判定阈值
 *
 * 用于检测音频采样是否包含有效信号。
 * 经验值：环境噪音通常 < 30，正常语音信号 > 100。
 */
private const val AMPLITUDE_NON_ZERO_THRESHOLD = 30

// 更保守的“坏源”判定阈值（近乎全零）。
// 仅用于预热阶段识别 VOICE_RECOGNITION 管线失效的场景；不用于用户是否开口的判断。
private const val BAD_SOURCE_MAX_ABS_THRESHOLD = 12
private const val BAD_SOURCE_RMS_THRESHOLD = 4.0

/**
 * 通用的音频处理工具方法。
 */

/**
 * 检查音频缓冲区是否包含非零振幅的采样
 *
 * 遍历 PCM 16位小端格式的音频数据，检测是否存在绝对值超过阈值的采样点。
 * 用于预热阶段判断音频源是否产生了有效信号。
 *
 * @param buf PCM 16位小端格式的音频数据
 * @param len 要检查的字节数（必须是偶数，因为每个采样点占 2 字节）
 * @return 如果存在绝对值大于 [AMPLITUDE_NON_ZERO_THRESHOLD] 的采样则返回 true
 */
internal fun hasNonZeroAmplitude(buf: ByteArray, len: Int): Boolean {
    var i = 0
    while (i + 1 < len) {
        val lo = buf[i].toInt() and 0xFF
        val hi = buf[i + 1].toInt() and 0xFF
        val s = (hi shl 8) or lo
        val v = if (s < 0x8000) s else s - 0x10000
        if (kotlin.math.abs(v) > AMPLITUDE_NON_ZERO_THRESHOLD) return true
        i += 2
    }
    return false
}

/**
 * 计算 16-bit PCM 小端的最大绝对振幅
 */
internal fun maxAbsAmplitude16le(buf: ByteArray, len: Int): Int {
    var i = 0
    var max = 0
    while (i + 1 < len) {
        val lo = buf[i].toInt() and 0xFF
        val hi = buf[i + 1].toInt() and 0xFF
        val s = (hi shl 8) or lo
        val v = if (s < 0x8000) s else s - 0x10000
        val a = kotlin.math.abs(v)
        if (a > max) max = a
        i += 2
    }
    return max
}

/**
 * 计算 16-bit PCM 小端的 RMS（均方根）幅度
 */
internal fun rmsAmplitude16le(buf: ByteArray, len: Int): Double {
    var i = 0
    var sumSq = 0.0
    var count = 0
    while (i + 1 < len) {
        val lo = buf[i].toInt() and 0xFF
        val hi = buf[i + 1].toInt() and 0xFF
        val s = (hi shl 8) or lo
        val v = if (s < 0x8000) s else s - 0x10000
        sumSq += (v * v).toDouble()
        count++
        i += 2
    }
    if (count == 0) return 0.0
    return kotlin.math.sqrt(sumSq / count)
}

/**
 * 统计超过指定阈值的样本数（绝对值）
 */
internal fun countSamplesAboveThreshold(buf: ByteArray, len: Int, threshold: Int): Int {
    var i = 0
    var cnt = 0
    while (i + 1 < len) {
        val lo = buf[i].toInt() and 0xFF
        val hi = buf[i + 1].toInt() and 0xFF
        val s = (hi shl 8) or lo
        val v = if (s < 0x8000) s else s - 0x10000
        if (kotlin.math.abs(v) > threshold) cnt++
        i += 2
    }
    return cnt
}

@Suppress("unused")
internal fun isLikelyBadSource(maxAbs: Int, rms: Double, countAbove30: Int): Boolean {
    return (maxAbs < BAD_SOURCE_MAX_ABS_THRESHOLD && rms < BAD_SOURCE_RMS_THRESHOLD && countAbove30 == 0)
}

data class FrameStats(
    val maxAbs: Int,
    val sumSquares: Long,
    val countAboveThreshold: Int,
    val sampleCount: Int
)

/**
 * 单次遍历计算帧统计数据（maxAbs、sumSquares、countAboveThreshold）
 */
internal fun computeFrameStats16le(buf: ByteArray, len: Int, threshold: Int = 30): FrameStats {
    var i = 0
    var maxAbs = 0
    var sumSquares = 0L
    var count = 0
    var samples = 0
    while (i + 1 < len) {
        val lo = buf[i].toInt() and 0xFF
        val hi = buf[i + 1].toInt() and 0xFF
        val s = (hi shl 8) or lo
        val v = if (s < 0x8000) s else s - 0x10000
        val a = kotlin.math.abs(v)
        if (a > maxAbs) maxAbs = a
        sumSquares += (v * v).toLong()
        if (a > threshold) count++
        samples++
        i += 2
    }
    return FrameStats(maxAbs, sumSquares, count, samples)
}
