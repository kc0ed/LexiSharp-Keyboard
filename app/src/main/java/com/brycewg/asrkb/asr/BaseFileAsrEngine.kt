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

    protected open val sampleRate: Int = 16000
    protected open val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    protected open val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
    protected open val chunkMillis: Int = 200
    protected open val maxRecordDurationMillis: Int = 30 * 60 * 1000 // 30 分钟

    private val bytesPerSample = 2 // 16bit mono

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        if (!ensureReady()) return
        running.set(true)
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            try {
                val pcm = recordAudio() ?: return@launch
                recognize(pcm)
            } finally {
                running.set(false)
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
        var hasSignal = false
        if (preRead > 0) {
            hasSignal = hasNonZeroAmplitude(buf, preRead)
        }
        if (!hasSignal) {
            try { recorder.stop() } catch (_: Throwable) {}
            try { recorder.release() } catch (_: Throwable) {}
            val newRecorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (_: Throwable) { null }
            if (newRecorder == null || newRecorder.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError(context.getString(R.string.error_audio_init_failed))
                return null
            }
            recorder = newRecorder
            try { recorder.startRecording() } catch (_: SecurityException) {
                listener.onError(context.getString(R.string.error_record_permission_denied))
                try { recorder.release() } catch (_: Throwable) {}
                return null
            } catch (t: Throwable) {
                listener.onError(
                    context.getString(R.string.error_audio_error, t.message ?: "")
                )
                try { recorder.release() } catch (_: Throwable) {}
                return null
            }
            val pre2 = try { recorder.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
            if (pre2 > 0) pcmBuffer.write(buf, 0, pre2)
        } else if (preRead > 0) {
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
