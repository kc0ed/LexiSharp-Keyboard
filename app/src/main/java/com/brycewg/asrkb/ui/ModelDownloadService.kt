package com.brycewg.asrkb.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地模型下载/解压 前台服务：
 * - 采用通知栏通知进度
 * - 支持同时下载不同版本
 * - 解压采用严格校验，临时目录原子替换
 */
class ModelDownloadService : Service() {

  override fun attachBaseContext(newBase: Context?) {
    val wrapped = newBase?.let { LocaleHelper.wrap(it) }
    super.attachBaseContext(wrapped ?: newBase)
  }

  companion object {
    private const val CHANNEL_ID = "model_download"
    private const val CHANNEL_NAME = "Model Download"
    private const val GROUP_ID = "model_download_group"
    private const val SUMMARY_ID = 1000

    private const val ACTION_START = "com.brycewg.asrkb.action.MODEL_DOWNLOAD_START"
    private const val ACTION_CANCEL = "com.brycewg.asrkb.action.MODEL_DOWNLOAD_CANCEL"

    private const val EXTRA_URL = "url"
    private const val EXTRA_VARIANT = "variant"
    private const val EXTRA_KEY = "key"

    fun startDownload(context: Context, url: String, variant: String) {
      val key = (variant + "|" + url).take(200)
      val i = Intent(context, ModelDownloadService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_URL, url)
        putExtra(EXTRA_VARIANT, variant)
        putExtra(EXTRA_KEY, key)
      }
      ContextCompat.startForegroundService(context, i)
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val tasks = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
  private val lastProgressMap = ConcurrentHashMap<String, Int>()
  private val lastNotifyTimeMap = ConcurrentHashMap<String, Long>()
  private lateinit var nm: NotificationManager

  override fun onCreate() {
    super.onCreate()
    nm = getSystemService(NotificationManager::class.java)
    ensureChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val variant = intent.getStringExtra(EXTRA_VARIANT) ?: ""
        val key = intent.getStringExtra(EXTRA_KEY) ?: (variant + "|" + url)
        if (!tasks.containsKey(key)) {
          if (tasks.isEmpty()) startAsForegroundSummary()
          val job = scope.launch { doDownloadTask(key, url, variant) }
          tasks[key] = job
        }
      }
      ACTION_CANCEL -> {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return START_NOT_STICKY
        tasks.remove(key)?.cancel()
        val variant = variantFromKey(key)
        // 让通知进入失败状态
        notifyProgress(key, titleForVariant(variant), 0, text = getString(R.string.sv_download_status_failed), done = true, success = false, force = true)
      }
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    super.onDestroy()
    try { scope.cancel() } catch (_: Throwable) { }
  }

  private fun startAsForegroundSummary() {
    val notif = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(getString(R.string.notif_model_summary_title))
      .setContentText(getString(R.string.notif_model_summary_text))
      .setSmallIcon(R.drawable.ic_stat_download)
      .setGroup(GROUP_ID)
      .setOngoing(true)
      .build()
    startForeground(SUMMARY_ID, notif)
  }

  private suspend fun doDownloadTask(key: String, url: String, variant: String) {
    val notifId = notifIdForKey(key)
    val title = titleForVariant(variant)
    val cancelIntent = PendingIntent.getService(
      this,
      key.hashCode(),
      Intent(this, ModelDownloadService::class.java).apply {
        action = ACTION_CANCEL
        putExtra(EXTRA_KEY, key)
      },
      PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    )

    notifyProgress(key, title, 0, getString(R.string.sv_download_status_downloading, 0), ongoing = true, action = cancelIntent, force = true)

    val cacheFile = File(cacheDir, safeName(key) + ".tar.bz2")
    val ok = OkHttpClient()
    try {
      withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        ok.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
          val body = resp.body ?: throw IllegalStateException("empty body")
          val total = body.contentLength()
          cacheFile.outputStream().use { out ->
            var readSum = 0L
            val buf = ByteArray(128 * 1024)
            body.byteStream().use { ins ->
              while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                readSum += n
                if (total > 0L) {
                  val p = ((readSum * 100) / total).toInt().coerceIn(0, 100)
                  notifyProgress(key, title, p, getString(R.string.sv_download_status_downloading, p), ongoing = true, action = cancelIntent, throttle = true)
                }
              }
            }
          }
        }
      }

      notifyProgress(key, title, 0, getString(R.string.sv_download_status_extracting), indeterminate = true, ongoing = true, force = true)

      // 输出目录
      val base = getExternalFilesDir(null) ?: filesDir
      val outRoot = File(base, "sensevoice")
      val outFinal = if (variant == "small-full") File(outRoot, "small-full") else File(outRoot, "small-int8")
      val tmpDir = File(outRoot, ".tmp_extract_" + safeName(key) + "_" + System.currentTimeMillis())
      if (tmpDir.exists()) tmpDir.deleteRecursively()
      tmpDir.mkdirs()

      // 解压
      extractTarBz2Strict(cacheFile, tmpDir)
      // 校验并定位模型目录
      val modelDir = findModelDir(tmpDir)
      if (modelDir == null ||
        !File(modelDir, "tokens.txt").exists() ||
        !(File(modelDir, "model.int8.onnx").exists() || File(modelDir, "model.onnx").exists())) {
        throw IllegalStateException("model files missing after extract")
      }

      // 原子替换
      if (outFinal.exists()) outFinal.deleteRecursively()
      val renamed = tmpDir.renameTo(outFinal)
      if (!renamed) copyDirRecursively(tmpDir, outFinal)
      try { tmpDir.deleteRecursively() } catch (_: Throwable) { }

      notifyProgress(key, title, 100, getString(R.string.sv_download_status_done), done = true, success = true, force = true)
    } catch (t: Throwable) {
      notifyProgress(key, title, 0, getString(R.string.sv_download_status_failed), done = true, success = false, force = true)
    } finally {
      tasks.remove(key)
      // 若无任务，结束前台与自身
      if (tasks.isEmpty()) {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) { }
        stopSelf()
      }
      try { cacheFile.delete() } catch (_: Throwable) { }
    }
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_model_download), NotificationManager.IMPORTANCE_LOW)
      ch.description = getString(R.string.notif_channel_model_download_desc)
      nm.createNotificationChannel(ch)
    }
  }

  private fun notifIdForKey(key: String): Int = 2000 + (key.hashCode() and 0x7fffffff) % 100000

  private fun safeName(key: String): String = key.replace("[^A-Za-z0-9._-]".toRegex(), "_")

  private fun titleForVariant(variant: String): String {
    return when (variant) {
      "small-full" -> getString(R.string.notif_model_title_full)
      else -> getString(R.string.notif_model_title_int8)
    }
  }

  private fun notifyProgress(
    key: String,
    title: String,
    progress: Int,
    text: String,
    indeterminate: Boolean = false,
    ongoing: Boolean = false,
    done: Boolean = false,
    success: Boolean = false,
    action: PendingIntent? = null,
    throttle: Boolean = false,
    force: Boolean = false
  ) {
    // 节流：避免高频刷新被系统丢弃
    val now = System.currentTimeMillis()
    if (!force && throttle) {
      val lastP = lastProgressMap[key] ?: -1
      val lastTs = lastNotifyTimeMap[key] ?: 0L
      if (progress == lastP && !indeterminate) return
      if (now - lastTs < 500) {
        // 小于 500ms 的更新直接丢弃
        return
      }
    }
    lastProgressMap[key] = progress
    lastNotifyTimeMap[key] = now

    val notifId = notifIdForKey(key)
    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_download)
      .setContentTitle(title)
      .setContentText(text)
      .setOnlyAlertOnce(true)
      .setGroup(GROUP_ID)
      .setOngoing(ongoing && !done)

    if (!done) {
      builder.setProgress(100, if (indeterminate) 0 else progress, indeterminate)
      action?.let {
        builder.addAction(0, getString(R.string.btn_cancel), it)
      }
    } else {
      builder.setProgress(0, 0, false)
    }

    // 点击跳转设置页
    val pi = PendingIntent.getActivity(
      this,
      (key.hashCode() + 1),
      Intent(this, SettingsActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
    )
    builder.setContentIntent(pi)

    nm.notify(notifId, builder.build())
  }

  private fun variantFromKey(key: String): String = key.substringBefore('|')

  // --- 解压与文件工具 ---
  private suspend fun extractTarBz2Strict(file: File, outDir: File) = withContext(Dispatchers.IO) {
    BZip2CompressorInputStream(file.inputStream().buffered(64 * 1024)).use { bz ->
      TarArchiveInputStream(bz).use { tar ->
        var entry = tar.getNextTarEntry()
        val buf = ByteArray(64 * 1024)
        while (entry != null) {
          val outFile = File(outDir, entry.name)
          if (entry.isDirectory) {
            outFile.mkdirs()
          } else {
            outFile.parentFile?.mkdirs()
            var written = 0L
            java.io.BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { bos ->
              while (true) {
                val n = tar.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
                written += n
              }
              bos.flush()
            }
            if (written != entry.size) {
              try { outFile.delete() } catch (_: Throwable) { }
              throw IllegalStateException("tar entry size mismatch: " + entry.name)
            }
          }
          entry = tar.getNextTarEntry()
        }
      }
    }
  }

  private suspend fun copyDirRecursively(src: File, dst: File) {
    withContext(Dispatchers.IO) { copyDirRecursivelyInternal(src, dst) }
  }

  private fun copyDirRecursivelyInternal(src: File, dst: File) {
    if (!src.exists()) return
    if (src.isDirectory) {
      if (!dst.exists()) dst.mkdirs()
      src.listFiles()?.forEach { child ->
        val target = File(dst, child.name)
        if (child.isDirectory) {
          copyDirRecursivelyInternal(child, target)
        } else {
          target.parentFile?.mkdirs()
          child.inputStream().use { ins ->
            java.io.BufferedOutputStream(FileOutputStream(target), 64 * 1024).use { bos ->
              val buf = ByteArray(64 * 1024)
              while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
              }
              bos.flush()
            }
          }
        }
      }
    } else {
      dst.parentFile?.mkdirs()
      src.inputStream().use { ins ->
        java.io.BufferedOutputStream(FileOutputStream(dst), 64 * 1024).use { bos ->
          val buf = ByteArray(64 * 1024)
          while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            bos.write(buf, 0, n)
          }
          bos.flush()
        }
      }
    }
  }

  private fun findModelDir(root: File): File? {
    if (!root.exists()) return null
    val direct = File(root, "tokens.txt")
    if (direct.exists()) return root
    val subs = root.listFiles() ?: return null
    subs.forEach { f ->
      if (f.isDirectory) {
        val t = File(f, "tokens.txt")
        if (t.exists()) return f
      }
    }
    return null
  }
}
