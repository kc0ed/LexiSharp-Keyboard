package com.brycewg.asrkb.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.HapticFeedbackConstants
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

class AsrSettingsActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_asr_settings)

    val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
    toolbar.setTitle(R.string.title_asr_settings)
    toolbar.setNavigationOnClickListener { finish() }

    val prefs = Prefs(this)

    val spAsrVendor = findViewById<Spinner>(R.id.spAsrVendor)
    // Silence auto-stop controls
    val switchAutoStopSilence = findViewById<MaterialSwitch>(R.id.switchAutoStopSilence)
    val tvSilenceWindowLabel = findViewById<View>(R.id.tvSilenceWindowLabel)
    val sliderSilenceWindow = findViewById<Slider>(R.id.sliderSilenceWindow)
    val tvSilenceSensitivityLabel = findViewById<View>(R.id.tvSilenceSensitivityLabel)
    val sliderSilenceSensitivity = findViewById<Slider>(R.id.sliderSilenceSensitivity)
    val groupVolc = findViewById<View>(R.id.groupVolc)
    val groupSf = findViewById<View>(R.id.groupSf)
    val groupEleven = findViewById<View>(R.id.groupEleven)
    val groupOpenAi = findViewById<View>(R.id.groupOpenAI)
    val groupDash = findViewById<View>(R.id.groupDashScope)
    val groupGemini = findViewById<View>(R.id.groupGemini)
    val groupSoniox = findViewById<View>(R.id.groupSoniox)
    val groupSenseVoice = findViewById<View>(R.id.groupSenseVoice)
    val switchSfUseOmni = findViewById<MaterialSwitch>(R.id.switchSfUseOmni)
    val tilSfOmniPrompt = findViewById<View>(R.id.tilSfOmniPrompt)
    val switchOpenAiUsePrompt = findViewById<MaterialSwitch>(R.id.switchOpenAiUsePrompt)
    val tilOpenAiPrompt = findViewById<View>(R.id.tilOpenAiPrompt)

    val titleVolc = findViewById<View>(R.id.titleVolc)
    val titleSf = findViewById<View>(R.id.titleSf)
    val titleEleven = findViewById<View>(R.id.titleEleven)
    val titleOpenAi = findViewById<View>(R.id.titleOpenAI)
    val titleDash = findViewById<View>(R.id.titleDash)
    val titleGemini = findViewById<View>(R.id.titleGemini)
    val titleSoniox = findViewById<View>(R.id.titleSoniox)
    val titleSenseVoice = findViewById<View>(R.id.titleSenseVoice)

    val etAppKey = findViewById<EditText>(R.id.etAppKey)
    val etAccessKey = findViewById<EditText>(R.id.etAccessKey)
    val etSfApiKey = findViewById<EditText>(R.id.etSfApiKey)
    val etSfModel = findViewById<EditText>(R.id.etSfModel)
    val etSfOmniPrompt = findViewById<EditText>(R.id.etSfOmniPrompt)
    val etElevenApiKey = findViewById<EditText>(R.id.etElevenApiKey)
    val etElevenModel = findViewById<EditText>(R.id.etElevenModel)
    val tvElevenLanguageLabel = findViewById<View>(R.id.tvElevenLanguageLabel)
    val spElevenLanguage = findViewById<Spinner>(R.id.spElevenLanguage)
    val etDashApiKey = findViewById<EditText>(R.id.etDashApiKey)
    val etDashModel = findViewById<EditText>(R.id.etDashModel)
    val etDashPrompt = findViewById<EditText>(R.id.etDashPrompt)
    val spDashLanguage = findViewById<Spinner>(R.id.spDashLanguage)
    val tvDashLanguageLabel = findViewById<View>(R.id.tvDashLanguageLabel)
    val tvOpenAiLanguageLabel = findViewById<View>(R.id.tvOpenAiLanguageLabel)
    val spOpenAiLanguage = findViewById<Spinner>(R.id.spOpenAiLanguage)
    val etOpenAiAsrEndpoint = findViewById<EditText>(R.id.etOpenAiAsrEndpoint)
    val etOpenAiApiKey = findViewById<EditText>(R.id.etOpenAiApiKey)
    val etOpenAiModel = findViewById<EditText>(R.id.etOpenAiModel)
    val etOpenAiPrompt = findViewById<EditText>(R.id.etOpenAiPrompt)
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
    val sliderSvThreads = findViewById<Slider>(R.id.sliderSvThreads)
    val spSvModelVariant = findViewById<Spinner>(R.id.spSvModelVariant)
    val switchSvUseNnapi = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchSvUseNnapi)
    val spSvLanguage = findViewById<Spinner>(R.id.spSvLanguage)
    val switchSvUseItn = findViewById<MaterialSwitch>(R.id.switchSvUseItn)
    val switchSvPreload = findViewById<MaterialSwitch>(R.id.switchSvPreload)
    val spSvKeepAlive = findViewById<Spinner>(R.id.spSvKeepAlive)
    val btnSvDownload = findViewById<MaterialButton>(R.id.btnSvDownloadModel)
    val btnSvClear = findViewById<MaterialButton>(R.id.btnSvClearModel)
    val tvSvDownloadStatus = findViewById<TextView>(R.id.tvSvDownloadStatus)

    fun applyVendorVisibility(v: AsrVendor) {
      val visMap = mapOf(
        AsrVendor.Volc to listOf(titleVolc, groupVolc),
        AsrVendor.SiliconFlow to listOf(titleSf, groupSf),
        AsrVendor.ElevenLabs to listOf(titleEleven, groupEleven),
        AsrVendor.OpenAI to listOf(titleOpenAi, groupOpenAi),
        AsrVendor.DashScope to listOf(titleDash, groupDash),
        AsrVendor.Gemini to listOf(titleGemini, groupGemini),
        AsrVendor.Soniox to listOf(titleSoniox, groupSoniox),
        AsrVendor.SenseVoice to listOf(titleSenseVoice, groupSenseVoice)
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
      AsrVendor.Soniox,
      AsrVendor.SenseVoice
    )
    val vendorItems = listOf(
      getString(R.string.vendor_volc),
      getString(R.string.vendor_sf),
      getString(R.string.vendor_eleven),
      getString(R.string.vendor_openai),
      getString(R.string.vendor_dashscope),
      getString(R.string.vendor_gemini),
      getString(R.string.vendor_soniox),
      getString(R.string.vendor_sensevoice)
    )
    spAsrVendor.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, vendorItems)
    spAsrVendor.setSelection(vendorOrder.indexOf(prefs.asrVendor).coerceAtLeast(0))
    applyVendorVisibility(prefs.asrVendor)

      spAsrVendor.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
          override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
              val vendor = vendorOrder.getOrNull(position) ?: AsrVendor.Volc
              val old = try { prefs.asrVendor } catch (_: Throwable) { AsrVendor.Volc }
              if (vendor != old) {
                // 更新选择
                prefs.asrVendor = vendor
                // 离开 SenseVoice -> 主动卸载本地模型
                if (old == AsrVendor.SenseVoice && vendor != AsrVendor.SenseVoice) {
                  try { com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer() } catch (_: Throwable) { }
                }
                // 切回 SenseVoice -> 如开启预加载则立即预热
                if (vendor == AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
                  try { com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(this@AsrSettingsActivity, prefs) } catch (_: Throwable) { }
                }
              }
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
    switchSfUseOmni.isChecked = prefs.sfUseOmni
    etSfOmniPrompt.setText(prefs.sfOmniPrompt)
    etElevenApiKey.setText(prefs.elevenApiKey)
    etElevenModel.setText(prefs.elevenModelId)
    etDashApiKey.setText(prefs.dashApiKey)
    etDashModel.setText(prefs.dashModel)
    etDashPrompt.setText(prefs.dashPrompt)
    etOpenAiAsrEndpoint.setText(prefs.oaAsrEndpoint)
    etOpenAiApiKey.setText(prefs.oaAsrApiKey)
    etOpenAiModel.setText(prefs.oaAsrModel)
    switchOpenAiUsePrompt.isChecked = prefs.oaAsrUsePrompt
    etOpenAiPrompt.setText(prefs.oaAsrPrompt)
    etGeminiApiKey.setText(prefs.gemApiKey)
    etGeminiModel.setText(prefs.gemModel)
    etGeminiPrompt.setText(prefs.gemPrompt)
    // Silence auto-stop initial
    switchAutoStopSilence.isChecked = prefs.autoStopOnSilenceEnabled
    sliderSilenceWindow.value = prefs.autoStopSilenceWindowMs.toFloat()
    sliderSilenceSensitivity.value = prefs.autoStopSilenceSensitivity.toFloat()
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
    etSfOmniPrompt.bindString { prefs.sfOmniPrompt = it }

    fun updateSfOmniVisibility(enabled: Boolean) {
      tilSfOmniPrompt.visibility = if (enabled) View.VISIBLE else View.GONE
    }
    fun updateOpenAiPromptVisibility(enabled: Boolean) {
      tilOpenAiPrompt.visibility = if (enabled) View.VISIBLE else View.GONE
    }
    // 初始可见性
    updateSfOmniVisibility(prefs.sfUseOmni)
    updateOpenAiPromptVisibility(prefs.oaAsrUsePrompt)
    switchSfUseOmni.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
      prefs.sfUseOmni = isChecked
      updateSfOmniVisibility(isChecked)
    }
    switchOpenAiUsePrompt.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
      prefs.oaAsrUsePrompt = isChecked
      updateOpenAiPromptVisibility(isChecked)
    }
    etElevenApiKey.bindString { prefs.elevenApiKey = it }
    etElevenModel.bindString { prefs.elevenModelId = it }
    // ElevenLabs 语言选择（单选，空=自动）
    run {
      val elLabels = listOf(
        getString(R.string.eleven_lang_auto),
        getString(R.string.eleven_lang_zh),
        getString(R.string.eleven_lang_en),
        getString(R.string.eleven_lang_ja),
        getString(R.string.eleven_lang_ko),
        getString(R.string.eleven_lang_de),
        getString(R.string.eleven_lang_fr),
        getString(R.string.eleven_lang_es),
        getString(R.string.eleven_lang_pt),
        getString(R.string.eleven_lang_ru),
        getString(R.string.eleven_lang_it)
      )
      val elCodes = listOf(
        "",
        "zh",
        "en",
        "ja",
        "ko",
        "de",
        "fr",
        "es",
        "pt",
        "ru",
        "it"
      )
      spElevenLanguage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, elLabels)
      spElevenLanguage.setSelection(elCodes.indexOf(prefs.elevenLanguageCode).coerceAtLeast(0))
      spElevenLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
          val code = elCodes.getOrNull(position) ?: ""
          if (code != prefs.elevenLanguageCode) prefs.elevenLanguageCode = code
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
      }
    }
    etDashApiKey.bindString { prefs.dashApiKey = it }
    etDashModel.bindString { prefs.dashModel = it }
    etDashPrompt.bindString { prefs.dashPrompt = it }
    etOpenAiAsrEndpoint.bindString { prefs.oaAsrEndpoint = it }
    etOpenAiApiKey.bindString { prefs.oaAsrApiKey = it }
    etOpenAiModel.bindString { prefs.oaAsrModel = it }
    etOpenAiPrompt.bindString { prefs.oaAsrPrompt = it }
    etGeminiApiKey.bindString { prefs.gemApiKey = it }
    etGeminiModel.bindString { prefs.gemModel = it }
    etGeminiPrompt.bindString { prefs.gemPrompt = it }

    // SenseVoice（本地 ASR）设置绑定（路径固定到外部专属目录，无需任何路径输入）
    // 模型版本
    fun updateSvDownloadButtonText() {
      val variant = prefs.svModelVariant
      val text = if (variant == "small-full") getString(R.string.btn_sv_download_model_full) else getString(R.string.btn_sv_download_model_int8)
      btnSvDownload.text = text
    }
    run {
      val variantLabels = listOf(
        getString(R.string.sv_model_small_int8),
        getString(R.string.sv_model_small_full)
      )
      val variantCodes = listOf("small-int8", "small-full")
      spSvModelVariant.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, variantLabels)
      val idx = variantCodes.indexOf(prefs.svModelVariant).coerceAtLeast(0)
      spSvModelVariant.setSelection(idx)
      spSvModelVariant.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
          val code = variantCodes.getOrNull(position) ?: "small-int8"
          if (code != prefs.svModelVariant) {
            prefs.svModelVariant = code
            // 参数变更即预卸载
            try { com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer() } catch (_: Throwable) { }
            // 变更后刷新按钮文案与状态
            updateSvDownloadButtonText()
            updateSvDownloadUiVisibility()
          } else {
            updateSvDownloadButtonText()
            updateSvDownloadUiVisibility()
          }
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
      }
    }
    run {
      val cur = prefs.svNumThreads.coerceIn(1, 8)
      sliderSvThreads.value = cur.toFloat()
      sliderSvThreads.addOnChangeListener { s, value, fromUser ->
        if (fromUser) {
          val v = value.toInt().coerceIn(1, 8)
          if (v != prefs.svNumThreads) {
            prefs.svNumThreads = v
            // 参数变更即预卸载
            try { com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer() } catch (_: Throwable) { }
          }
        }
      }
      sliderSvThreads.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: Slider) { hapticTapIfEnabled(slider) }
        override fun onStopTrackingTouch(slider: Slider) { hapticTapIfEnabled(slider) }
      })
    }
    switchSvUseNnapi.isChecked = prefs.svUseNnapi
    switchSvUseNnapi.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
      if (prefs.svUseNnapi != isChecked) {
        prefs.svUseNnapi = isChecked
        // 参数变更即预卸载
        try { com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer() } catch (_: Throwable) { }
      }
    }

    // SenseVoice 语言与 ITN
    run {
      val labels = listOf(
        getString(R.string.sv_lang_auto),
        getString(R.string.sv_lang_zh),
        getString(R.string.sv_lang_en),
        getString(R.string.sv_lang_ja),
        getString(R.string.sv_lang_ko),
        getString(R.string.sv_lang_yue)
      )
      val codes = listOf("auto", "zh", "en", "ja", "ko", "yue")
      spSvLanguage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
      spSvLanguage.setSelection(codes.indexOf(prefs.svLanguage).coerceAtLeast(0))
      spSvLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
          val code = codes.getOrNull(position) ?: "auto"
          if (code != prefs.svLanguage) {
            prefs.svLanguage = code
            // 参数变更即预卸载
            try { com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer() } catch (_: Throwable) { }
          }
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
      }
    }
    switchSvUseItn.isChecked = prefs.svUseItn
    switchSvUseItn.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
      if (prefs.svUseItn != isChecked) {
        prefs.svUseItn = isChecked
        // 参数变更即预卸载
        try { com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer() } catch (_: Throwable) { }
      }
    }

    // 预加载开关（首次显示键盘/悬浮球时预加载）；开启时若当前渠道为 SenseVoice 则立即预热
    switchSvPreload.isChecked = prefs.svPreloadEnabled
    switchSvPreload.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
      prefs.svPreloadEnabled = isChecked
      if (isChecked && prefs.asrVendor == AsrVendor.SenseVoice) {
        try { com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(this, prefs) } catch (_: Throwable) { }
      }
    }

    // 模型保留时长
    run {
      val labels = listOf(
        getString(R.string.sv_keep_alive_immediate),
        getString(R.string.sv_keep_alive_5m),
        getString(R.string.sv_keep_alive_15m),
        getString(R.string.sv_keep_alive_30m),
        getString(R.string.sv_keep_alive_always)
      )
      val values = listOf(0, 5, 15, 30, -1)
      spSvKeepAlive.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
      val idx = values.indexOf(prefs.svKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
      spSvKeepAlive.setSelection(idx)
      spSvKeepAlive.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
          val v = values.getOrNull(position) ?: -1
          if (v != prefs.svKeepAliveMinutes) prefs.svKeepAliveMinutes = v
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
      }
    }

    updateSvDownloadButtonText()

    // SenseVoice 模型一键下载（按所选版本分别存放）
    btnSvDownload.setOnClickListener { v ->
      v.isEnabled = false
      tvSvDownloadStatus.text = ""
      // 选择下载源（默认 GitHub 官方）
      val sources = arrayOf(
        getString(R.string.download_source_github_official),
        getString(R.string.download_source_mirror_ghproxy),
        getString(R.string.download_source_mirror_gitmirror),
        getString(R.string.download_source_mirror_gh_proxynet)
      )
      val variant = prefs.svModelVariant
      val urlOfficial = if (variant == "small-full")
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2025-09-09.tar.bz2"
      else
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2"
      val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(R.string.download_source_title)
        .setItems(sources) { dlg, which ->
          dlg.dismiss()
          val url = when (which) {
            1 -> "https://ghproxy.net/" + urlOfficial
            2 -> "https://hub.gitmirror.com/" + urlOfficial
            3 -> "https://gh-proxy.net/" + urlOfficial
            else -> urlOfficial
          }
          lifecycleScope.launch {
            try {
              // 下载到 cacheDir
              val tmp = File(cacheDir, if (variant == "small-full") "sv_small_full.tar.bz2" else "sv_small_int8.tar.bz2")
              downloadFile(url, tmp) { progress ->
                tvSvDownloadStatus.text = getString(R.string.sv_download_status_downloading, progress)
              }
              tvSvDownloadStatus.text = getString(R.string.sv_download_status_extracting)
              // 统一下载到 App 专属外部目录（/storage/emulated/0/Android/data/<pkg>/files/）
              val base = getExternalFilesDir(null) ?: filesDir
              val outDirRoot = File(base, "sensevoice")
              val outDir = if (variant == "small-full") File(outDirRoot, "small-full") else File(outDirRoot, "small-int8")
              if (outDir.exists()) outDir.deleteRecursively()
              outDir.mkdirs()
              extractTarBz2(tmp, outDir)
              // 寻找包含 tokens.txt 的目录作为模型目录（仅在该版本目录内）
              val modelDir = findModelDir(outDir)
              tvSvDownloadStatus.text = if (modelDir != null) {
                getString(R.string.sv_download_status_done)
              } else {
                getString(R.string.sv_download_status_failed)
              }
              updateSvDownloadUiVisibility()
            } catch (t: Throwable) {
              tvSvDownloadStatus.text = getString(R.string.sv_download_status_failed)
            } finally {
              v.isEnabled = true
            }
          }
        }
        .setOnDismissListener { v.isEnabled = true }
      builder.show()
    }

    // 清除已下载模型（仅清除当前选定版本）
    btnSvClear.setOnClickListener { v ->
      val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(R.string.sv_clear_confirm_title)
        .setMessage(R.string.sv_clear_confirm_message)
        .setPositiveButton(android.R.string.ok) { d, _ ->
          d.dismiss()
          v.isEnabled = false
          lifecycleScope.launch {
            try {
              val base = getExternalFilesDir(null) ?: filesDir
              val variant = prefs.svModelVariant
              val outDirRoot = File(base, "sensevoice")
              val outDir = if (variant == "small-full") File(outDirRoot, "small-full") else File(outDirRoot, "small-int8")
              if (outDir.exists()) {
                withContext(Dispatchers.IO) { outDir.deleteRecursively() }
              }
              // 卸载当前已加载的本地识别器（与当前版本相关）
              try { com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer() } catch (_: Throwable) { }
              tvSvDownloadStatus.text = getString(R.string.sv_clear_done)
            } catch (_: Throwable) {
              tvSvDownloadStatus.text = getString(R.string.sv_clear_failed)
            } finally {
              v.isEnabled = true
              updateSvDownloadUiVisibility()
            }
          }
        }
        .setNegativeButton(R.string.btn_cancel, null)
        .create()
      dlg.show()
    }

    // 根据本地是否已有模型，更新下载按钮可见性
    updateSvDownloadUiVisibility()

    fun updateSilenceOptionsVisibility(enabled: Boolean) {
      val vis = if (enabled) View.VISIBLE else View.GONE
      tvSilenceWindowLabel.visibility = vis
      sliderSilenceWindow.visibility = vis
      tvSilenceSensitivityLabel.visibility = vis
      sliderSilenceSensitivity.visibility = vis
    }

    // Initial visibility per switch
    updateSilenceOptionsVisibility(prefs.autoStopOnSilenceEnabled)

    switchAutoStopSilence.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
      prefs.autoStopOnSilenceEnabled = isChecked
      updateSilenceOptionsVisibility(isChecked)
    }

    sliderSilenceWindow.addOnChangeListener { _, value, fromUser ->
      if (fromUser) {
        prefs.autoStopSilenceWindowMs = value.toInt().coerceIn(300, 5000)
      }
    }
    sliderSilenceWindow.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
      override fun onStartTrackingTouch(slider: Slider) { hapticTapIfEnabled(slider) }
      override fun onStopTrackingTouch(slider: Slider) { hapticTapIfEnabled(slider) }
    })

    sliderSilenceSensitivity.addOnChangeListener { _, value, fromUser ->
      if (fromUser) {
        prefs.autoStopSilenceSensitivity = value.toInt().coerceIn(1, 10)
      }
    }
    sliderSilenceSensitivity.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
      override fun onStartTrackingTouch(slider: Slider) { hapticTapIfEnabled(slider) }
      override fun onStopTrackingTouch(slider: Slider) { hapticTapIfEnabled(slider) }
    })

    switchVolcStreaming.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
      prefs.volcStreamingEnabled = isChecked
    }
    switchVolcDdc.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
      prefs.volcDdcEnabled = isChecked
    }
    switchVolcVad.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
      prefs.volcVadEnabled = isChecked
    }
    switchVolcNonstream.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
      prefs.volcNonstreamEnabled = isChecked
    }
    switchVolcFirstCharAccel.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
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
      // 语义顺滑开关：非流式也有意义，保持常显
      switchVolcVad.visibility = vis
      switchVolcNonstream.visibility = vis
      switchVolcFirstCharAccel.visibility = vis
      spVolcLanguage.visibility = vis
      tvVolcLanguageLabel.visibility = vis
    }

    // 初次进入根据当前值处理可见性
    updateVolcStreamOptionsVisibility(prefs.volcStreamingEnabled)
    // 切换时动态展示/隐藏实验性选项
    switchVolcStreaming.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
      prefs.volcStreamingEnabled = isChecked
      updateVolcStreamOptionsVisibility(isChecked)
    }
    etSonioxApiKey.bindString { prefs.sonioxApiKey = it }
    switchSonioxStreaming.setOnCheckedChangeListener { btn, isChecked ->
      hapticTapIfEnabled(btn)
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
        .setPositiveButton(android.R.string.ok) { _, _ ->
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
    // OpenAI 语言：单选（language），空值为自动
    run {
      val oaLangLabels = listOf(
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
      val oaLangCodes = listOf(
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
      spOpenAiLanguage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, oaLangLabels)
      spOpenAiLanguage.setSelection(oaLangCodes.indexOf(prefs.oaAsrLanguage).coerceAtLeast(0))
      spOpenAiLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
          val code = oaLangCodes.getOrNull(position) ?: ""
          if (code != prefs.oaAsrLanguage) prefs.oaAsrLanguage = code
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
      }
    }
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

  override fun onResume() {
    super.onResume()
    updateSvDownloadUiVisibility()
  }

  private fun updateSvDownloadUiVisibility() {
    val base = getExternalFilesDir(null) ?: filesDir
    val root = File(base, "sensevoice")
    val variant = Prefs(this).svModelVariant
    val dir = if (variant == "small-full") File(root, "small-full") else File(root, "small-int8")
    val modelDir = findModelDir(dir)
    val ready = modelDir != null &&
      (File(modelDir, "tokens.txt").exists()) &&
      (File(modelDir, "model.int8.onnx").exists() || File(modelDir, "model.onnx").exists())
    val btn = findViewById<MaterialButton>(R.id.btnSvDownloadModel)
    val btnClear = findViewById<MaterialButton>(R.id.btnSvClearModel)
    val tv = findViewById<TextView>(R.id.tvSvDownloadStatus)
    btn.visibility = if (ready) View.GONE else View.VISIBLE
    btnClear.visibility = if (ready) View.VISIBLE else View.GONE
    if (ready && tv.text.isNullOrBlank()) {
      tv.text = getString(R.string.sv_download_status_done)
    }
  }

  private suspend fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    val req = Request.Builder().url(url).build()
    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) throw IllegalStateException("HTTP ${'$'}{resp.code}")
      val body = resp.body ?: throw IllegalStateException("empty body")
      val total = body.contentLength()
      dest.outputStream().use { out ->
        var readSum = 0L
        val buf = ByteArray(8192)
        body.byteStream().use { ins ->
          while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            readSum += n
            if (total > 0L) {
              val p = ((readSum * 100) / total).toInt().coerceIn(0, 100)
              withContext(Dispatchers.Main) { onProgress(p) }
            }
          }
        }
      }
    }
  }

  private suspend fun extractTarBz2(file: File, outDir: File) = withContext(Dispatchers.IO) {
    // BZip2 是单线程、CPU 密集型；使用更大的缓冲以略微降低 I/O 调用次数
    BZip2CompressorInputStream(file.inputStream().buffered(64 * 1024)).use { bz ->
      TarArchiveInputStream(bz).use { tar ->
        var entry = tar.getNextTarEntry()
        val buf = ByteArray(64 * 1024)
        while (entry != null) {
          val outFile = File(outDir, entry.name)
          if (entry.isDirectory) {
            outFile.mkdirs()
          } else {
            outFile.parentFile?.mkdirs()
            java.io.BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { bos ->
              var n: Int
              while (true) {
                n = tar.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
              }
              bos.flush()
            }
          }
          entry = tar.getNextTarEntry()
        }
      }
    }
  }

  private fun findModelDir(root: File): File? {
    if (!root.exists()) return null
    // 1) 直接包含 tokens.txt
    val direct = File(root, "tokens.txt")
    if (direct.exists()) return root
    // 2) 子目录搜索一层
    val subs = root.listFiles() ?: return null
    subs.forEach { f ->
      if (f.isDirectory) {
        val t = File(f, "tokens.txt")
        if (t.exists()) return f
      }
    }
    return null
  }

  private fun hapticTapIfEnabled(view: View?) {
    try {
      if (Prefs(this).micHapticEnabled) view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    } catch (_: Throwable) { }
  }
}
