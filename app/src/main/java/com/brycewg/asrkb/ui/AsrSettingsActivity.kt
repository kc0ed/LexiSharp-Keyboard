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
    val groupSoniox = findViewById<View>(R.id.groupSoniox)

    val titleVolc = findViewById<View>(R.id.titleVolc)
    val titleSf = findViewById<View>(R.id.titleSf)
    val titleEleven = findViewById<View>(R.id.titleEleven)
    val titleOpenAi = findViewById<View>(R.id.titleOpenAI)
    val titleDash = findViewById<View>(R.id.titleDash)
    val titleGemini = findViewById<View>(R.id.titleGemini)
    val titleSoniox = findViewById<View>(R.id.titleSoniox)

    val etAppKey = findViewById<EditText>(R.id.etAppKey)
    val etAccessKey = findViewById<EditText>(R.id.etAccessKey)
    val etSfApiKey = findViewById<EditText>(R.id.etSfApiKey)
    val etSfModel = findViewById<EditText>(R.id.etSfModel)
    val etElevenApiKey = findViewById<EditText>(R.id.etElevenApiKey)
    val etElevenModel = findViewById<EditText>(R.id.etElevenModel)
    val etDashApiKey = findViewById<EditText>(R.id.etDashApiKey)
    val etDashModel = findViewById<EditText>(R.id.etDashModel)
    val etDashPrompt = findViewById<EditText>(R.id.etDashPrompt)
    val spDashLanguage = findViewById<Spinner>(R.id.spDashLanguage)
    val tvDashLanguageLabel = findViewById<View>(R.id.tvDashLanguageLabel)
    val etOpenAiAsrEndpoint = findViewById<EditText>(R.id.etOpenAiAsrEndpoint)
    val etOpenAiApiKey = findViewById<EditText>(R.id.etOpenAiApiKey)
    val etOpenAiModel = findViewById<EditText>(R.id.etOpenAiModel)
    val etGeminiApiKey = findViewById<EditText>(R.id.etGeminiApiKey)
    val etGeminiModel = findViewById<EditText>(R.id.etGeminiModel)
    val etGeminiPrompt = findViewById<EditText>(R.id.etGeminiPrompt)
    val switchVolcStreaming = findViewById<MaterialSwitch>(R.id.switchVolcStreaming)
    val switchVolcDdc = findViewById<MaterialSwitch>(R.id.switchVolcDdc)
    val switchVolcVad = findViewById<MaterialSwitch>(R.id.switchVolcVad)
    val switchVolcNonstream = findViewById<MaterialSwitch>(R.id.switchVolcNonstream)
    val switchVolcFirstCharAccel = findViewById<MaterialSwitch>(R.id.switchVolcFirstCharAccel)
    val spVolcLanguage = findViewById<Spinner>(R.id.spVolcLanguage)
    val tvVolcLanguageLabel = findViewById<View>(R.id.tvVolcLanguageLabel)
    val etSonioxApiKey = findViewById<EditText>(R.id.etSonioxApiKey)
    val switchSonioxStreaming = findViewById<MaterialSwitch>(R.id.switchSonioxStreaming)
    val tvSonioxLanguageLabel = findViewById<View>(R.id.tvSonioxLanguageLabel)
    val tvSonioxLanguageValue = findViewById<android.widget.TextView>(R.id.tvSonioxLanguageValue)

    fun applyVendorVisibility(v: AsrVendor) {
      val visMap = mapOf(
        AsrVendor.Volc to listOf(titleVolc, groupVolc),
        AsrVendor.SiliconFlow to listOf(titleSf, groupSf),
        AsrVendor.ElevenLabs to listOf(titleEleven, groupEleven),
        AsrVendor.OpenAI to listOf(titleOpenAi, groupOpenAi),
        AsrVendor.DashScope to listOf(titleDash, groupDash),
        AsrVendor.Gemini to listOf(titleGemini, groupGemini),
        AsrVendor.Soniox to listOf(titleSoniox, groupSoniox)
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
      AsrVendor.Gemini,
      AsrVendor.Soniox
    )
    val vendorItems = listOf(
      getString(R.string.vendor_volc),
      getString(R.string.vendor_sf),
      getString(R.string.vendor_eleven),
      getString(R.string.vendor_openai),
      getString(R.string.vendor_dashscope),
      getString(R.string.vendor_gemini),
      getString(R.string.vendor_soniox)
    )
    spAsrVendor.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vendorItems)
    spAsrVendor.setSelection(vendorOrder.indexOf(prefs.asrVendor).coerceAtLeast(0))
    applyVendorVisibility(prefs.asrVendor)

      spAsrVendor.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
          override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
              val vendor = vendorOrder.getOrNull(position) ?: AsrVendor.Volc
              prefs.asrVendor = vendor
              applyVendorVisibility(vendor)
          }

          override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
      }

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
    etDashPrompt.setText(prefs.dashPrompt)
    etOpenAiAsrEndpoint.setText(prefs.oaAsrEndpoint)
    etOpenAiApiKey.setText(prefs.oaAsrApiKey)
    etOpenAiModel.setText(prefs.oaAsrModel)
    etGeminiApiKey.setText(prefs.gemApiKey)
    etGeminiModel.setText(prefs.gemModel)
    etGeminiPrompt.setText(prefs.gemPrompt)
    switchVolcStreaming.isChecked = prefs.volcStreamingEnabled
    switchVolcDdc.isChecked = prefs.volcDdcEnabled
    switchVolcVad.isChecked = prefs.volcVadEnabled
    switchVolcNonstream.isChecked = prefs.volcNonstreamEnabled
    switchVolcFirstCharAccel.isChecked = prefs.volcFirstCharAccelEnabled
    etSonioxApiKey.setText(prefs.sonioxApiKey)
    switchSonioxStreaming.isChecked = prefs.sonioxStreamingEnabled
    // Soniox 语言：多选（language_hints）
    val sonioxLangLabels = listOf(
      getString(R.string.soniox_lang_auto),
      getString(R.string.soniox_lang_en),
      getString(R.string.soniox_lang_zh),
      getString(R.string.soniox_lang_ja),
      getString(R.string.soniox_lang_ko),
      getString(R.string.soniox_lang_es),
      getString(R.string.soniox_lang_pt),
      getString(R.string.soniox_lang_de),
      getString(R.string.soniox_lang_fr),
      getString(R.string.soniox_lang_id),
      getString(R.string.soniox_lang_ru),
      getString(R.string.soniox_lang_ar),
      getString(R.string.soniox_lang_hi),
      getString(R.string.soniox_lang_vi),
      getString(R.string.soniox_lang_th),
      getString(R.string.soniox_lang_ms),
      getString(R.string.soniox_lang_fil)
    )
    val sonioxLangCodes = listOf(
      "",      // Auto / unset hints
      "en",
      "zh",
      "ja",
      "ko",
      "es",
      "pt",
      "de",
      "fr",
      "id",
      "ru",
      "ar",
      "hi",
      "vi",
      "th",
      "ms",
      "fil"
    )

    fun updateSonioxLangSummary() {
      val selected = prefs.getSonioxLanguages()
      if (selected.isEmpty()) {
        tvSonioxLanguageValue.text = getString(R.string.soniox_lang_auto)
        return
      }
      val names = selected.mapNotNull { code ->
        val idx = sonioxLangCodes.indexOf(code)
        if (idx >= 0) sonioxLangLabels[idx] else null
      }
      tvSonioxLanguageValue.text = if (names.isEmpty()) getString(R.string.soniox_lang_auto) else names.joinToString(separator = "、")
    }
    updateSonioxLangSummary()

    etAppKey.bindString { prefs.appKey = it }
    etAccessKey.bindString { prefs.accessKey = it }
    etSfApiKey.bindString { prefs.sfApiKey = it }
    etSfModel.bindString { prefs.sfModel = it }
    etElevenApiKey.bindString { prefs.elevenApiKey = it }
    etElevenModel.bindString { prefs.elevenModelId = it }
    etDashApiKey.bindString { prefs.dashApiKey = it }
    etDashModel.bindString { prefs.dashModel = it }
    etDashPrompt.bindString { prefs.dashPrompt = it }
    etOpenAiAsrEndpoint.bindString { prefs.oaAsrEndpoint = it }
    etOpenAiApiKey.bindString { prefs.oaAsrApiKey = it }
    etOpenAiModel.bindString { prefs.oaAsrModel = it }
    etGeminiApiKey.bindString { prefs.gemApiKey = it }
    etGeminiModel.bindString { prefs.gemModel = it }
    etGeminiPrompt.bindString { prefs.gemPrompt = it }

    switchVolcStreaming.setOnCheckedChangeListener { _, isChecked ->
      prefs.volcStreamingEnabled = isChecked
    }
    switchVolcDdc.setOnCheckedChangeListener { _, isChecked ->
      prefs.volcDdcEnabled = isChecked
    }
    switchVolcVad.setOnCheckedChangeListener { _, isChecked ->
      prefs.volcVadEnabled = isChecked
    }
    switchVolcNonstream.setOnCheckedChangeListener { _, isChecked ->
      prefs.volcNonstreamEnabled = isChecked
    }
    switchVolcFirstCharAccel.setOnCheckedChangeListener { _, isChecked ->
      prefs.volcFirstCharAccelEnabled = isChecked
    }

    // 识别语言（nostream/二遍识别用）。空字符串表示“自动（中英/方言）”。
    val langLabels = listOf(
      getString(R.string.volc_lang_auto),
      getString(R.string.volc_lang_en_us),
      getString(R.string.volc_lang_ja_jp),
      getString(R.string.volc_lang_id_id),
      getString(R.string.volc_lang_es_mx),
      getString(R.string.volc_lang_pt_br),
      getString(R.string.volc_lang_de_de),
      getString(R.string.volc_lang_fr_fr),
      getString(R.string.volc_lang_ko_kr),
      getString(R.string.volc_lang_fil_ph),
      getString(R.string.volc_lang_ms_my),
      getString(R.string.volc_lang_th_th),
      getString(R.string.volc_lang_ar_sa)
    )
    val langCodes = listOf(
      "",
      "en-US",
      "ja-JP",
      "id-ID",
      "es-MX",
      "pt-BR",
      "de-DE",
      "fr-FR",
      "ko-KR",
      "fil-PH",
      "ms-MY",
      "th-TH",
      "ar-SA"
    )
    spVolcLanguage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langLabels)
    // 根据已保存的代码选择
    val savedLang = prefs.volcLanguage
    spVolcLanguage.setSelection(langCodes.indexOf(savedLang).coerceAtLeast(0))
    spVolcLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
        val code = langCodes.getOrNull(position) ?: ""
        if (code != prefs.volcLanguage) prefs.volcLanguage = code
      }
      override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    fun updateVolcStreamOptionsVisibility(enabled: Boolean) {
      val vis = if (enabled) View.VISIBLE else View.GONE
      switchVolcDdc.visibility = vis
      switchVolcVad.visibility = vis
      switchVolcNonstream.visibility = vis
      switchVolcFirstCharAccel.visibility = vis
      spVolcLanguage.visibility = vis
      tvVolcLanguageLabel.visibility = vis
    }

    // 初次进入根据当前值处理可见性
    updateVolcStreamOptionsVisibility(prefs.volcStreamingEnabled)
    // 切换时动态展示/隐藏实验性选项
    switchVolcStreaming.setOnCheckedChangeListener { _, isChecked ->
      prefs.volcStreamingEnabled = isChecked
      updateVolcStreamOptionsVisibility(isChecked)
    }
    etSonioxApiKey.bindString { prefs.sonioxApiKey = it }
    switchSonioxStreaming.setOnCheckedChangeListener { _, isChecked ->
      prefs.sonioxStreamingEnabled = isChecked
    }
    tvSonioxLanguageValue.setOnClickListener {
      val saved = prefs.getSonioxLanguages()
      val checked = BooleanArray(sonioxLangCodes.size) { idx ->
        // Auto（空）特殊处理：若未选任何语言则默认选中 auto
        if (idx == 0) saved.isEmpty() else sonioxLangCodes[idx] in saved
      }
      val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(R.string.label_soniox_language)
        .setMultiChoiceItems(sonioxLangLabels.toTypedArray(), checked) { _, which, isChecked ->
          if (which == 0) {
            // 选择了 自动 -> 清空其余
            if (isChecked) {
              for (i in 1 until checked.size) checked[i] = false
            }
          } else if (isChecked) {
            // 选择任意非自动 -> 取消 自动
            checked[0] = false
          }
        }
        .setPositiveButton(R.string.btn_save) { _, _ ->
          // 收集选择
          val codes = mutableListOf<String>()
          for (i in checked.indices) {
            if (checked[i]) {
              val code = sonioxLangCodes[i]
              if (code.isNotEmpty()) codes.add(code)
            }
          }
          prefs.setSonioxLanguages(codes)
          updateSonioxLangSummary()
        }
        .setNegativeButton(R.string.btn_cancel, null)
      builder.show()
    }

    // DashScope 语言：单选（language），空值为自动
    val dashLangLabels = listOf(
      getString(R.string.dash_lang_auto),
      getString(R.string.dash_lang_zh),
      getString(R.string.dash_lang_en),
      getString(R.string.dash_lang_ja),
      getString(R.string.dash_lang_de),
      getString(R.string.dash_lang_ko),
      getString(R.string.dash_lang_ru),
      getString(R.string.dash_lang_fr),
      getString(R.string.dash_lang_pt),
      getString(R.string.dash_lang_ar),
      getString(R.string.dash_lang_it),
      getString(R.string.dash_lang_es)
    )
    val dashLangCodes = listOf(
      "",
      "zh",
      "en",
      "ja",
      "de",
      "ko",
      "ru",
      "fr",
      "pt",
      "ar",
      "it",
      "es"
    )
    spDashLanguage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dashLangLabels)
    val savedDashLang = prefs.dashLanguage
    spDashLanguage.setSelection(dashLangCodes.indexOf(savedDashLang).coerceAtLeast(0))
    spDashLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
        val code = dashLangCodes.getOrNull(position) ?: ""
        if (code != prefs.dashLanguage) prefs.dashLanguage = code
      }
      override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }
  }
}
