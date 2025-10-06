package com.example.asrkeyboard.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.activity.ComponentActivity
import com.example.asrkeyboard.R
import com.example.asrkeyboard.store.Prefs

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

        val prefs = Prefs(this)
        val etAppKey = findViewById<EditText>(R.id.etAppKey)
        val etAccessKey = findViewById<EditText>(R.id.etAccessKey)
        val etResourceId = findViewById<EditText>(R.id.etResourceId)
        val etEndpoint = findViewById<EditText>(R.id.etEndpoint)
        val switchTrimTrailingPunct = findViewById<MaterialSwitch>(R.id.switchTrimTrailingPunct)
        val switchShowImeSwitcher = findViewById<MaterialSwitch>(R.id.switchShowImeSwitcher)

        // LLM fields
        val etLlmEndpoint = findViewById<EditText>(R.id.etLlmEndpoint)
        val etLlmApiKey = findViewById<EditText>(R.id.etLlmApiKey)
        val etLlmModel = findViewById<EditText>(R.id.etLlmModel)
        val etLlmTemperature = findViewById<EditText>(R.id.etLlmTemperature)
        val etLlmPrompt = findViewById<EditText>(R.id.etLlmPrompt)

        etAppKey.setText(prefs.appKey)
        etAccessKey.setText(prefs.accessKey)
        etResourceId.setText(prefs.resourceId)
        etEndpoint.setText(prefs.endpoint)
        switchTrimTrailingPunct.isChecked = prefs.trimFinalTrailingPunct
        switchShowImeSwitcher.isChecked = prefs.showImeSwitcherButton
        etLlmEndpoint.setText(prefs.llmEndpoint)
        etLlmApiKey.setText(prefs.llmApiKey)
        etLlmModel.setText(prefs.llmModel)
        etLlmTemperature.setText(prefs.llmTemperature.toString())
        etLlmPrompt.setText(prefs.llmPrompt)

        findViewById<Button>(R.id.btnSaveKeys).setOnClickListener {
            prefs.appKey = etAppKey.text?.toString() ?: ""
            prefs.accessKey = etAccessKey.text?.toString() ?: ""
            prefs.resourceId = etResourceId.text?.toString()?.ifBlank { Prefs.DEFAULT_RESOURCE } ?: Prefs.DEFAULT_RESOURCE
            prefs.endpoint = etEndpoint.text?.toString()?.ifBlank { Prefs.DEFAULT_ENDPOINT } ?: Prefs.DEFAULT_ENDPOINT
            // LLM
            prefs.llmEndpoint = etLlmEndpoint.text?.toString()?.ifBlank { Prefs.DEFAULT_LLM_ENDPOINT } ?: Prefs.DEFAULT_LLM_ENDPOINT
            prefs.llmApiKey = etLlmApiKey.text?.toString() ?: ""
            prefs.llmModel = etLlmModel.text?.toString()?.ifBlank { Prefs.DEFAULT_LLM_MODEL } ?: Prefs.DEFAULT_LLM_MODEL
            val tempVal = etLlmTemperature.text?.toString()?.toFloatOrNull()
            prefs.llmTemperature = (tempVal ?: Prefs.DEFAULT_LLM_TEMPERATURE).coerceIn(0f, 2f)
            prefs.llmPrompt = etLlmPrompt.text?.toString() ?: Prefs.DEFAULT_LLM_PROMPT
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }

        switchTrimTrailingPunct.setOnCheckedChangeListener { _, isChecked ->
            prefs.trimFinalTrailingPunct = isChecked
        }

        switchShowImeSwitcher.setOnCheckedChangeListener { _, isChecked ->
            prefs.showImeSwitcherButton = isChecked
        }

        // Continuous mode has been removed; no toggle needed.
    }
}
