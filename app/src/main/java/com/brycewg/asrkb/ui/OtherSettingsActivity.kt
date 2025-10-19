package com.brycewg.asrkb.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.SpeechPreset
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OtherSettingsActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_other_settings)

    val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
    toolbar.setTitle(R.string.title_other_settings)
    toolbar.setNavigationOnClickListener { finish() }

    val prefs = Prefs(this)

    val etP1 = findViewById<EditText>(R.id.etPunct1)
    val etP2 = findViewById<EditText>(R.id.etPunct2)
    val etP3 = findViewById<EditText>(R.id.etPunct3)
    val etP4 = findViewById<EditText>(R.id.etPunct4)

    etP1.setText(prefs.punct1)
    etP2.setText(prefs.punct2)
    etP3.setText(prefs.punct3)
    etP4.setText(prefs.punct4)

    fun EditText.bind(onChange: (String) -> Unit) {
      addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
      })
    }

    etP1.bind { prefs.punct1 = it }
    etP2.bind { prefs.punct2 = it }
    etP3.bind { prefs.punct3 = it }
    etP4.bind { prefs.punct4 = it }

    // ---- 语音预置信息（来自 PR 功能，迁移到“其他设置”） ----
    val spSpeechPresets = findViewById<android.widget.Spinner>(R.id.spSpeechPresets)
    val tvSpeechPresets = findViewById<TextView>(R.id.tvSpeechPresetsValue)
    val tilSpeechPresetName = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilSpeechPresetName)
    val tilSpeechPresetContent = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilSpeechPresetContent)
    val etSpeechPresetName = findViewById<TextInputEditText>(R.id.etSpeechPresetName)
    val etSpeechPresetContent = findViewById<TextInputEditText>(R.id.etSpeechPresetContent)
    val btnSpeechPresetAdd = findViewById<MaterialButton>(R.id.btnSpeechPresetAdd)
    val btnSpeechPresetDelete = findViewById<MaterialButton>(R.id.btnSpeechPresetDelete)

    var suppressSpeechPresetSpinner = false
    var updatingSpeechPresetFields = false

    fun refreshSpeechPresetSection() {
      val presets = prefs.getSpeechPresets()
      val hasAny = presets.isNotEmpty()
      val displayNames = if (hasAny) {
        presets.map { preset ->
          val name = preset.name.trim()
            name.ifEmpty { getString(R.string.speech_preset_untitled) }
        }
      } else {
        listOf(getString(R.string.speech_preset_empty_placeholder))
      }
      // 更新展示文本
      if (hasAny) {
        val activeId = prefs.activeSpeechPresetId
        val idx = presets.indexOfFirst { it.id == activeId }.let { if (it < 0) 0 else it }
        tvSpeechPresets.text = displayNames.getOrNull(idx) ?: getString(R.string.speech_preset_untitled)
      } else {
        tvSpeechPresets.text = getString(R.string.speech_preset_empty_placeholder)
      }

      val current = if (hasAny) {
        val activeId = prefs.activeSpeechPresetId
        presets.firstOrNull { it.id == activeId } ?: presets.firstOrNull()
      } else {
        null
      }
      if (current != null && prefs.activeSpeechPresetId != current.id) {
        prefs.activeSpeechPresetId = current.id
      }
      updatingSpeechPresetFields = true
      val currentName = current?.name ?: ""
      if (etSpeechPresetName.text?.toString() != currentName) {
        etSpeechPresetName.setText(currentName)
      }
      val currentContent = current?.content ?: ""
      if (etSpeechPresetContent.text?.toString() != currentContent) {
        etSpeechPresetContent.setText(currentContent)
      }
      updatingSpeechPresetFields = false
      tilSpeechPresetName.error = null
      val enable = hasAny
      spSpeechPresets.isEnabled = enable
      tilSpeechPresetName.isEnabled = enable
      tilSpeechPresetContent.isEnabled = enable
      etSpeechPresetName.isEnabled = enable
      etSpeechPresetContent.isEnabled = enable
      btnSpeechPresetDelete.isEnabled = enable
    }

    fun mutateActiveSpeechPreset(refreshSpinner: Boolean = false, block: (SpeechPreset) -> SpeechPreset?) {
      val list = prefs.getSpeechPresets().toMutableList()
      val idx = list.indexOfFirst { it.id == prefs.activeSpeechPresetId }
      if (idx < 0) return
      val current = list[idx]
      val mutated = block(current) ?: run {
        tilSpeechPresetName.error = null
        return
      }
      if (mutated == current) {
        tilSpeechPresetName.error = null
        return
      }
      list[idx] = mutated
      prefs.setSpeechPresets(list)
      if (refreshSpinner) {
        refreshSpeechPresetSection()
      }
    }

    fun TextInputEditText.bindSpeechPreset(onChange: (String) -> Unit) {
      addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
          if (updatingSpeechPresetFields) return
          onChange(s?.toString() ?: "")
        }
      })
    }

    tvSpeechPresets.setOnClickListener {
      val presets = prefs.getSpeechPresets()
      val displayNames = if (presets.isNotEmpty()) presets.map { it.name.ifBlank { getString(R.string.speech_preset_untitled) } } else listOf(getString(R.string.speech_preset_empty_placeholder))
      val idx = if (presets.isNotEmpty()) presets.indexOfFirst { it.id == prefs.activeSpeechPresetId }.let { if (it < 0) 0 else it } else 0
      com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(R.string.label_speech_preset_section)
        .setSingleChoiceItems(displayNames.toTypedArray(), idx) { dlg, which ->
          val preset = presets.getOrNull(which)
          if (preset != null) {
            prefs.activeSpeechPresetId = preset.id
            tvSpeechPresets.text = displayNames[which]
            updatingSpeechPresetFields = true
            if (etSpeechPresetName.text?.toString() != preset.name) {
              etSpeechPresetName.setText(preset.name)
            }
            if (etSpeechPresetContent.text?.toString() != preset.content) {
              etSpeechPresetContent.setText(preset.content)
            }
            updatingSpeechPresetFields = false
            tilSpeechPresetName.error = null
          }
          dlg.dismiss()
        }
        .setNegativeButton(R.string.btn_cancel, null)
        .show()
    }

    etSpeechPresetName?.bindSpeechPreset { value ->
      if (prefs.getSpeechPresets().isEmpty()) return@bindSpeechPreset
      val trimmed = value.trim()
      if (trimmed.isEmpty()) {
        tilSpeechPresetName.error = getString(R.string.error_speech_preset_name_required)
        return@bindSpeechPreset
      }
      tilSpeechPresetName.error = null
      mutateActiveSpeechPreset(refreshSpinner = true) { preset ->
        if (preset.name == trimmed) preset else preset.copy(name = trimmed)
      }
    }

    etSpeechPresetContent?.bindSpeechPreset { value ->
      if (prefs.getSpeechPresets().isEmpty()) return@bindSpeechPreset
      mutateActiveSpeechPreset(refreshSpinner = false) { preset ->
        if (preset.content == value) preset else preset.copy(content = value)
      }
    }

    btnSpeechPresetAdd?.setOnClickListener {
      val list = prefs.getSpeechPresets().toMutableList()
      val newId = java.util.UUID.randomUUID().toString()
      val defaultName = getString(R.string.speech_preset_default_name, list.size + 1)
      list.add(SpeechPreset(newId, defaultName, ""))
      prefs.setSpeechPresets(list)
      prefs.activeSpeechPresetId = newId
      refreshSpeechPresetSection()
      tilSpeechPresetName.error = null
      etSpeechPresetName.post {
        etSpeechPresetName.requestFocus()
        etSpeechPresetName.setSelection(etSpeechPresetName.text?.length ?: 0)
      }
      Toast.makeText(this, getString(R.string.toast_speech_preset_added), Toast.LENGTH_SHORT).show()
    }

    btnSpeechPresetDelete?.setOnClickListener {
      val presets = prefs.getSpeechPresets()
      if (presets.isEmpty()) return@setOnClickListener
      val activeId = prefs.activeSpeechPresetId
      val current = presets.firstOrNull { it.id == activeId } ?: presets.first()
      com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_speech_preset_delete_title)
        .setMessage(getString(R.string.dialog_speech_preset_delete_message, current.name.ifBlank { getString(R.string.speech_preset_untitled) }))
        .setPositiveButton(R.string.btn_speech_preset_delete) { _, _ ->
          val list = prefs.getSpeechPresets().toMutableList()
          val idx = list.indexOfFirst { it.id == current.id }
          if (idx >= 0) {
            list.removeAt(idx)
            prefs.setSpeechPresets(list)
            if (list.isNotEmpty()) {
              val nextIdx = idx.coerceAtMost(list.lastIndex)
              prefs.activeSpeechPresetId = list[nextIdx].id
            } else {
              prefs.activeSpeechPresetId = ""
            }
            refreshSpeechPresetSection()
            Toast.makeText(this, getString(R.string.toast_speech_preset_deleted), Toast.LENGTH_SHORT).show()
          }
        }
        .setNegativeButton(R.string.btn_cancel, null)
        .show()
    }

    // 初始化一次预置区块
    refreshSpeechPresetSection()

    // ---- SyncClipboard 设置 ----
    val switchSync = findViewById<MaterialSwitch>(R.id.switchSyncClipboard)
    val layoutSync = findViewById<View>(R.id.layoutSyncClipboard)
    val etServer = findViewById<TextInputEditText>(R.id.etScServerBase)
    val etUser = findViewById<TextInputEditText>(R.id.etScUsername)
    val etPass = findViewById<TextInputEditText>(R.id.etScPassword)
    val switchAutoPull = findViewById<MaterialSwitch>(R.id.switchScAutoPull)
    val etInterval = findViewById<TextInputEditText>(R.id.etScPullInterval)
    val btnTestPull = findViewById<MaterialButton>(R.id.btnScTestPull)
    val btnProjectHome = findViewById<MaterialButton>(R.id.btnScProjectHome)

    fun refreshSyncVisibility() {
      layoutSync.visibility = if (switchSync.isChecked) View.VISIBLE else View.GONE
    }

    // 初始化 UI 值
    switchSync.isChecked = prefs.syncClipboardEnabled
    etServer.setText(prefs.syncClipboardServerBase)
    etUser.setText(prefs.syncClipboardUsername)
    etPass.setText(prefs.syncClipboardPassword)
    switchAutoPull.isChecked = prefs.syncClipboardAutoPullEnabled
    etInterval.setText(prefs.syncClipboardPullIntervalSec.toString())
    refreshSyncVisibility()

    switchSync.setOnCheckedChangeListener { _, checked ->
      prefs.syncClipboardEnabled = checked
      refreshSyncVisibility()
    }
    switchAutoPull.setOnCheckedChangeListener { _, checked ->
      prefs.syncClipboardAutoPullEnabled = checked
    }

    etServer.addTextChangedListener(object: TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) { prefs.syncClipboardServerBase = s?.toString() ?: "" }
    })
    etUser.addTextChangedListener(object: TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) { prefs.syncClipboardUsername = s?.toString() ?: "" }
    })
    etPass.addTextChangedListener(object: TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) { prefs.syncClipboardPassword = s?.toString() ?: "" }
    })

    etInterval.addTextChangedListener(object: TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
      override fun afterTextChanged(s: Editable?) {
        val v = (s?.toString() ?: "").trim()
        val sec = v.toIntOrNull()?.coerceIn(1, 600)
        if (sec != null) prefs.syncClipboardPullIntervalSec = sec
      }
    })

    btnTestPull.setOnClickListener {
      // 仅用于验证配置：执行一次 GET，并不写入系统剪贴板
      val mgr = com.brycewg.asrkb.clipboard.SyncClipboardManager(this, prefs, lifecycleScope)
      lifecycleScope.launch(Dispatchers.IO) {
        val (ok, _) = try { mgr.pullNow(updateClipboard = false) } catch (_: Throwable) { false to null }
        withContext(Dispatchers.Main) {
          if (ok) {
            Toast.makeText(this@OtherSettingsActivity, getString(R.string.sc_test_success), Toast.LENGTH_SHORT).show()
          } else {
            Toast.makeText(this@OtherSettingsActivity, getString(R.string.sc_test_failed), Toast.LENGTH_SHORT).show()
          }
        }
      }
    }

    btnProjectHome.setOnClickListener {
      try {
        val uri = android.net.Uri.parse("https://github.com/Jeric-X/SyncClipboard")
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
        startActivity(intent)
      } catch (_: Throwable) {
        Toast.makeText(this@OtherSettingsActivity, getString(R.string.sc_open_browser_failed), Toast.LENGTH_SHORT).show()
      }
    }
  }
}
