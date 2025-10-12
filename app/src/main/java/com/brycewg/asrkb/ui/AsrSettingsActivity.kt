package com.brycewg.asrkb.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class AsrSettingsActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_asr_settings)

    val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
    toolbar.setTitle(R.string.title_asr_settings)
    toolbar.setNavigationOnClickListener { finish() }

    val prefs = Prefs(this)

    val spAsrVendor = findViewById<Spinner>(R.id.spAsrVendor)
    val groupVolc = findViewById<View>(R.id.groupVolc)
    val groupSf = findViewById<View>(R.id.groupSf)
    val groupEleven = findViewById<View>(R.id.groupEleven)
    val groupOpenAi = findViewById<View>(R.id.groupOpenAI)
    val groupDash = findViewById<View>(R.id.groupDashScope)
    val groupGemini = findViewById<View>(R.id.groupGemini)

    val titleVolc = findViewById<View>(R.id.titleVolc)
    val titleSf = findViewById<View>(R.id.titleSf)
    val titleEleven = findViewById<View>(R.id.titleEleven)
    val titleOpenAi = findViewById<View>(R.id.titleOpenAI)
    val titleDash = findViewById<View>(R.id.titleDash)
    val titleGemini = findViewById<View>(R.id.titleGemini)

    val etAppKey = findViewById<EditText>(R.id.etAppKey)
    val etAccessKey = findViewById<EditText>(R.id.etAccessKey)
    val etSfApiKey = findViewById<EditText>(R.id.etSfApiKey)
    val etSfModel = findViewById<EditText>(R.id.etSfModel)
    val etElevenApiKey = findViewById<EditText>(R.id.etElevenApiKey)
    val etElevenModel = findViewById<EditText>(R.id.etElevenModel)
    val etDashApiKey = findViewById<EditText>(R.id.etDashApiKey)
    val etDashModel = findViewById<EditText>(R.id.etDashModel)
    val etOpenAiAsrEndpoint = findViewById<EditText>(R.id.etOpenAiAsrEndpoint)
    val etOpenAiApiKey = findViewById<EditText>(R.id.etOpenAiApiKey)
    val etOpenAiModel = findViewById<EditText>(R.id.etOpenAiModel)
    val etGeminiApiKey = findViewById<EditText>(R.id.etGeminiApiKey)
    val etGeminiModel = findViewById<EditText>(R.id.etGeminiModel)
    val switchVolcStreaming = findViewById<MaterialSwitch>(R.id.switchVolcStreaming)

    fun applyVendorVisibility(v: AsrVendor) {
      val visMap = mapOf(
        AsrVendor.Volc to listOf(titleVolc, groupVolc),
        AsrVendor.SiliconFlow to listOf(titleSf, groupSf),
        AsrVendor.ElevenLabs to listOf(titleEleven, groupEleven),
        AsrVendor.OpenAI to listOf(titleOpenAi, groupOpenAi),
        AsrVendor.DashScope to listOf(titleDash, groupDash),
        AsrVendor.Gemini to listOf(titleGemini, groupGemini)
      )
      visMap.forEach { (vendor, views) ->
        val vis = if (vendor == v) View.VISIBLE else View.GONE
        views.forEach { it.visibility = vis }
      }
    }

    val vendorOrder = listOf(
      AsrVendor.Volc,
      AsrVendor.SiliconFlow,
      AsrVendor.ElevenLabs,
      AsrVendor.OpenAI,
      AsrVendor.DashScope,
      AsrVendor.Gemini
    )
    val vendorItems = listOf(
      getString(R.string.vendor_volc),
      getString(R.string.vendor_sf),
      getString(R.string.vendor_eleven),
      getString(R.string.vendor_openai),
      getString(R.string.vendor_dashscope),
      getString(R.string.vendor_gemini)
    )
    spAsrVendor.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vendorItems)
    spAsrVendor.setSelection(vendorOrder.indexOf(prefs.asrVendor).coerceAtLeast(0))
    applyVendorVisibility(prefs.asrVendor)

    spAsrVendor.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
        val vendor = vendorOrder.getOrNull(position) ?: AsrVendor.Volc
        prefs.asrVendor = vendor
        applyVendorVisibility(vendor)
      }
      override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    })

    fun EditText.bindString(onChange: (String) -> Unit) {
      setText(this.text) // keep as is
      addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
      })
    }

    // 初始化 + 绑定自动保存
    etAppKey.setText(prefs.appKey)
    etAccessKey.setText(prefs.accessKey)
    etSfApiKey.setText(prefs.sfApiKey)
    etSfModel.setText(prefs.sfModel)
    etElevenApiKey.setText(prefs.elevenApiKey)
    etElevenModel.setText(prefs.elevenModelId)
    etDashApiKey.setText(prefs.dashApiKey)
    etDashModel.setText(prefs.dashModel)
    etOpenAiAsrEndpoint.setText(prefs.oaAsrEndpoint)
    etOpenAiApiKey.setText(prefs.oaAsrApiKey)
    etOpenAiModel.setText(prefs.oaAsrModel)
    etGeminiApiKey.setText(prefs.gemApiKey)
    etGeminiModel.setText(prefs.gemModel)
    switchVolcStreaming.isChecked = prefs.volcStreamingEnabled

    etAppKey.bindString { prefs.appKey = it }
    etAccessKey.bindString { prefs.accessKey = it }
    etSfApiKey.bindString { prefs.sfApiKey = it }
    etSfModel.bindString { prefs.sfModel = it }
    etElevenApiKey.bindString { prefs.elevenApiKey = it }
    etElevenModel.bindString { prefs.elevenModelId = it }
    etDashApiKey.bindString { prefs.dashApiKey = it }
    etDashModel.bindString { prefs.dashModel = it }
    etOpenAiAsrEndpoint.bindString { prefs.oaAsrEndpoint = it }
    etOpenAiApiKey.bindString { prefs.oaAsrApiKey = it }
    etOpenAiModel.bindString { prefs.oaAsrModel = it }
    etGeminiApiKey.bindString { prefs.gemApiKey = it }
    etGeminiModel.bindString { prefs.gemModel = it }

    switchVolcStreaming.setOnCheckedChangeListener { _, isChecked ->
      prefs.volcStreamingEnabled = isChecked
    }
  }
}

