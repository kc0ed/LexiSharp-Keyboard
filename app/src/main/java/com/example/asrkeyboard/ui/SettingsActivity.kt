package com.example.asrkeyboard.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.activity.ComponentActivity
import com.example.asrkeyboard.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import com.example.asrkeyboard.store.Prefs
import com.example.asrkeyboard.store.PromptPreset
import com.example.asrkeyboard.asr.AsrVendor
import android.view.View

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.btnEnable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btnChoose).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<Button>(R.id.btnMicPermission).setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }

        findViewById<Button>(R.id.btnShowGuide).setOnClickListener {
            val view = LayoutInflater.from(this).inflate(R.layout.dialog_guide, null, false)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_quick_guide)
                .setView(view)
                .setPositiveButton(R.string.btn_close, null)
                .show()
        }

        val prefs = Prefs(this)
        val etAppKey = findViewById<EditText>(R.id.etAppKey)
        val etAccessKey = findViewById<EditText>(R.id.etAccessKey)
        val etSfApiKey = findViewById<EditText>(R.id.etSfApiKey)
        val etSfModel = findViewById<EditText>(R.id.etSfModel)
        val etElevenApiKey = findViewById<EditText>(R.id.etElevenApiKey)
        val etElevenModel = findViewById<EditText>(R.id.etElevenModel)
        val groupVolc = findViewById<View>(R.id.groupVolc)
        val groupSf = findViewById<View>(R.id.groupSf)
        val groupEleven = findViewById<View>(R.id.groupEleven)
        val spAsrVendor = findViewById<Spinner>(R.id.spAsrVendor)
        val switchTrimTrailingPunct = findViewById<MaterialSwitch>(R.id.switchTrimTrailingPunct)
        val switchShowImeSwitcher = findViewById<MaterialSwitch>(R.id.switchShowImeSwitcher)
        val switchAutoSwitchPassword = findViewById<MaterialSwitch>(R.id.switchAutoSwitchPassword)

        // LLM fields
        val etLlmEndpoint = findViewById<EditText>(R.id.etLlmEndpoint)
        val etLlmApiKey = findViewById<EditText>(R.id.etLlmApiKey)
        val etLlmModel = findViewById<EditText>(R.id.etLlmModel)
        val etLlmTemperature = findViewById<EditText>(R.id.etLlmTemperature)
        val etLlmPrompt = findViewById<EditText>(R.id.etLlmPrompt)
        val etLlmPromptTitle = findViewById<EditText>(R.id.etLlmPromptTitle)
        val spPromptPresets = findViewById<Spinner>(R.id.spPromptPresets)
        // Custom punctuation inputs
        val etPunct1 = findViewById<EditText>(R.id.etPunct1)
        val etPunct2 = findViewById<EditText>(R.id.etPunct2)
        val etPunct3 = findViewById<EditText>(R.id.etPunct3)
        val etPunct4 = findViewById<EditText>(R.id.etPunct4)

        etAppKey.setText(prefs.appKey)
        etAccessKey.setText(prefs.accessKey)
        etSfApiKey.setText(prefs.sfApiKey)
        etSfModel.setText(prefs.sfModel)
        etElevenApiKey.setText(prefs.elevenApiKey)
        etElevenModel.setText(prefs.elevenModelId)
        switchTrimTrailingPunct.isChecked = prefs.trimFinalTrailingPunct
        switchShowImeSwitcher.isChecked = prefs.showImeSwitcherButton
        switchAutoSwitchPassword.isChecked = prefs.autoSwitchOnPassword
        etLlmEndpoint.setText(prefs.llmEndpoint)
        etLlmApiKey.setText(prefs.llmApiKey)
        etLlmModel.setText(prefs.llmModel)
        etLlmTemperature.setText(prefs.llmTemperature.toString())
        etPunct1.setText(prefs.punct1)
        etPunct2.setText(prefs.punct2)
        etPunct3.setText(prefs.punct3)
        etPunct4.setText(prefs.punct4)
        // Prompt presets
        var presets = prefs.getPromptPresets().toMutableList()
        var activeId = prefs.activePromptId
        if (activeId.isBlank()) {
            activeId = presets.firstOrNull()?.id ?: ""
            prefs.activePromptId = activeId
        }
        fun refreshSpinnerSelection() {
            presets = prefs.getPromptPresets().toMutableList()
            val titles = presets.map { it.title }
            spPromptPresets.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
            val idx = presets.indexOfFirst { it.id == prefs.activePromptId }.let { if (it < 0) 0 else it }
            if (idx in titles.indices) {
                spPromptPresets.setSelection(idx)
                etLlmPromptTitle.setText(presets[idx].title)
                etLlmPrompt.setText(presets[idx].content)
            }
        }
        refreshSpinnerSelection()

        // ASR vendor spinner setup
        val vendorItems = listOf(
            getString(R.string.vendor_volc),
            getString(R.string.vendor_sf),
            getString(R.string.vendor_eleven)
        )
        spAsrVendor.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vendorItems)
        spAsrVendor.setSelection(
            when (prefs.asrVendor) {
                AsrVendor.Volc -> 0
                AsrVendor.SiliconFlow -> 1
                AsrVendor.ElevenLabs -> 2
            }
        )
        fun applyVendorVisibility(v: AsrVendor) {
            groupVolc.visibility = if (v == AsrVendor.Volc) View.VISIBLE else View.GONE
            groupSf.visibility = if (v == AsrVendor.SiliconFlow) View.VISIBLE else View.GONE
            groupEleven.visibility = if (v == AsrVendor.ElevenLabs) View.VISIBLE else View.GONE
        }
        applyVendorVisibility(prefs.asrVendor)

        spAsrVendor.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val vendor = when (position) {
                    1 -> AsrVendor.SiliconFlow
                    2 -> AsrVendor.ElevenLabs
                    else -> AsrVendor.Volc
                }
                prefs.asrVendor = vendor
                applyVendorVisibility(vendor)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        spPromptPresets.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val p = presets.getOrNull(position) ?: return
                prefs.activePromptId = p.id
                etLlmPromptTitle.setText(p.title)
                etLlmPrompt.setText(p.content)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
        })

        findViewById<Button>(R.id.btnSaveKeys).setOnClickListener {
            // Save vendor-specific keys
            prefs.appKey = etAppKey.text?.toString() ?: ""
            prefs.accessKey = etAccessKey.text?.toString() ?: ""
            prefs.sfApiKey = etSfApiKey.text?.toString() ?: ""
            prefs.sfModel = etSfModel.text?.toString()?.ifBlank { Prefs.DEFAULT_SF_MODEL } ?: Prefs.DEFAULT_SF_MODEL
            prefs.elevenApiKey = etElevenApiKey.text?.toString() ?: ""
            prefs.elevenModelId = etElevenModel.text?.toString() ?: ""
            // 开关设置
            prefs.trimFinalTrailingPunct = switchTrimTrailingPunct.isChecked
            prefs.showImeSwitcherButton = switchShowImeSwitcher.isChecked
            prefs.autoSwitchOnPassword = switchAutoSwitchPassword.isChecked
            // LLM
            prefs.llmEndpoint = etLlmEndpoint.text?.toString()?.ifBlank { Prefs.DEFAULT_LLM_ENDPOINT } ?: Prefs.DEFAULT_LLM_ENDPOINT
            prefs.llmApiKey = etLlmApiKey.text?.toString() ?: ""
            prefs.llmModel = etLlmModel.text?.toString()?.ifBlank { Prefs.DEFAULT_LLM_MODEL } ?: Prefs.DEFAULT_LLM_MODEL
            val tempVal = etLlmTemperature.text?.toString()?.toFloatOrNull()
            prefs.llmTemperature = (tempVal ?: Prefs.DEFAULT_LLM_TEMPERATURE).coerceIn(0f, 2f)
            // Custom punctuation buttons
            prefs.punct1 = etPunct1.text?.toString() ?: Prefs.DEFAULT_PUNCT_1
            prefs.punct2 = etPunct2.text?.toString() ?: Prefs.DEFAULT_PUNCT_2
            prefs.punct3 = etPunct3.text?.toString() ?: Prefs.DEFAULT_PUNCT_3
            prefs.punct4 = etPunct4.text?.toString() ?: Prefs.DEFAULT_PUNCT_4
            // Update current preset title/content and set active
            val newTitle = etLlmPromptTitle.text?.toString()?.ifBlank { "未命名预设" } ?: "未命名预设"
            val newContent = etLlmPrompt.text?.toString() ?: Prefs.DEFAULT_LLM_PROMPT
            val currentIdx = presets.indexOfFirst { it.id == prefs.activePromptId }
            val updated = if (currentIdx >= 0) presets.toMutableList() else presets
            if (currentIdx >= 0) {
                updated[currentIdx] = updated[currentIdx].copy(title = newTitle, content = newContent)
                prefs.setPromptPresets(updated)
                prefs.activePromptId = updated[currentIdx].id
            } else {
                // No active preset? create one
                val created = PromptPreset(java.util.UUID.randomUUID().toString(), newTitle, newContent)
                val newList = presets.toMutableList().apply { add(created) }
                prefs.setPromptPresets(newList)
                prefs.activePromptId = created.id
            }
            refreshSpinnerSelection()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnAddPromptPreset).setOnClickListener {
            val title = etLlmPromptTitle.text?.toString()?.ifBlank { "未命名预设" } ?: "未命名预设"
            val content = etLlmPrompt.text?.toString() ?: Prefs.DEFAULT_LLM_PROMPT
            val created = PromptPreset(java.util.UUID.randomUUID().toString(), title, content)
            val newList = prefs.getPromptPresets().toMutableList().apply { add(created) }
            prefs.setPromptPresets(newList)
            prefs.activePromptId = created.id
            refreshSpinnerSelection()
            Toast.makeText(this, "已新增预设", Toast.LENGTH_SHORT).show()
        }


        // Continuous mode has been removed; no toggle needed.
    }
}
