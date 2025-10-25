package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
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
 * - 预热策略：两帧探测 + 仅在确认“坏源”时回退到 MIC
 *
 * @param context Android Context
 * @param sampleRate 采样率（Hz），默认 16000
 * @param channelConfig 声道配置，默认单声道
 * @param audioFormat 音频格式，默认 PCM 16-bit
 * @param chunkMillis 每个音频块的时长（ms），默认 200ms
 */
class AudioCaptureManager(
    private val context: Context,
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val chunkMillis: Int = 200
) {
    private val bytesPerSample = 2 // 16bit mono PCM
    private val debugLoggingEnabled: Boolean by lazy {
        try {
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read debuggable flag", t)
            false
        }
    }

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
     * 3. 执行预热逻辑（两帧小窗探测 + 仅在两帧近乎全零时回退为 MIC）
     * 4. 循环读取音频数据并通过 Flow emit
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

        // 4. 回退到 MIC（如果 VOICE_RECOGNITION 失败：构造异常或未初始化）
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "VOICE_RECOGNITION source unavailable, falling back to MIC")
            try {
                recorder?.release()
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
     * 预热 AudioRecord：两帧小窗探测 + 仅在确认“坏源”时回退为 MIC
     *
     * - 读取第1帧：若明显存在有效信号，直接返回该帧，避免额外时延
     * - 读取第2帧：与第1帧共同判断是否“近乎全零”
     * - 若两帧均近乎全零：停止并释放当前源，重建为 MIC 源，再读取一帧返回
     */
    private fun warmupRecorder(
        current: AudioRecord,
        buf: ByteArray,
        bufferSize: Int
    ): Pair<AudioRecord, ByteArray?> {
        if (!hasPermission()) {
            val error = SecurityException("RECORD_AUDIO permission was revoked during warmup")
            Log.e(TAG, "Permission check failed during warmup", error)
            throw error
        }

        var recorder = current
        if (debugLoggingEnabled) Log.d(TAG, "Warmup: probing 2 frames")

        // 第1帧
        val preRead1 = try {
            recorder.read(buf, 0, buf.size)
        } catch (t: Throwable) {
            Log.e(TAG, "Error during warmup probe read #1", t)
            -1
        }
        var frame1Bytes: ByteArray? = null
        var frame1HasSignal = false
        var frame1IsNearZero = false
        if (preRead1 > 0) {
            val st1 = computeFrameStats16le(buf, preRead1, 30)
            val rms1sq = if (st1.sampleCount > 0) st1.sumSquares.toDouble() / st1.sampleCount else 0.0
            frame1HasSignal = st1.countAboveThreshold > 0
            frame1IsNearZero = (st1.maxAbs < 12 && rms1sq < 16.0 && st1.countAboveThreshold == 0)
            if (debugLoggingEnabled) {
                val rms1 = kotlin.math.sqrt(rms1sq)
                Log.d(TAG, "Warmup frame#1: read=$preRead1, max=${st1.maxAbs}, rms=${"%.1f".format(rms1)}, cnt>30=${st1.countAboveThreshold}, nearZero=$frame1IsNearZero")
            }
            if (frame1HasSignal) frame1Bytes = buf.copyOf(preRead1)
        }

        // 若第1帧已确认存在有效信号，则直接返回
        if (frame1HasSignal && frame1Bytes != null) {
            if (debugLoggingEnabled) Log.d(TAG, "Warmup: signal confirmed on frame#1, short-circuit")
            return Pair(recorder, frame1Bytes)
        }

        // 第2帧
        val preRead2 = try {
            recorder.read(buf, 0, buf.size)
        } catch (t: Throwable) {
            Log.e(TAG, "Error during warmup probe read #2", t)
            -1
        }
        var frame2Bytes: ByteArray? = null
        var frame2HasSignal = false
        var frame2IsNearZero = false
        if (preRead2 > 0) {
            val st2 = computeFrameStats16le(buf, preRead2, 30)
            val rms2sq = if (st2.sampleCount > 0) st2.sumSquares.toDouble() / st2.sampleCount else 0.0
            frame2HasSignal = st2.countAboveThreshold > 0
            frame2IsNearZero = (st2.maxAbs < 12 && rms2sq < 16.0 && st2.countAboveThreshold == 0)
            if (debugLoggingEnabled) {
                val rms2 = kotlin.math.sqrt(rms2sq)
                Log.d(TAG, "Warmup frame#2: read=$preRead2, max=${st2.maxAbs}, rms=${"%.1f".format(rms2)}, cnt>30=${st2.countAboveThreshold}, nearZero=$frame2IsNearZero")
            }
            if (frame2HasSignal) frame2Bytes = buf.copyOf(preRead2)
        }

        val nearZeroBoth = frame1IsNearZero && frame2IsNearZero
        var firstChunk: ByteArray? = when {
            frame1HasSignal -> frame1Bytes
            frame2HasSignal -> frame2Bytes
            else -> null
        }

        if (nearZeroBoth) {
            // 两帧均近乎全零：重建为 MIC 源
            Log.i(TAG, "Warmup: near-zero on both frames, rebuilding with MIC source")
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

        return Pair(recorder, firstChunk)
    }
}
