package com.brycewg.asrkb.ui.settings.backup

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupSettingsActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "BackupSettingsActivity"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val WEBDAV_DIRECTORY = "LexiSharp"
        private const val WEBDAV_FILENAME = "asr_keyboard_settings.json"
    }

    private lateinit var prefs: Prefs
    private val http by lazy { OkHttpClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_settings)

        prefs = Prefs(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.title_backup_settings)
        toolbar.setNavigationOnClickListener { finish() }

        setupFileSection()
        setupWebdavSection()
    }

    // ================= 文件导入/导出 =================
    private fun setupFileSection() {
        val btnExport = findViewById<MaterialButton>(R.id.btnExportToFile)
        val btnImport = findViewById<MaterialButton>(R.id.btnImportFromFile)

        val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            if (uri != null) exportSettings(uri)
        }

        val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) importSettings(uri)
        }

        btnExport.setOnClickListener {
            val fileName = "asr_keyboard_settings_" +
                SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date()) +
                ".json"
            exportLauncher.launch(fileName)
        }

        btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain"))
        }
    }

    private fun exportSettings(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                val jsonString = prefs.exportJsonString()
                os.write(jsonString.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val name = uri.lastPathSegment ?: "settings.json"
            Toast.makeText(this, getString(R.string.toast_export_success, name), Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Settings exported successfully to $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export settings", e)
            Toast.makeText(this, getString(R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importSettings(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() } ?: ""

            val success = prefs.importJsonString(json)
            if (success) {
                // 导入完成后，通知 IME 即时刷新（包含高度与按钮交换等）
                try {
                    sendBroadcast(android.content.Intent(com.brycewg.asrkb.ime.AsrKeyboardService.ACTION_REFRESH_IME_UI))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send refresh broadcast", e)
                }
                Toast.makeText(this, getString(R.string.toast_import_success), Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Settings imported successfully from $uri")
            } else {
                Toast.makeText(this, getString(R.string.toast_import_failed), Toast.LENGTH_SHORT).show()
                Log.w(TAG, "Failed to import settings (invalid JSON or parsing error)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings", e)
            Toast.makeText(this, getString(R.string.toast_import_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ================= WebDAV 同步 =================
    private fun setupWebdavSection() {
        val etUrl = findViewById<TextInputEditText>(R.id.etWebdavUrl)
        val etUser = findViewById<TextInputEditText>(R.id.etWebdavUsername)
        val etPass = findViewById<TextInputEditText>(R.id.etWebdavPassword)
        val btnUpload = findViewById<MaterialButton>(R.id.btnWebdavUpload)
        val btnDownload = findViewById<MaterialButton>(R.id.btnWebdavDownload)

        etUrl.setText(prefs.webdavUrl)
        etUser.setText(prefs.webdavUsername)
        etPass.setText(prefs.webdavPassword)

        etUrl.addTextChangedListener(SimpleTextWatcher { prefs.webdavUrl = it })
        etUser.addTextChangedListener(SimpleTextWatcher { prefs.webdavUsername = it })
        etPass.addTextChangedListener(SimpleTextWatcher { prefs.webdavPassword = it })

        btnUpload.setOnClickListener { uploadToWebdav() }
        btnDownload.setOnClickListener { downloadFromWebdav() }
    }

    /**
     * 规范化baseUrl，确保没有末尾斜杠
     */
    private fun normalizeBaseUrl(url: String): String {
        return url.trim().trimEnd('/')
    }

    /**
     * 构建目录URL：{baseUrl}/LexiSharp
     */
    private fun buildDirectoryUrl(baseUrl: String): String {
        return "$baseUrl/$WEBDAV_DIRECTORY"
    }

    /**
     * 构建文件URL：{baseUrl}/LexiSharp/asr_keyboard_settings.json
     */
    private fun buildFileUrl(baseUrl: String): String {
        return "$baseUrl/$WEBDAV_DIRECTORY/$WEBDAV_FILENAME"
    }

    /**
     * 确保LexiSharp目录存在，如果不存在则创建
     * @throws Exception 检查或创建失败时抛出异常
     */
    private suspend fun ensureLexiSharpDirectory(baseUrl: String) {
        val dirUrl = buildDirectoryUrl(baseUrl)

        // 使用PROPFIND检查目录是否存在
        val checkReqBuilder = Request.Builder()
            .url(dirUrl)
            .method("PROPFIND", ByteArray(0).toRequestBody(null))
            .addHeader("Depth", "0")
        addBasicAuthIfNeeded(checkReqBuilder)

        try {
            http.newCall(checkReqBuilder.build()).execute().use { resp ->
                when (resp.code) {
                    207 -> {
                        // 目录存在
                        Log.d(TAG, "WebDAV directory $WEBDAV_DIRECTORY already exists")
                        return
                    }
                    404 -> {
                        // 目录不存在，需要创建
                        Log.d(TAG, "WebDAV directory $WEBDAV_DIRECTORY not found, creating...")
                    }
                    else -> {
                        val errorMsg = "HTTP ${resp.code}"
                        Log.e(TAG, "PROPFIND failed: $errorMsg, body=${resp.body?.string()}")
                        throw Exception(errorMsg)
                    }
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("HTTP") == true) {
                throw e
            }
            Log.e(TAG, "Failed to check directory existence", e)
            throw Exception(e.localizedMessage ?: "Network error")
        }

        // 使用MKCOL创建目录
        val mkdirReqBuilder = Request.Builder()
            .url(dirUrl)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
        addBasicAuthIfNeeded(mkdirReqBuilder)

        try {
            http.newCall(mkdirReqBuilder.build()).execute().use { resp ->
                when (resp.code) {
                    201 -> {
                        Log.d(TAG, "WebDAV directory $WEBDAV_DIRECTORY created successfully")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@BackupSettingsActivity,
                                getString(R.string.toast_webdav_directory_created),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    405 -> {
                        // 405 Method Not Allowed 通常表示目录已存在
                        Log.d(TAG, "Directory already exists (405)")
                    }
                    else -> {
                        val errorMsg = "HTTP ${resp.code}"
                        Log.e(TAG, "MKCOL failed: $errorMsg, body=${resp.body?.string()}")
                        throw Exception(errorMsg)
                    }
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("HTTP") == true) {
                throw e
            }
            Log.e(TAG, "Failed to create directory", e)
            throw Exception(e.localizedMessage ?: "Network error")
        }
    }

    private fun uploadToWebdav() {
        val rawUrl = prefs.webdavUrl.trim()
        if (rawUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_webdav_url_required), Toast.LENGTH_SHORT).show()
            return
        }

        val baseUrl = normalizeBaseUrl(rawUrl)
        val body: RequestBody = prefs.exportJsonString().toRequestBody(JSON_MEDIA)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 确保目录存在
                ensureLexiSharpDirectory(baseUrl)

                // 上传文件到 {baseUrl}/LexiSharp/asr_keyboard_settings.json
                val fileUrl = buildFileUrl(baseUrl)
                val reqBuilder = Request.Builder().url(fileUrl).put(body)
                addBasicAuthIfNeeded(reqBuilder)

                http.newCall(reqBuilder.build()).execute().use { resp ->
                    val ok = resp.isSuccessful
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            Toast.makeText(
                                this@BackupSettingsActivity,
                                getString(R.string.toast_webdav_upload_success),
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.d(TAG, "WebDAV upload successful to $fileUrl")
                        } else {
                            val msg = "HTTP ${resp.code}"
                            Toast.makeText(
                                this@BackupSettingsActivity,
                                getString(R.string.toast_webdav_upload_failed, msg),
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.e(TAG, "WebDAV upload failed: code=${resp.code}, body=${resp.body?.string()}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebDAV upload error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@BackupSettingsActivity,
                        getString(R.string.toast_webdav_upload_failed, e.localizedMessage ?: "error"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun downloadFromWebdav() {
        val rawUrl = prefs.webdavUrl.trim()
        if (rawUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_webdav_url_required), Toast.LENGTH_SHORT).show()
            return
        }

        val baseUrl = normalizeBaseUrl(rawUrl)
        val fileUrl = buildFileUrl(baseUrl)

        lifecycleScope.launch(Dispatchers.IO) {
            val reqBuilder = Request.Builder().url(fileUrl).get()
            addBasicAuthIfNeeded(reqBuilder)
            try {
                http.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val msg = "HTTP ${resp.code}"
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@BackupSettingsActivity,
                                getString(R.string.toast_webdav_download_failed, msg),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        Log.e(TAG, "WebDAV download failed: code=${resp.code}, body=${resp.body?.string()}")
                        return@use
                    }
                    val text = resp.body?.string() ?: ""
                    val ok = prefs.importJsonString(text)
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            try {
                                sendBroadcast(android.content.Intent(com.brycewg.asrkb.ime.AsrKeyboardService.ACTION_REFRESH_IME_UI))
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send refresh broadcast", e)
                            }
                            Toast.makeText(
                                this@BackupSettingsActivity,
                                getString(R.string.toast_webdav_download_success),
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.d(TAG, "WebDAV download and import successful from $fileUrl")
                        } else {
                            Toast.makeText(
                                this@BackupSettingsActivity,
                                getString(R.string.toast_webdav_download_failed, getString(R.string.toast_import_failed)),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebDAV download error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@BackupSettingsActivity,
                        getString(R.string.toast_webdav_download_failed, e.localizedMessage ?: "error"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun addBasicAuthIfNeeded(builder: Request.Builder) {
        val user = prefs.webdavUsername.trim()
        val pass = prefs.webdavPassword.trim()
        if (user.isNotEmpty()) {
            try {
                val token = Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                builder.addHeader("Authorization", "Basic $token")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add auth header", e)
            }
        }
    }
}

private class SimpleTextWatcher(private val onChanged: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) {
        onChanged(s?.toString() ?: "")
    }
}
