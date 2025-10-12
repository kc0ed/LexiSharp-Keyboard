package com.brycewg.asrkb.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.appbar.MaterialToolbar

class PunctSettingsActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_punct_settings)

    val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
    toolbar.setTitle(R.string.title_punct_settings)
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
  }
}

