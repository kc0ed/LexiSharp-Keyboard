package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基础的文件识别 ASR 引擎，封装了麦克风采集、静音判停等通用逻辑，
 * 子类只需实现具体的识别请求即可。
 */
abstract class BaseFileAsrEngine(
    protected val context: Context,
    private val scope: CoroutineScope,
    protected val prefs: Prefs,
    protected val listener: StreamingAsrEngine.Listener,
    protected val onRequestDuration: ((Long) -> Unit)? = null
) : StreamingAsrEngine {

    private val running = AtomicBoolean(false)
    private var audioJob: Job? = null
    private var processingJob: Job? = null
    private var segmentChan: Channel<ByteArray>? = null

    protected open val sampleRate: Int = 16000
    protected open val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    protected open val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
    protected open val chunkMillis: Int = 200
    // 非流式录音的最大时长（子类按供应商覆盖）。
    // 达到该时长会立即结束录音并触发一次识别请求，以避免超过服务商限制。
    protected open val maxRecordDurationMillis: Int = 30 * 60 * 1000 // 默认 30 分钟

    private val bytesPerSample = 2 // 16bit mono

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        if (!ensureReady()) return
        running.set(true)
        audioJob?.cancel()
        processingJob?.cancel()
        // 使用无界队列以避免在停止录音前的最后一段被 trySend 丢弃
        val chan = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        segmentChan = chan
        // 顺序消费识别请求，确保结果按段落顺序提交
        processingJob = scope.launch(Dispatchers.IO) {
            try {
                for (seg in chan) {
                    try {
                        recognize(seg)
                    } catch (t: Throwable) {
                        try { listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")) } catch (_: Throwable) {}
                    }
                }
            } finally {
                processingJob = null
            }
        }
        // 持续录音并按上限切段，投递到识别队列
        audioJob = scope.launch(Dispatchers.IO) {
            try {
                recordAndEnqueueSegments(chan)
            } finally {
                running.set(false)
                try { chan.close() } catch (_: Throwable) {}
                audioJob = null
            }
        }
    }

    override fun stop() {
        running.set(false)
    }

    /**
     * 识别前的准备校验，可在子类中扩展，如检查 API Key 是否配置。
     */
    protected open fun ensureReady(): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            listener.onError(context.getString(R.string.error_record_permission_denied))
            return false
        }
        return true
    }

    private fun recordAudio(): ByteArray? {
        // 额外的就地权限检查，便于 Lint 数据流分析识别到保护分支
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            listener.onError(context.getString(R.string.error_record_permission_denied))
            return null
        }
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val chunkBytes = ((sampleRate * chunkMillis) / 1000) * bytesPerSample
        val bufferSize = maxOf(minBuffer, chunkBytes)

        var recorder: AudioRecord? = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_audio_init_cannot, t.message ?: "")
            )
            null
        }

        if (recorder != null && recorder.state != AudioRecord.STATE_INITIALIZED) {
            try { recorder.release() } catch (_: Throwable) {}
            recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (_: Throwable) { null }
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError(context.getString(R.string.error_audio_init_failed))
                return null
            }
        }

        var activeRecorder = recorder ?: return null

        val pcmBuffer = ByteArrayOutputStream()
        try {
            try {
                activeRecorder.startRecording()
            } catch (_: SecurityException) {
                listener.onError(context.getString(R.string.error_record_permission_denied))
                return null
            } catch (t: Throwable) {
                listener.onError(
                    context.getString(R.string.error_audio_error, t.message ?: "")
                )
                return null
            }

            val buf = ByteArray(chunkBytes)
            val warmed = warmupRecorder(activeRecorder, buf, bufferSize, pcmBuffer)
                ?: return null
            activeRecorder = warmed

            val silenceDetector = if (prefs.autoStopOnSilenceEnabled)
                SilenceDetector(sampleRate, prefs.autoStopSilenceWindowMs, prefs.autoStopSilenceSensitivity)
            else null
            val maxBytes = (maxRecordDurationMillis / 1000.0 * sampleRate * bytesPerSample).toInt()

            while (true) {
                if (!running.get()) break
                val read = try { activeRecorder.read(buf, 0, buf.size) } catch (t: Throwable) {
                    listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
                    return null
                }
                if (read > 0) {
                    pcmBuffer.write(buf, 0, read)
                    if (silenceDetector?.shouldStop(buf, read) == true) {
                        running.set(false)
                        try { listener.onStopped() } catch (_: Throwable) {}
                        break
                    }
                    if (pcmBuffer.size() >= maxBytes) {
                        // 录音达到本地上限：退出循环，由调用方负责后续处理
                        break
                    }
                }
            }
        } finally {
            try { activeRecorder.stop() } catch (_: Throwable) {}
            try { activeRecorder.release() } catch (_: Throwable) {}
        }

        val pcmBytes = pcmBuffer.toByteArray()
        if (pcmBytes.isEmpty()) {
            listener.onError(context.getString(R.string.error_audio_empty))
            return null
        }
        return pcmBytes
    }

    /**
     * 连续录音并按 [maxRecordDurationMillis] 切分，将片段依次投递到 [chan]。
     * - 段间不停止/重建 AudioRecord，尽量保证采集连续。
     * - 仅在静音判停或用户停止时回调 onStopped()，切段不打断 UI 的“正在聆听”。
     */
    private fun recordAndEnqueueSegments(chan: Channel<ByteArray>) {
        // 权限兜底
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            try { listener.onError(context.getString(R.string.error_record_permission_denied)) } catch (_: Throwable) {}
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val chunkBytes = ((sampleRate * chunkMillis) / 1000) * bytesPerSample
        val bufferSize = maxOf(minBuffer, chunkBytes)

        var recorder: AudioRecord? = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (t: Throwable) {
            try { listener.onError(context.getString(R.string.error_audio_init_cannot, t.message ?: "")) } catch (_: Throwable) {}
            null
        }
        if (recorder != null && recorder.state != AudioRecord.STATE_INITIALIZED) {
            try { recorder.release() } catch (_: Throwable) {}
            recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (_: Throwable) { null }
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                try { listener.onError(context.getString(R.string.error_audio_init_failed)) } catch (_: Throwable) {}
                return
            }
        }
        var activeRecorder = recorder ?: return

        val currentSeg = ByteArrayOutputStream()
        val pendingList: java.util.ArrayDeque<ByteArray> = java.util.ArrayDeque()
        try {
            try { activeRecorder.startRecording() } catch (_: SecurityException) {
                try { listener.onError(context.getString(R.string.error_record_permission_denied)) } catch (_: Throwable) {}
                return
            } catch (t: Throwable) {
                try { listener.onError(context.getString(R.string.error_audio_error, t.message ?: "")) } catch (_: Throwable) {}
                return
            }

            val buf = ByteArray(chunkBytes)
            val warmed = warmupRecorder(activeRecorder, buf, bufferSize, currentSeg) ?: return
            activeRecorder = warmed

            val silenceDetector = if (prefs.autoStopOnSilenceEnabled)
                SilenceDetector(sampleRate, prefs.autoStopSilenceWindowMs, prefs.autoStopSilenceSensitivity)
            else null
            val maxBytes = (maxRecordDurationMillis / 1000.0 * sampleRate * bytesPerSample).toInt()

            while (true) {
                if (!running.get()) break

                // 尝试非阻塞地刷出待发送片段（若存在）
                while (!pendingList.isEmpty()) {
                    val head = pendingList.peekFirst()
                    val r = chan.trySend(head)
                    if (r.isSuccess) {
                        pendingList.removeFirst()
                    } else break
                }

                val read = try { activeRecorder.read(buf, 0, buf.size) } catch (t: Throwable) {
                    try { listener.onError(context.getString(R.string.error_audio_error, t.message ?: "")) } catch (_: Throwable) {}
                    return
                }
                if (read > 0) {
                    currentSeg.write(buf, 0, read)

                    // 静音自动判停：结束录音，推送最后一段
                    if (silenceDetector?.shouldStop(buf, read) == true) {
                        running.set(false)
                        try { listener.onStopped() } catch (_: Throwable) {}
                        val last = currentSeg.toByteArray()
                        if (last.isNotEmpty()) {
                            // 刷出已有待发送
                            while (!pendingList.isEmpty()) {
                                val head = pendingList.peekFirst()
                                val ok = chan.trySend(head).isSuccess
                                if (ok) pendingList.removeFirst() else break
                            }
                            // 尝试直接投递最后一段；不成则加入待发送
                            val ok2 = chan.trySend(last).isSuccess
                            if (!ok2) pendingList.addLast(last)
                            // 关键：已投递/入队最后一段后，重置缓冲，避免 finally 重复推送
                            currentSeg.reset()
                        }
                        break
                    }

                    // 达到上限：切出一个片段，不打断录音
                    if (currentSeg.size() >= maxBytes) {
                        val out = currentSeg.toByteArray()
                        currentSeg.reset()
                        // 先尝试刷出之前的待发送段
                        while (!pendingList.isEmpty()) {
                            val head = pendingList.peekFirst()
                            val ok = chan.trySend(head).isSuccess
                            if (ok) pendingList.removeFirst() else break
                        }
                        // 再投递当前片段；不成则加入待发送队列
                        val ok2 = chan.trySend(out).isSuccess
                        if (!ok2) pendingList.addLast(out)
                    }
                }
            }
        } finally {
            try { activeRecorder.stop() } catch (_: Throwable) {}
            try { activeRecorder.release() } catch (_: Throwable) {}

            // 录音结束后，推送任何遗留的待发送段与缓冲
            while (!pendingList.isEmpty()) {
                try {
                    val head = pendingList.removeFirst()
                    chan.trySend(head)
                } catch (_: Throwable) { break }
            }
            val tail2 = currentSeg.toByteArray()
            if (tail2.isNotEmpty()) {
                try { chan.trySend(tail2) } catch (_: Throwable) {}
            }
        }
    }

    private fun warmupRecorder(
        current: AudioRecord,
        buf: ByteArray,
        bufferSize: Int,
        pcmBuffer: ByteArrayOutputStream
    ): AudioRecord? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            listener.onError(context.getString(R.string.error_record_permission_denied))
            return null
        }

        var recorder = current
        val preRead = try { recorder.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
        if (preRead > 0) {
            pcmBuffer.write(buf, 0, preRead)
        }
        return recorder
    }

    protected fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val headerSize = 44
        val dataSize = pcm.size
        val totalDataLen = dataSize + 36
        val out = ByteArrayOutputStream(headerSize + dataSize)
        out.write("RIFF".toByteArray())
        out.write(intToBytesLE(totalDataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToBytesLE(16))
        out.write(shortToBytesLE(1))
        out.write(shortToBytesLE(channels))
        out.write(intToBytesLE(sampleRate))
        out.write(intToBytesLE(byteRate))
        out.write(shortToBytesLE((channels * bitsPerSample / 8)))
        out.write(shortToBytesLE(bitsPerSample))
        out.write("data".toByteArray())
        out.write(intToBytesLE(dataSize))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intToBytesLE(v: Int): ByteArray {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(v)
        return bb.array()
    }

    private fun shortToBytesLE(v: Int): ByteArray {
        val bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(v.toShort())
        return bb.array()
    }

    /**
     * 交由子类实现具体的识别流程，如上传音频并解析结果。
     */
    protected abstract suspend fun recognize(pcm: ByteArray)
}
