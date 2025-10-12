package com.brycewg.asrkb.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptPreset
import com.google.android.material.appbar.MaterialToolbar

class AiPostSettingsActivity : AppCompatActivity() {
  private var updating = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_ai_post_settings)

    val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
    toolbar.setTitle(R.string.title_ai_settings)
    toolbar.setNavigationOnClickListener { finish() }

    val prefs = Prefs(this)

    val spLlmProfiles = findViewById<Spinner>(R.id.spLlmProfiles)
    val etLlmProfileName = findViewById<EditText>(R.id.etLlmProfileName)
    val etLlmEndpoint = findViewById<EditText>(R.id.etLlmEndpoint)
    val etLlmApiKey = findViewById<EditText>(R.id.etLlmApiKey)
    val etLlmModel = findViewById<EditText>(R.id.etLlmModel)
    val etLlmTemperature = findViewById<EditText>(R.id.etLlmTemperature)

    val spPromptPresets = findViewById<Spinner>(R.id.spPromptPresets)
    val etLlmPromptTitle = findViewById<EditText>(R.id.etLlmPromptTitle)
    val etLlmPrompt = findViewById<EditText>(R.id.etLlmPrompt)

    fun refreshLlmProfilesSpinner() {
      val profiles = prefs.getLlmProviders()
      val titles = profiles.map { it.name }
      spLlmProfiles.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
      val idx = profiles.indexOfFirst { it.id == prefs.activeLlmId }.let { if (it < 0) 0 else it }
      if (idx in titles.indices) spLlmProfiles.setSelection(idx)
    }

    fun bindLlmEditorsFromActive() {
      val active = prefs.getActiveLlmProvider()
      updating = true
      etLlmProfileName.setText(active?.name ?: "")
      etLlmEndpoint.setText(active?.endpoint ?: prefs.llmEndpoint)
      etLlmApiKey.setText(active?.apiKey ?: prefs.llmApiKey)
      etLlmModel.setText(active?.model ?: prefs.llmModel)
      etLlmTemperature.setText((active?.temperature ?: prefs.llmTemperature).toString())
      updating = false
    }

    refreshLlmProfilesSpinner()
    bindLlmEditorsFromActive()

    spLlmProfiles.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
        val profiles = prefs.getLlmProviders()
        val p = profiles.getOrNull(position)
        if (p != null) {
          prefs.activeLlmId = p.id
          bindLlmEditorsFromActive()
        }
      }
      override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
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

    fun updateActiveProfile(mutator: (Prefs.LlmProvider) -> Prefs.LlmProvider) {
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
      refreshLlmProfilesSpinner()
    }

    etLlmProfileName.bind { v -> updateActiveProfile { it.copy(name = v.ifBlank { getString(R.string.untitled_profile) }) } }
    etLlmEndpoint.bind { v -> updateActiveProfile { it.copy(endpoint = v.ifBlank { Prefs.DEFAULT_LLM_ENDPOINT }) } }
    etLlmApiKey.bind { v -> updateActiveProfile { it.copy(apiKey = v) } }
    etLlmModel.bind { v -> updateActiveProfile { it.copy(model = v.ifBlank { Prefs.DEFAULT_LLM_MODEL }) } }
    etLlmTemperature.bind { v -> updateActiveProfile { it.copy(temperature = (v.toFloatOrNull() ?: Prefs.DEFAULT_LLM_TEMPERATURE).coerceIn(0f, 2f)) } }

    // Prompt presets
    fun refreshPromptPresets() {
      val presets = prefs.getPromptPresets()
      val titles = presets.map { it.title }
      spPromptPresets.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
      val idx = presets.indexOfFirst { it.id == prefs.activePromptId }.let { if (it < 0) 0 else it }
      if (idx in titles.indices) spPromptPresets.setSelection(idx)
      val cur = presets.getOrNull(idx)
      updating = true
      etLlmPromptTitle.setText(cur?.title ?: "")
      etLlmPrompt.setText(cur?.content ?: Prefs.DEFAULT_LLM_PROMPT)
      updating = false
    }

    spPromptPresets.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
        val presets = prefs.getPromptPresets()
        val p = presets.getOrNull(position) ?: return
        prefs.activePromptId = p.id
        updating = true
        etLlmPromptTitle.setText(p.title)
        etLlmPrompt.setText(p.content)
        updating = false
      }
      override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    fun updateActivePrompt(mutator: (PromptPreset) -> PromptPreset) {
      val list = prefs.getPromptPresets().toMutableList()
      val idx = list.indexOfFirst { it.id == prefs.activePromptId }
      if (idx >= 0) list[idx] = mutator(list[idx])
      prefs.setPromptPresets(list)
      refreshPromptPresets()
    }

    etLlmPromptTitle.bind { v ->
      if (updating) return@bind
      updateActivePrompt { it.copy(title = v.ifBlank { getString(R.string.untitled_preset) }) }
    }
    etLlmPrompt.bind { v ->
      if (updating) return@bind
      updateActivePrompt { it.copy(content = v) }
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
      refreshLlmProfilesSpinner()
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
        refreshLlmProfilesSpinner()
        bindLlmEditorsFromActive()
        Toast.makeText(this, getString(R.string.toast_llm_profile_deleted), Toast.LENGTH_SHORT).show()
      }
    }

    findViewById<Button>(R.id.btnAddPromptPreset).setOnClickListener {
      val created = PromptPreset(java.util.UUID.randomUUID().toString(), getString(R.string.untitled_preset), Prefs.DEFAULT_LLM_PROMPT)
      val list = prefs.getPromptPresets().toMutableList().apply { add(created) }
      prefs.setPromptPresets(list)
      prefs.activePromptId = created.id
      refreshPromptPresets()
      Toast.makeText(this, getString(R.string.toast_preset_added), Toast.LENGTH_SHORT).show()
    }

    // 初始载入
    refreshPromptPresets()
  }
}

