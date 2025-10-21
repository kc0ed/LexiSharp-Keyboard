package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 音频采集管理器
 *
 * 封装 AudioRecord 的初始化、权限检查、预热和音频流读取逻辑。
 * 提供简洁的音频流接口供 ASR 引擎使用，消除重复代码。
 *
 * ## 功能特性
 * - 自动处理音频源回退（VOICE_RECOGNITION -> MIC）
 * - 支持兼容模式与非兼容模式的预热策略
 * - 基于 Kotlin Flow 的音频数据流
 * - 完善的错误处理与日志记录
 *
 * ## 使用示例
 * ```kotlin
 * val manager = AudioCaptureManager(context, prefs = prefs)
 * if (!manager.hasPermission()) {
 *     // 请求权限
 *     return
 * }
 *
 * manager.startCapture().collect { audioChunk ->
 *     // 处理音频数据
 *     sendToAsrEngine(audioChunk)
 * }
 * ```
 *
 * @param context Android Context
 * @param sampleRate 采样率（Hz），默认 16000
 * @param channelConfig 声道配置，默认单声道
 * @param audioFormat 音频格式，默认 PCM 16-bit
 * @param chunkMillis 每个音频块的时长（ms），默认 200ms
 * @param prefs 应用偏好设置，用于读取兼容模式配置
 */
class AudioCaptureManager(
    private val context: Context,
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val chunkMillis: Int = 200,
    private val prefs: Prefs
) {
    private val bytesPerSample = 2 // 16bit mono PCM

    companion object {
        private const val TAG = "AudioCaptureManager"
    }

    /**
     * 检查录音权限
     *
     * @return 如果具有 RECORD_AUDIO 权限返回 true，否则返回 false
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 启动音频采集，返回音频数据流
     *
     * 该方法会：
     * 1. 检查录音权限
     * 2. 初始化 AudioRecord（优先 VOICE_RECOGNITION，失败时回退到 MIC）
     * 3. 根据 prefs.audioCompatPreferMic 执行预热逻辑
     * 4. 循环读取音频数据并通过 Flow emit
     *
     * ## 兼容模式说明
     * - **兼容模式**（audioCompatPreferMic = true）：连续多帧预热并写入缓冲，避免会话闪断
     * - **非兼容模式**（audioCompatPreferMic = false）：单帧探测 + 自动回退，丢弃无信号的预热数据
     *
     * @return Flow<ByteArray> 音频数据流，每个 ByteArray 是一个音频块（约 chunkMillis 时长）
     * @throws SecurityException 如果缺少录音权限
     * @throws IllegalStateException 如果 AudioRecord 初始化失败
     */
    fun startCapture(): Flow<ByteArray> = flow {
        // 1. 权限检查
        if (!hasPermission()) {
            val error = SecurityException("Missing RECORD_AUDIO permission")
            Log.e(TAG, "Permission check failed", error)
            throw error
        }

        // 2. 计算缓冲区大小
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val chunkBytes = ((sampleRate * chunkMillis) / 1000) * bytesPerSample
        val bufferSize = maxOf(minBuffer, chunkBytes)

        // 3. 初始化 AudioRecord（优先 VOICE_RECOGNITION）
        var recorder: AudioRecord? = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create AudioRecord with VOICE_RECOGNITION", t)
            null
        }

        // 4. 回退到 MIC（如果 VOICE_RECOGNITION 失败）
        if (recorder != null && recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "VOICE_RECOGNITION source not initialized, falling back to MIC")
            try {
                recorder.release()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to release failed recorder", t)
            }
            recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create AudioRecord with MIC", t)
                null
            }
        }

        // 5. 最终校验
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            val error = IllegalStateException("AudioRecord initialization failed for both VOICE_RECOGNITION and MIC sources")
            Log.e(TAG, "AudioRecord initialization failed", error)
            throw error
        }

        var activeRecorder = recorder
        val buf = ByteArray(chunkBytes)

        try {
            // 6. 启动录音
            try {
                activeRecorder.startRecording()
                Log.d(TAG, "AudioRecord started successfully")
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException during startRecording", se)
                throw se
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start recording", t)
                throw IllegalStateException("Failed to start recording", t)
            }

            // 7. 预热并获取可能替换的 recorder 和第一块数据
            val warmupResult = warmupRecorder(activeRecorder, buf, bufferSize)
            activeRecorder = warmupResult.first
            val firstChunk = warmupResult.second

            // 8. Emit 第一块数据（如果预热产生了数据）
            if (firstChunk != null && firstChunk.isNotEmpty()) {
                emit(firstChunk)
            }

            // 9. 持续读取音频数据
            while (true) {
                val read = try {
                    activeRecorder.read(buf, 0, buf.size)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error reading audio data", t)
                    throw IllegalStateException("Error reading audio data", t)
                }

                if (read > 0) {
                    // 复制有效数据并 emit
                    val chunk = buf.copyOf(read)
                    emit(chunk)
                } else if (read < 0) {
                    val error = IllegalStateException("AudioRecord read error: $read")
                    Log.e(TAG, "AudioRecord read returned error code", error)
                    throw error
                }
            }
        } finally {
            // 10. 清理资源
            try {
                activeRecorder.stop()
                Log.d(TAG, "AudioRecord stopped")
            } catch (t: Throwable) {
                Log.e(TAG, "Error stopping AudioRecord", t)
            }
            try {
                activeRecorder.release()
                Log.d(TAG, "AudioRecord released")
            } catch (t: Throwable) {
                Log.e(TAG, "Error releasing AudioRecord", t)
            }
        }
    }

    /**
     * 预热 AudioRecord 并根据配置决定是否重建
     *
     * ## 兼容模式（audioCompatPreferMic = true）
     * - 连续读取 3 帧数据作为预热
     * - 所有预热数据合并返回，避免会话闪断
     * - 不进行音频源切换
     *
     * ## 非兼容模式（audioCompatPreferMic = false）
     * - 读取 1 帧预热数据
     * - 检测是否包含非零振幅信号
     * - 如果无信号，stop + release 并重建为 MIC 源
     * - 重建后再读取 1 帧并返回
     *
     * @param current 当前的 AudioRecord 实例（已启动）
     * @param buf 音频数据缓冲区
     * @param bufferSize AudioRecord 的缓冲区大小
     * @return Pair<AudioRecord, ByteArray?> 最终使用的 recorder 和预热产生的数据
     * @throws SecurityException 如果权限被撤销
     * @throws IllegalStateException 如果重建失败
     */
    private fun warmupRecorder(
        current: AudioRecord,
        buf: ByteArray,
        bufferSize: Int
    ): Pair<AudioRecord, ByteArray?> {
        // 再次校验权限（避免运行时被撤销）
        if (!hasPermission()) {
            val error = SecurityException("RECORD_AUDIO permission was revoked during warmup")
            Log.e(TAG, "Permission check failed during warmup", error)
            throw error
        }

        var recorder = current
        val compat = try {
            prefs.audioCompatPreferMic
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read audioCompatPreferMic preference, defaulting to false", t)
            false
        }

        return if (compat) {
            // 兼容模式：连续多帧预热，不重建
            Log.d(TAG, "Warmup: compat mode enabled, reading 3 warmup frames")
            val warmupFrames = 3
            val warmupData = mutableListOf<ByteArray>()

            repeat(warmupFrames) { i ->
                val n = try {
                    recorder.read(buf, 0, buf.size)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error during warmup frame $i", t)
                    -1
                }
                if (n > 0) {
                    warmupData.add(buf.copyOf(n))
                    Log.d(TAG, "Warmup frame $i: read $n bytes")
                }
            }

            // 合并所有预热数据
            val combined = if (warmupData.isNotEmpty()) {
                val totalSize = warmupData.sumOf { it.size }
                val result = ByteArray(totalSize)
                var offset = 0
                warmupData.forEach { chunk ->
                    chunk.copyInto(result, offset)
                    offset += chunk.size
                }
                Log.d(TAG, "Warmup: combined ${warmupData.size} frames into $totalSize bytes")
                result
            } else {
                null
            }

            Pair(recorder, combined)
        } else {
            // 非兼容模式：单帧探测 + 回退
            Log.d(TAG, "Warmup: non-compat mode, probing signal")
            val preRead = try {
                recorder.read(buf, 0, buf.size)
            } catch (t: Throwable) {
                Log.e(TAG, "Error during warmup probe read", t)
                -1
            }

            var hasSignal = false
            var firstChunk: ByteArray? = null

            if (preRead > 0) {
                hasSignal = hasNonZeroAmplitude(buf, preRead)
                Log.d(TAG, "Warmup probe: read $preRead bytes, hasSignal=$hasSignal")
                if (hasSignal) {
                    firstChunk = buf.copyOf(preRead)
                }
            }

            if (!hasSignal) {
                // 无信号：重建为 MIC 源
                Log.i(TAG, "Warmup: no signal detected, rebuilding with MIC source")
                try {
                    recorder.stop()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error stopping recorder during rebuild", t)
                }
                try {
                    recorder.release()
                } catch (t: Throwable) {
                    Log.e(TAG, "Error releasing recorder during rebuild", t)
                }

                val newRecorder = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to create new AudioRecord with MIC during warmup", t)
                    null
                }

                if (newRecorder == null || newRecorder.state != AudioRecord.STATE_INITIALIZED) {
                    val error = IllegalStateException("Failed to rebuild AudioRecord with MIC source during warmup")
                    Log.e(TAG, "AudioRecord rebuild failed", error)
                    throw error
                }

                recorder = newRecorder
                try {
                    recorder.startRecording()
                    Log.d(TAG, "Warmup: MIC recorder started")
                } catch (se: SecurityException) {
                    Log.e(TAG, "SecurityException during MIC recorder start", se)
                    try {
                        recorder.release()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error releasing recorder after SecurityException", t)
                    }
                    throw se
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to start MIC recorder", t)
                    try {
                        recorder.release()
                    } catch (releaseError: Throwable) {
                        Log.e(TAG, "Error releasing recorder after start failure", releaseError)
                    }
                    throw IllegalStateException("Failed to start MIC recorder", t)
                }

                // 重新读取一帧
                val pre2 = try {
                    recorder.read(buf, 0, buf.size)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error reading from rebuilt MIC recorder", t)
                    -1
                }
                if (pre2 > 0) {
                    firstChunk = buf.copyOf(pre2)
                    Log.d(TAG, "Warmup: MIC recorder read $pre2 bytes")
                }
            }

            Pair(recorder, firstChunk)
        }
    }
}
