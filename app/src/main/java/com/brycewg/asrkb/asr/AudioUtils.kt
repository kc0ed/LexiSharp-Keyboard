package com.brycewg.asrkb.asr

/**
 * 振幅非零判定阈值
 *
 * 用于检测音频采样是否包含有效信号。
 * 经验值：环境噪音通常 < 30，正常语音信号 > 100。
 */
private const val AMPLITUDE_NON_ZERO_THRESHOLD = 30

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
