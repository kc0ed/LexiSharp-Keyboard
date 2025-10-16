package com.brycewg.asrkb.asr

/**
 * 通用的音频处理工具方法。
 */
internal fun hasNonZeroAmplitude(buf: ByteArray, len: Int): Boolean {
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
