package com.brycewg.asrkb.asr

/**
 * 简易静音检测器：
 * - 按块（ByteArray，PCM 16kHz 单声道 16-bit）计算该块的峰值幅度。
 * - 若连续静音时长超过 windowMs，则返回应当停止。
 * - 灵敏度 1-10：值越大阈值越高，更容易判定为无人说话。
 */
class SilenceDetector(
  private val sampleRate: Int,
  private val windowMs: Int,
  sensitivityLevel: Int
) {
  private val threshold: Int
  private var silentMsAcc: Int = 0

  init {
    val lvl = sensitivityLevel.coerceIn(1, LEVELS)
    threshold = THRESHOLDS[lvl - 1]
  }

  /**
   * 处理一帧音频数据，返回是否应当根据静音窗口停止录音。
   */
  fun shouldStop(buf: ByteArray, len: Int): Boolean {
    val frameMs = if (sampleRate > 0) ((len / 2) * 1000) / sampleRate else 0
    val maxAbs = peakAbsAmplitude(buf, len)
    val isSilent = maxAbs < threshold
    if (isSilent) {
      silentMsAcc += frameMs
      if (silentMsAcc >= windowMs) return true
    } else {
      silentMsAcc = 0
    }
    return false
  }

  private fun peakAbsAmplitude(buf: ByteArray, len: Int): Int {
    var max = 0
    var i = 0
    while (i + 1 < len) {
      val lo = buf[i].toInt() and 0xFF
      val hi = buf[i + 1].toInt() and 0xFF
      val s = (hi shl 8) or lo // 0..65535 (LE)
      val v = if (s < 0x8000) s else s - 0x10000 // 转为有符号 -32768..32767
      val abs = kotlin.math.abs(v)
      if (abs > max) max = abs
      i += 2
    }
    return max
  }

  companion object {
    // 灵敏度档位总数
    const val LEVELS: Int = 15
    // 非线性阈值表（16-bit 绝对幅度），小档位更细，后段增量更大
    // 经验值：常见设备环境静音 < ~300-600，正常语音峰值 ~300-1200。
    // 1..5：步长 50；6..10：步长 100；11..15：步长 150/100
    val THRESHOLDS: IntArray = intArrayOf(
      100, 150, 200, 250, 300, // 1..5（细）
      400, 500, 600, 700, 800, // 6..10（中）
      950, 1100, 1250, 1400, 1500 // 11..15（粗）
    )
  }
}
