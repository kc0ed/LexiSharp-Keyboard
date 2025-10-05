package com.example.asrkeyboard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.Switch
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
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
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

        etAppKey.setText(prefs.appKey)
        etAccessKey.setText(prefs.accessKey)
        etResourceId.setText(prefs.resourceId)
        etEndpoint.setText(prefs.endpoint)

        findViewById<Button>(R.id.btnSaveKeys).setOnClickListener {
            prefs.appKey = etAppKey.text?.toString() ?: ""
            prefs.accessKey = etAccessKey.text?.toString() ?: ""
            prefs.resourceId = etResourceId.text?.toString()?.ifBlank { Prefs.DEFAULT_RESOURCE } ?: Prefs.DEFAULT_RESOURCE
            prefs.endpoint = etEndpoint.text?.toString()?.ifBlank { Prefs.DEFAULT_ENDPOINT } ?: Prefs.DEFAULT_ENDPOINT
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }

        val swContinuous = findViewById<Switch>(R.id.swContinuous)
        swContinuous.isChecked = prefs.continuousMode
        swContinuous.setOnCheckedChangeListener { _, isChecked ->
            prefs.continuousMode = isChecked
        }
    }
}
