package com.brycewg.asrkb.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptPreset
import com.google.android.material.appbar.MaterialToolbar

class AiPostSettingsActivity : AppCompatActivity() {
  private var updating = false
  // 兼容旧逻辑保留变量（已不再使用 Spinner 重绑）
  private var suppressRebind = false
  private var suppressPromptRebind = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_ai_post_settings)

    val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
    toolbar.setTitle(R.string.title_ai_settings)
    toolbar.setNavigationOnClickListener { finish() }

    val prefs = Prefs(this)

    val spLlmProfiles = findViewById<Spinner>(R.id.spLlmProfiles)
    val tvLlmProfiles = findViewById<TextView>(R.id.tvLlmProfilesValue)
    val etLlmProfileName = findViewById<EditText>(R.id.etLlmProfileName)
    val etLlmEndpoint = findViewById<EditText>(R.id.etLlmEndpoint)
    val etLlmApiKey = findViewById<EditText>(R.id.etLlmApiKey)
    val etLlmModel = findViewById<EditText>(R.id.etLlmModel)
    val etLlmTemperature = findViewById<EditText>(R.id.etLlmTemperature)

    val spPromptPresets = findViewById<Spinner>(R.id.spPromptPresets)
    val tvPromptPresets = findViewById<TextView>(R.id.tvPromptPresetsValue)
    val etLlmPromptTitle = findViewById<EditText>(R.id.etLlmPromptTitle)
    val etLlmPrompt = findViewById<EditText>(R.id.etLlmPrompt)

    fun refreshLlmProfilesUi() {
      val profiles = prefs.getLlmProviders()
      val idx = profiles.indexOfFirst { it.id == prefs.activeLlmId }.let { if (it < 0) 0 else it }
      val title = profiles.getOrNull(idx)?.name ?: getString(R.string.untitled_profile)
      tvLlmProfiles.text = title
    }

    fun bindLlmEditorsFromActive() {
      val active = prefs.getActiveLlmProvider()
      updating = true
      val vName = active?.name ?: ""
      if (etLlmProfileName.text?.toString() != vName) etLlmProfileName.setText(vName)

      val vEndpoint = active?.endpoint ?: prefs.llmEndpoint
      if (etLlmEndpoint.text?.toString() != vEndpoint) etLlmEndpoint.setText(vEndpoint)

      val vApiKey = active?.apiKey ?: prefs.llmApiKey
      if (etLlmApiKey.text?.toString() != vApiKey) etLlmApiKey.setText(vApiKey)

      val vModel = active?.model ?: prefs.llmModel
      if (etLlmModel.text?.toString() != vModel) etLlmModel.setText(vModel)

      val vTemp = (active?.temperature ?: prefs.llmTemperature).toString()
      if (etLlmTemperature.text?.toString() != vTemp) etLlmTemperature.setText(vTemp)
      updating = false
    }

    refreshLlmProfilesUi()
    bindLlmEditorsFromActive()

    tvLlmProfiles.setOnClickListener {
      val profiles = prefs.getLlmProviders()
      val titles = profiles.map { it.name }
      val idx = profiles.indexOfFirst { it.id == prefs.activeLlmId }.let { if (it < 0) 0 else it }
      com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(R.string.label_llm_choose_profile)
        .setSingleChoiceItems(titles.toTypedArray(), idx) { dlg, which ->
          val p = profiles.getOrNull(which)
          if (p != null) {
            prefs.activeLlmId = p.id
            tvLlmProfiles.text = p.name
            bindLlmEditorsFromActive()
          }
          dlg.dismiss()
        }
        .setNegativeButton(R.string.btn_cancel, null)
        .show()
    }

    fun EditText.bind(onChange: (String) -> Unit) {
      addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
          if (updating) return
          onChange(s?.toString() ?: "")
        }
      })
    }

    fun updateActiveProfile(mutator: (Prefs.LlmProvider) -> Prefs.LlmProvider, refreshSpinner: Boolean = false) {
      val list = prefs.getLlmProviders().toMutableList()
      val idx = list.indexOfFirst { it.id == prefs.activeLlmId }
      if (idx >= 0) {
        list[idx] = mutator(list[idx])
      } else if (list.isNotEmpty()) {
        list[0] = mutator(list[0])
        prefs.activeLlmId = list[0].id
      } else {
        val created = Prefs.LlmProvider(
          id = java.util.UUID.randomUUID().toString(),
          name = etLlmProfileName.text?.toString()?.ifBlank { getString(R.string.untitled_profile) } ?: getString(R.string.untitled_profile),
          endpoint = etLlmEndpoint.text?.toString() ?: Prefs.DEFAULT_LLM_ENDPOINT,
          apiKey = etLlmApiKey.text?.toString() ?: "",
          model = etLlmModel.text?.toString() ?: Prefs.DEFAULT_LLM_MODEL,
          temperature = etLlmTemperature.text?.toString()?.toFloatOrNull() ?: Prefs.DEFAULT_LLM_TEMPERATURE
        )
        list.add(created)
        prefs.activeLlmId = created.id
      }
      prefs.setLlmProviders(list)
      if (refreshSpinner) refreshLlmProfilesUi()
    }

    etLlmProfileName.bind { v -> updateActiveProfile({ it.copy(name = v.ifBlank { getString(R.string.untitled_profile) }) }, refreshSpinner = true) }
    etLlmEndpoint.bind { v -> updateActiveProfile({ it.copy(endpoint = v.ifBlank { Prefs.DEFAULT_LLM_ENDPOINT }) }, refreshSpinner = false) }
    etLlmApiKey.bind { v -> updateActiveProfile({ it.copy(apiKey = v) }, refreshSpinner = false) }
    etLlmModel.bind { v -> updateActiveProfile({ it.copy(model = v.ifBlank { Prefs.DEFAULT_LLM_MODEL }) }, refreshSpinner = false) }
    etLlmTemperature.bind { v -> updateActiveProfile({ it.copy(temperature = (v.toFloatOrNull() ?: Prefs.DEFAULT_LLM_TEMPERATURE).coerceIn(0f, 2f)) }, refreshSpinner = false) }

    // Prompt presets
    fun refreshPromptPresetsUi() {
      val presets = prefs.getPromptPresets()
      val idx = presets.indexOfFirst { it.id == prefs.activePromptId }.let { if (it < 0) 0 else it }
      val cur = presets.getOrNull(idx)
      tvPromptPresets.text = cur?.title ?: getString(R.string.untitled_preset)
      updating = true
      val t = cur?.title ?: ""
      if (etLlmPromptTitle.text?.toString() != t) etLlmPromptTitle.setText(t)
      val c = cur?.content ?: Prefs.DEFAULT_LLM_PROMPT
      if (etLlmPrompt.text?.toString() != c) etLlmPrompt.setText(c)
      updating = false
    }

    tvPromptPresets.setOnClickListener {
      val presets = prefs.getPromptPresets()
      val titles = presets.map { it.title }
      val idx = presets.indexOfFirst { it.id == prefs.activePromptId }.let { if (it < 0) 0 else it }
      com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(R.string.label_llm_prompt_presets)
        .setSingleChoiceItems(titles.toTypedArray(), idx) { dlg, which ->
          val p = presets.getOrNull(which)
          if (p != null) {
            prefs.activePromptId = p.id
            tvPromptPresets.text = p.title
            updating = true
            if (etLlmPromptTitle.text?.toString() != p.title) etLlmPromptTitle.setText(p.title)
            if (etLlmPrompt.text?.toString() != p.content) etLlmPrompt.setText(p.content)
            updating = false
          }
          dlg.dismiss()
        }
        .setNegativeButton(R.string.btn_cancel, null)
        .show()
    }

    fun updateActivePrompt(mutator: (PromptPreset) -> PromptPreset, refreshSpinner: Boolean = false) {
      val list = prefs.getPromptPresets().toMutableList()
      val idx = list.indexOfFirst { it.id == prefs.activePromptId }
      if (idx >= 0) list[idx] = mutator(list[idx])
      prefs.setPromptPresets(list)
      if (refreshSpinner) refreshPromptPresetsUi()
    }

    etLlmPromptTitle.bind { v ->
      if (updating) return@bind
      updateActivePrompt({ it.copy(title = v.ifBlank { getString(R.string.untitled_preset) }) }, refreshSpinner = true)
    }
    etLlmPrompt.bind { v ->
      if (updating) return@bind
      updateActivePrompt({ it.copy(content = v) }, refreshSpinner = false)
    }

    findViewById<Button>(R.id.btnLlmAddProfile).setOnClickListener {
      val id = java.util.UUID.randomUUID().toString()
      val cur = prefs.getLlmProviders().toMutableList()
      cur.add(
        Prefs.LlmProvider(
          id,
          name = getString(R.string.untitled_profile),
          endpoint = Prefs.DEFAULT_LLM_ENDPOINT,
          apiKey = "",
          model = Prefs.DEFAULT_LLM_MODEL,
          temperature = Prefs.DEFAULT_LLM_TEMPERATURE
        )
      )
      prefs.setLlmProviders(cur)
      prefs.activeLlmId = id
      refreshLlmProfilesUi()
      bindLlmEditorsFromActive()
      Toast.makeText(this, getString(R.string.toast_llm_profile_added), Toast.LENGTH_SHORT).show()
    }

    findViewById<Button>(R.id.btnLlmDeleteProfile).setOnClickListener {
      val cur = prefs.getLlmProviders().toMutableList()
      if (cur.isEmpty()) return@setOnClickListener
      val idx = cur.indexOfFirst { it.id == prefs.activeLlmId }
      if (idx >= 0) {
        cur.removeAt(idx)
        prefs.setLlmProviders(cur)
        prefs.activeLlmId = cur.firstOrNull()?.id ?: ""
      refreshLlmProfilesUi()
        bindLlmEditorsFromActive()
        Toast.makeText(this, getString(R.string.toast_llm_profile_deleted), Toast.LENGTH_SHORT).show()
      }
    }

    findViewById<Button>(R.id.btnAddPromptPreset).setOnClickListener {
      val created = PromptPreset(java.util.UUID.randomUUID().toString(), getString(R.string.untitled_preset), Prefs.DEFAULT_LLM_PROMPT)
      val list = prefs.getPromptPresets().toMutableList().apply { add(created) }
      prefs.setPromptPresets(list)
      prefs.activePromptId = created.id
      refreshPromptPresetsUi()
      Toast.makeText(this, getString(R.string.toast_preset_added), Toast.LENGTH_SHORT).show()
    }

    findViewById<Button>(R.id.btnDeletePromptPreset).setOnClickListener {
      val list = prefs.getPromptPresets().toMutableList()
      if (list.isEmpty()) return@setOnClickListener
      val idx = list.indexOfFirst { it.id == prefs.activePromptId }
      if (idx >= 0) {
        list.removeAt(idx)
        prefs.setPromptPresets(list)
        prefs.activePromptId = list.firstOrNull()?.id ?: ""
        refreshPromptPresetsUi()
        Toast.makeText(this, getString(R.string.toast_preset_deleted), Toast.LENGTH_SHORT).show()
      }
    }

    // 初始载入
    refreshPromptPresetsUi()
  }
}
