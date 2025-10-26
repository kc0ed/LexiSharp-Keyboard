package com.brycewg.asrkb.ui.settings.asr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.asr.VadDetector
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * ASR Settings Activity - Refactored version with ViewModel pattern.
 *
 * Key improvements:
 * 1. Introduced AsrSettingsViewModel to manage state and business logic
 * 2. Split giant onCreate into focused setup methods
 * 3. Created reusable showSingleChoiceDialog function
 * 4. Added proper logging to all catch blocks
 * 5. Vendor-specific settings organized in separate methods
 */
class AsrSettingsActivity : AppCompatActivity() {

    private lateinit var viewModel: AsrSettingsViewModel
    private lateinit var prefs: Prefs

    // View references grouped by function
    private lateinit var tvAsrVendor: TextView

    // Silence detection views
    private lateinit var switchAutoStopSilence: MaterialSwitch
    private lateinit var tvSilenceWindowLabel: View
    private lateinit var sliderSilenceWindow: Slider
    private lateinit var tvSilenceSensitivityLabel: View
    private lateinit var sliderSilenceSensitivity: Slider

    // Vendor group containers
    private lateinit var groupVolc: View
    private lateinit var groupSf: View
    private lateinit var groupEleven: View
    private lateinit var groupOpenAi: View
    private lateinit var groupDash: View
    private lateinit var groupGemini: View
    private lateinit var groupSoniox: View
    private lateinit var groupSenseVoice: View

    // Vendor title views
    private lateinit var titleVolc: View
    private lateinit var titleSf: View
    private lateinit var titleEleven: View
    private lateinit var titleOpenAi: View
    private lateinit var titleDash: View
    private lateinit var titleGemini: View
    private lateinit var titleSoniox: View
    private lateinit var titleSenseVoice: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr_settings)

        prefs = Prefs(this)
        viewModel = ViewModelProvider(this)[AsrSettingsViewModel::class.java]
        viewModel.initialize(this)

        setupToolbar()
        initializeViews()
        setupVendorSelection()
        setupSilenceDetection()
        setupVendorSpecificSettings()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        updateSvDownloadUiVisibility()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.title_asr_settings)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initializeViews() {
        // ASR Vendor
        tvAsrVendor = findViewById(R.id.tvAsrVendorValue)

        // Silence auto-stop controls
        switchAutoStopSilence = findViewById(R.id.switchAutoStopSilence)
        tvSilenceWindowLabel = findViewById(R.id.tvSilenceWindowLabel)
        sliderSilenceWindow = findViewById(R.id.sliderSilenceWindow)
        tvSilenceSensitivityLabel = findViewById(R.id.tvSilenceSensitivityLabel)
        sliderSilenceSensitivity = findViewById(R.id.sliderSilenceSensitivity)

        // Vendor groups
        groupVolc = findViewById(R.id.groupVolc)
        groupSf = findViewById(R.id.groupSf)
        groupEleven = findViewById(R.id.groupEleven)
        groupOpenAi = findViewById(R.id.groupOpenAI)
        groupDash = findViewById(R.id.groupDashScope)
        groupGemini = findViewById(R.id.groupGemini)
        groupSoniox = findViewById(R.id.groupSoniox)
        groupSenseVoice = findViewById(R.id.groupSenseVoice)

        // Vendor titles
        titleVolc = findViewById(R.id.titleVolc)
        titleSf = findViewById(R.id.titleSf)
        titleEleven = findViewById(R.id.titleEleven)
        titleOpenAi = findViewById(R.id.titleOpenAI)
        titleDash = findViewById(R.id.titleDash)
        titleGemini = findViewById(R.id.titleGemini)
        titleSoniox = findViewById(R.id.titleSoniox)
        titleSenseVoice = findViewById(R.id.titleSenseVoice)
    }

    private fun setupVendorSelection() {
        val vendorOrder = listOf(
            AsrVendor.Volc, AsrVendor.SiliconFlow, AsrVendor.ElevenLabs,
            AsrVendor.OpenAI, AsrVendor.DashScope, AsrVendor.Gemini,
            AsrVendor.Soniox, AsrVendor.SenseVoice
        )
        val vendorItems = listOf(
            getString(R.string.vendor_volc), getString(R.string.vendor_sf),
            getString(R.string.vendor_eleven), getString(R.string.vendor_openai),
            getString(R.string.vendor_dashscope), getString(R.string.vendor_gemini),
            getString(R.string.vendor_soniox), getString(R.string.vendor_sensevoice)
        )

        tvAsrVendor.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val curIdx = vendorOrder.indexOf(prefs.asrVendor).coerceAtLeast(0)
            showSingleChoiceDialog(
                titleResId = R.string.label_asr_vendor,
                items = vendorItems.toTypedArray(),
                currentIndex = curIdx
            ) { selectedIdx ->
                val vendor = vendorOrder.getOrNull(selectedIdx) ?: AsrVendor.Volc
                viewModel.updateVendor(vendor)
            }
        }
    }

    private fun setupSilenceDetection() {
        // Initial values
        switchAutoStopSilence.isChecked = prefs.autoStopOnSilenceEnabled
        sliderSilenceWindow.value = prefs.autoStopSilenceWindowMs.toFloat()
        sliderSilenceSensitivity.value = prefs.autoStopSilenceSensitivity.toFloat()

        // Switch listener
        switchAutoStopSilence.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            viewModel.updateAutoStopSilence(isChecked)
            // 若开启，则立即预加载全局 VAD，避免下次录音首次加载
            if (isChecked) {
                try { VadDetector.preload(applicationContext, 16000, prefs.autoStopSilenceSensitivity) } catch (_: Throwable) { }
            }
        }

        // Sliders
        setupSlider(sliderSilenceWindow) { value ->
            viewModel.updateSilenceWindow(value.toInt().coerceIn(300, 5000))
        }

        setupSlider(sliderSilenceSensitivity) { value ->
            viewModel.updateSilenceSensitivity(value.toInt().coerceIn(1, 10))
        }
        // 在松手时“立即生效”：重建全局 VAD，以新的灵敏度用于后续会话
        sliderSilenceSensitivity.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { /* no-op */ }
            override fun onStopTrackingTouch(slider: Slider) {
                try {
                    if (prefs.autoStopOnSilenceEnabled) {
                        VadDetector.rebuildGlobal(applicationContext, 16000, prefs.autoStopSilenceSensitivity)
                        Toast.makeText(this@AsrSettingsActivity, R.string.toast_vad_sensitivity_applied, Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Throwable) { }
            }
        })

    }

    private fun setupVendorSpecificSettings() {
        setupVolcengineSettings()
        setupSiliconFlowSettings()
        setupElevenLabsSettings()
        setupOpenAISettings()
        setupDashScopeSettings()
        setupGeminiSettings()
        setupSonioxSettings()
        setupSenseVoiceSettings()
    }

    private fun setupVolcengineSettings() {
        // EditTexts
        findViewById<EditText>(R.id.etAppKey).apply {
            setText(prefs.appKey)
            bindString { prefs.appKey = it }
        }
        findViewById<EditText>(R.id.etAccessKey).apply {
            setText(prefs.accessKey)
            bindString { prefs.accessKey = it }
        }

        // Switches
        findViewById<MaterialSwitch>(R.id.switchVolcStreaming).apply {
            isChecked = prefs.volcStreamingEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateVolcStreaming(isChecked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchVolcDdc).apply {
            isChecked = prefs.volcDdcEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateVolcDdc(isChecked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchVolcVad).apply {
            isChecked = prefs.volcVadEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateVolcVad(isChecked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchVolcNonstream).apply {
            isChecked = prefs.volcNonstreamEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateVolcNonstream(isChecked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchVolcFirstCharAccel).apply {
            isChecked = prefs.volcFirstCharAccelEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateVolcFirstCharAccel(isChecked)
            }
        }

        // Language selection
        setupVolcLanguageSelection()

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnVolcGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://console.volcengine.com/iam/keymanage/")
        }
    }

    private fun setupVolcLanguageSelection() {
        val langLabels = listOf(
            getString(R.string.volc_lang_auto), getString(R.string.volc_lang_en_us),
            getString(R.string.volc_lang_ja_jp), getString(R.string.volc_lang_id_id),
            getString(R.string.volc_lang_es_mx), getString(R.string.volc_lang_pt_br),
            getString(R.string.volc_lang_de_de), getString(R.string.volc_lang_fr_fr),
            getString(R.string.volc_lang_ko_kr), getString(R.string.volc_lang_fil_ph),
            getString(R.string.volc_lang_ms_my), getString(R.string.volc_lang_th_th),
            getString(R.string.volc_lang_ar_sa)
        )
        val langCodes = listOf(
            "", "en-US", "ja-JP", "id-ID", "es-MX", "pt-BR",
            "de-DE", "fr-FR", "ko-KR", "fil-PH", "ms-MY", "th-TH", "ar-SA"
        )
        val tvVolcLanguage = findViewById<TextView>(R.id.tvVolcLanguageValue)

        fun updateVolcLangSummary() {
            val idx = langCodes.indexOf(prefs.volcLanguage).coerceAtLeast(0)
            tvVolcLanguage.text = langLabels[idx]
        }

        updateVolcLangSummary()
        tvVolcLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = langCodes.indexOf(prefs.volcLanguage).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_volc_language, langLabels.toTypedArray(), cur) { which ->
                val code = langCodes.getOrNull(which) ?: ""
                viewModel.updateVolcLanguage(code)
                updateVolcLangSummary()
            }
        }
    }

    private fun setupSiliconFlowSettings() {
        findViewById<EditText>(R.id.etSfApiKey).apply {
            setText(prefs.sfApiKey)
            bindString { prefs.sfApiKey = it }
        }
        findViewById<EditText>(R.id.etSfModel).apply {
            setText(prefs.sfModel)
            bindString { prefs.sfModel = it }
        }
        findViewById<EditText>(R.id.etSfOmniPrompt).apply {
            setText(prefs.sfOmniPrompt)
            bindString { prefs.sfOmniPrompt = it }
        }

        findViewById<MaterialSwitch>(R.id.switchSfUseOmni).apply {
            isChecked = prefs.sfUseOmni
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateSfUseOmni(isChecked)
            }
        }

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSfGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://siliconflow.cn/console/api-keys")
        }
    }

    private fun setupElevenLabsSettings() {
        findViewById<EditText>(R.id.etElevenApiKey).apply {
            setText(prefs.elevenApiKey)
            bindString { prefs.elevenApiKey = it }
        }
        findViewById<EditText>(R.id.etElevenModel).apply {
            setText(prefs.elevenModelId)
            bindString { prefs.elevenModelId = it }
        }

        setupElevenLanguageSelection()

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnElevenGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://elevenlabs.io/app/settings/api-keys")
        }
    }

    private fun setupElevenLanguageSelection() {
        val elLabels = listOf(
            getString(R.string.eleven_lang_auto), getString(R.string.eleven_lang_zh),
            getString(R.string.eleven_lang_en), getString(R.string.eleven_lang_ja),
            getString(R.string.eleven_lang_ko), getString(R.string.eleven_lang_de),
            getString(R.string.eleven_lang_fr), getString(R.string.eleven_lang_es),
            getString(R.string.eleven_lang_pt), getString(R.string.eleven_lang_ru),
            getString(R.string.eleven_lang_it)
        )
        val elCodes = listOf("", "zh", "en", "ja", "ko", "de", "fr", "es", "pt", "ru", "it")
        val tvElevenLanguage = findViewById<TextView>(R.id.tvElevenLanguageValue)

        fun updateElSummary() {
            val idx = elCodes.indexOf(prefs.elevenLanguageCode).coerceAtLeast(0)
            tvElevenLanguage.text = elLabels[idx]
        }

        updateElSummary()
        tvElevenLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = elCodes.indexOf(prefs.elevenLanguageCode).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_eleven_language, elLabels.toTypedArray(), cur) { which ->
                val code = elCodes.getOrNull(which) ?: ""
                if (code != prefs.elevenLanguageCode) prefs.elevenLanguageCode = code
                updateElSummary()
            }
        }
    }

    private fun setupOpenAISettings() {
        findViewById<EditText>(R.id.etOpenAiAsrEndpoint).apply {
            setText(prefs.oaAsrEndpoint)
            bindString { prefs.oaAsrEndpoint = it }
        }
        findViewById<EditText>(R.id.etOpenAiApiKey).apply {
            setText(prefs.oaAsrApiKey)
            bindString { prefs.oaAsrApiKey = it }
        }
        findViewById<EditText>(R.id.etOpenAiModel).apply {
            setText(prefs.oaAsrModel)
            bindString { prefs.oaAsrModel = it }
        }
        findViewById<EditText>(R.id.etOpenAiPrompt).apply {
            setText(prefs.oaAsrPrompt)
            bindString { prefs.oaAsrPrompt = it }
        }

        findViewById<MaterialSwitch>(R.id.switchOpenAiUsePrompt).apply {
            isChecked = prefs.oaAsrUsePrompt
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateOpenAiUsePrompt(isChecked)
            }
        }

        setupOpenAILanguageSelection()

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenAiGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://platform.openai.com/api-keys")
        }
    }

    private fun setupOpenAILanguageSelection() {
        val langLabels = listOf(
            getString(R.string.dash_lang_auto), getString(R.string.dash_lang_zh),
            getString(R.string.dash_lang_en), getString(R.string.dash_lang_ja),
            getString(R.string.dash_lang_de), getString(R.string.dash_lang_ko),
            getString(R.string.dash_lang_ru), getString(R.string.dash_lang_fr),
            getString(R.string.dash_lang_pt), getString(R.string.dash_lang_ar),
            getString(R.string.dash_lang_it), getString(R.string.dash_lang_es)
        )
        val langCodes = listOf("", "zh", "en", "ja", "de", "ko", "ru", "fr", "pt", "ar", "it", "es")
        val tvOpenAiLanguage = findViewById<TextView>(R.id.tvOpenAiLanguageValue)

        fun updateOaLangSummary() {
            val idx = langCodes.indexOf(prefs.oaAsrLanguage).coerceAtLeast(0)
            tvOpenAiLanguage.text = langLabels[idx]
        }

        updateOaLangSummary()
        tvOpenAiLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = langCodes.indexOf(prefs.oaAsrLanguage).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_openai_language, langLabels.toTypedArray(), cur) { which ->
                val code = langCodes.getOrNull(which) ?: ""
                if (code != prefs.oaAsrLanguage) prefs.oaAsrLanguage = code
                updateOaLangSummary()
            }
        }
    }

    private fun setupDashScopeSettings() {
        findViewById<EditText>(R.id.etDashApiKey).apply {
            setText(prefs.dashApiKey)
            bindString { prefs.dashApiKey = it }
        }
        findViewById<EditText>(R.id.etDashModel).apply {
            setText(prefs.dashModel)
            bindString { prefs.dashModel = it }
        }
        findViewById<EditText>(R.id.etDashPrompt).apply {
            setText(prefs.dashPrompt)
            bindString { prefs.dashPrompt = it }
        }

        setupDashLanguageSelection()

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDashGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://dashscope.console.aliyun.com/apiKeys")
        }
    }

    private fun setupDashLanguageSelection() {
        val langLabels = listOf(
            getString(R.string.dash_lang_auto), getString(R.string.dash_lang_zh),
            getString(R.string.dash_lang_en), getString(R.string.dash_lang_ja),
            getString(R.string.dash_lang_de), getString(R.string.dash_lang_ko),
            getString(R.string.dash_lang_ru), getString(R.string.dash_lang_fr),
            getString(R.string.dash_lang_pt), getString(R.string.dash_lang_ar),
            getString(R.string.dash_lang_it), getString(R.string.dash_lang_es)
        )
        val langCodes = listOf("", "zh", "en", "ja", "de", "ko", "ru", "fr", "pt", "ar", "it", "es")
        val tvDashLanguage = findViewById<TextView>(R.id.tvDashLanguageValue)

        fun updateDashLangSummary() {
            val idx = langCodes.indexOf(prefs.dashLanguage).coerceAtLeast(0)
            tvDashLanguage.text = langLabels[idx]
        }

        updateDashLangSummary()
        tvDashLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = langCodes.indexOf(prefs.dashLanguage).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_dash_language, langLabels.toTypedArray(), cur) { which ->
                val code = langCodes.getOrNull(which) ?: ""
                if (code != prefs.dashLanguage) prefs.dashLanguage = code
                updateDashLangSummary()
            }
        }
    }

    private fun setupGeminiSettings() {
        findViewById<EditText>(R.id.etGeminiApiKey).apply {
            setText(prefs.gemApiKey)
            bindString { prefs.gemApiKey = it }
        }
        findViewById<EditText>(R.id.etGeminiModel).apply {
            setText(prefs.gemModel)
            bindString { prefs.gemModel = it }
        }
        
        // 自定义词汇输入框 - 支持直接编辑
        findViewById<EditText>(R.id.etGeminiPrompt).apply {
            setText(prefs.gemPrompt)
            bindString { prefs.gemPrompt = it }
        }
        
        // 编辑按钮点击事件 - 打开全屏编辑对话框
        findViewById<MaterialButton>(R.id.btnGeminiEditPrompt).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            showCustomWordsEditDialog()
        }

        findViewById<MaterialSwitch>(R.id.switchGeminiDisableThinking).apply {
            isChecked = prefs.geminiDisableThinking
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                prefs.geminiDisableThinking = isChecked
            }
        }

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGeminiGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://aistudio.google.com/app/apikey")
        }
    }
    
    private fun showCustomWordsEditDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_custom_words, null)
        val etCustomWords = dialogView.findViewById<EditText>(R.id.etCustomWords)
        val toolbar = dialogView.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSave)
        
        // 设置当前内容
        etCustomWords.setText(prefs.gemPrompt)
        
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()
        
        // Toolbar 关闭按钮
        toolbar.setNavigationOnClickListener {
            dialog.dismiss()
        }
        
        // 取消按钮
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        // 保存按钮
        btnSave.setOnClickListener {
            val newText = etCustomWords.text.toString()
            prefs.gemPrompt = newText
            // 更新主界面的预览
            findViewById<EditText>(R.id.etGeminiPrompt).setText(newText)
            dialog.dismiss()
        }
        
        dialog.show()
        
        // 设置对话框为全屏
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun setupSonioxSettings() {
        findViewById<EditText>(R.id.etSonioxApiKey).apply {
            setText(prefs.sonioxApiKey)
            bindString { prefs.sonioxApiKey = it }
        }

        findViewById<MaterialSwitch>(R.id.switchSonioxStreaming).apply {
            isChecked = prefs.sonioxStreamingEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateSonioxStreaming(isChecked)
            }
        }

        setupSonioxLanguageSelection()

        // Key guide link
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSonioxGetKey).setOnClickListener { v ->
            hapticTapIfEnabled(v)
            openUrlSafely("https://soniox.com/account/api-keys")
        }
    }

    private fun setupSonioxLanguageSelection() {
        val sonioxLangLabels = listOf(
            getString(R.string.soniox_lang_auto), getString(R.string.soniox_lang_en),
            getString(R.string.soniox_lang_zh), getString(R.string.soniox_lang_ja),
            getString(R.string.soniox_lang_ko), getString(R.string.soniox_lang_es),
            getString(R.string.soniox_lang_pt), getString(R.string.soniox_lang_de),
            getString(R.string.soniox_lang_fr), getString(R.string.soniox_lang_id),
            getString(R.string.soniox_lang_ru), getString(R.string.soniox_lang_ar),
            getString(R.string.soniox_lang_hi), getString(R.string.soniox_lang_vi),
            getString(R.string.soniox_lang_th), getString(R.string.soniox_lang_ms),
            getString(R.string.soniox_lang_fil)
        )
        val sonioxLangCodes = listOf(
            "", "en", "zh", "ja", "ko", "es", "pt", "de",
            "fr", "id", "ru", "ar", "hi", "vi", "th", "ms", "fil"
        )
        val tvSonioxLanguage = findViewById<TextView>(R.id.tvSonioxLanguageValue)

        fun updateSonioxLangSummary() {
            val selected = prefs.getSonioxLanguages()
            if (selected.isEmpty()) {
                tvSonioxLanguage.text = getString(R.string.soniox_lang_auto)
                return
            }
            val names = selected.mapNotNull { code ->
                val idx = sonioxLangCodes.indexOf(code)
                if (idx >= 0) sonioxLangLabels[idx] else null
            }
            tvSonioxLanguage.text = if (names.isEmpty()) {
                getString(R.string.soniox_lang_auto)
            } else {
                names.joinToString(separator = "、")
            }
        }

        updateSonioxLangSummary()
        tvSonioxLanguage.setOnClickListener {
            val saved = prefs.getSonioxLanguages()
            val checked = BooleanArray(sonioxLangCodes.size) { idx ->
                if (idx == 0) saved.isEmpty() else sonioxLangCodes[idx] in saved
            }
            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.label_soniox_language)
                .setMultiChoiceItems(sonioxLangLabels.toTypedArray(), checked) { _, which, isChecked ->
                    if (which == 0) {
                        if (isChecked) {
                            for (i in 1 until checked.size) checked[i] = false
                        }
                    } else if (isChecked) {
                        checked[0] = false
                    }
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val codes = mutableListOf<String>()
                    for (i in checked.indices) {
                        if (checked[i]) {
                            val code = sonioxLangCodes[i]
                            if (code.isNotEmpty()) codes.add(code)
                        }
                    }
                    viewModel.updateSonioxLanguages(codes)
                    updateSonioxLangSummary()
                }
                .setNegativeButton(R.string.btn_cancel, null)
            builder.show()
        }
    }

    private fun setupSenseVoiceSettings() {
        // Model variant
        setupSvModelVariantSelection()

        // Language
        setupSvLanguageSelection()

        // Thread count
        findViewById<Slider>(R.id.sliderSvThreads).apply {
            value = prefs.svNumThreads.coerceIn(1, 8).toFloat()
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    val v = value.toInt().coerceIn(1, 8)
                    if (v != prefs.svNumThreads) {
                        viewModel.updateSvNumThreads(v)
                    }
                }
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
                override fun onStopTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
            })
        }

        // Switches
        findViewById<MaterialSwitch>(R.id.switchSvUseItn).apply {
            isChecked = prefs.svUseItn
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateSvUseItn(isChecked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchSvPreload).apply {
            isChecked = prefs.svPreloadEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateSvPreload(isChecked)
            }
        }

        findViewById<MaterialSwitch>(R.id.switchSvPseudoStreaming).apply {
            isChecked = prefs.svPseudoStreamingEnabled
            setOnCheckedChangeListener { btn, isChecked ->
                hapticTapIfEnabled(btn)
                viewModel.updateSvPseudoStreaming(isChecked)
            }
        }

        // Keep alive
        setupSvKeepAliveSelection()

        // Download/Clear buttons
        setupSvDownloadButtons()
    }

    private fun setupSvModelVariantSelection() {
        val variantLabels = listOf(
            getString(R.string.sv_model_small_int8),
            getString(R.string.sv_model_small_full)
        )
        val variantCodes = listOf("small-int8", "small-full")
        val tvSvModelVariant = findViewById<TextView>(R.id.tvSvModelVariantValue)
        val btnSvDownload = findViewById<MaterialButton>(R.id.btnSvDownloadModel)

        fun updateVariantSummary() {
            val idx = variantCodes.indexOf(prefs.svModelVariant).coerceAtLeast(0)
            tvSvModelVariant.text = variantLabels[idx]
        }

        fun updateDownloadButtonText() {
            val variant = prefs.svModelVariant
            val text = if (variant == "small-full") {
                getString(R.string.btn_sv_download_model_full)
            } else {
                getString(R.string.btn_sv_download_model_int8)
            }
            btnSvDownload.text = text
        }

        updateVariantSummary()
        updateDownloadButtonText()

        tvSvModelVariant.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = variantCodes.indexOf(prefs.svModelVariant).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_sv_model_variant, variantLabels.toTypedArray(), cur) { which ->
                val code = variantCodes.getOrNull(which) ?: "small-int8"
                if (code != prefs.svModelVariant) {
                    viewModel.updateSvModelVariant(code)
                }
                updateVariantSummary()
                updateDownloadButtonText()
                updateSvDownloadUiVisibility()
            }
        }
    }

    private fun setupSvLanguageSelection() {
        val labels = listOf(
            getString(R.string.sv_lang_auto), getString(R.string.sv_lang_zh),
            getString(R.string.sv_lang_en), getString(R.string.sv_lang_ja),
            getString(R.string.sv_lang_ko), getString(R.string.sv_lang_yue)
        )
        val codes = listOf("auto", "zh", "en", "ja", "ko", "yue")
        val tvSvLanguage = findViewById<TextView>(R.id.tvSvLanguageValue)

        fun updateSvLangSummary() {
            val idx = codes.indexOf(prefs.svLanguage).coerceAtLeast(0)
            tvSvLanguage.text = labels[idx]
        }

        updateSvLangSummary()
        tvSvLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = codes.indexOf(prefs.svLanguage).coerceAtLeast(0)
            showSingleChoiceDialog(R.string.label_sv_language, labels.toTypedArray(), cur) { which ->
                val code = codes.getOrNull(which) ?: "auto"
                if (code != prefs.svLanguage) {
                    viewModel.updateSvLanguage(code)
                }
                updateSvLangSummary()
            }
        }
    }

    private fun setupSvKeepAliveSelection() {
        val labels = listOf(
            getString(R.string.sv_keep_alive_immediate),
            getString(R.string.sv_keep_alive_5m),
            getString(R.string.sv_keep_alive_15m),
            getString(R.string.sv_keep_alive_30m),
            getString(R.string.sv_keep_alive_always)
        )
        val values = listOf(0, 5, 15, 30, -1)
        val tvSvKeepAlive = findViewById<TextView>(R.id.tvSvKeepAliveValue)

        fun updateKeepAliveSummary() {
            val idx = values.indexOf(prefs.svKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            tvSvKeepAlive.text = labels[idx]
        }

        updateKeepAliveSummary()
        tvSvKeepAlive.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val cur = values.indexOf(prefs.svKeepAliveMinutes).let { if (it >= 0) it else values.size - 1 }
            showSingleChoiceDialog(R.string.label_sv_keep_alive, labels.toTypedArray(), cur) { which ->
                val vv = values.getOrNull(which) ?: -1
                if (vv != prefs.svKeepAliveMinutes) {
                    viewModel.updateSvKeepAlive(vv)
                }
                updateKeepAliveSummary()
            }
        }
    }

    private fun setupSvDownloadButtons() {
        val btnSvDownload = findViewById<MaterialButton>(R.id.btnSvDownloadModel)
        val btnSvClear = findViewById<MaterialButton>(R.id.btnSvClearModel)
        val tvSvDownloadStatus = findViewById<TextView>(R.id.tvSvDownloadStatus)

        btnSvDownload.setOnClickListener { v ->
            v.isEnabled = false
            tvSvDownloadStatus.text = ""

            val sources = arrayOf(
                getString(R.string.download_source_github_official),
                getString(R.string.download_source_mirror_ghproxy),
                getString(R.string.download_source_mirror_gitmirror),
                getString(R.string.download_source_mirror_gh_proxynet)
            )
            val variant = prefs.svModelVariant
            val urlOfficial = if (variant == "small-full") {
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2"
            } else {
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.download_source_title)
                .setItems(sources) { dlg, which ->
                    dlg.dismiss()
                    val url = when (which) {
                        1 -> "https://ghproxy.net/$urlOfficial"
                        2 -> "https://hub.gitmirror.com/$urlOfficial"
                        3 -> "https://gh-proxy.net/$urlOfficial"
                        else -> urlOfficial
                    }
                    try {
                        ModelDownloadService.startDownload(this, url, variant)
                        tvSvDownloadStatus.text = getString(R.string.sv_download_started_in_bg)
                    } catch (e: Throwable) {
                        android.util.Log.e(TAG, "Failed to start model download", e)
                        tvSvDownloadStatus.text = getString(R.string.sv_download_status_failed)
                    } finally {
                        v.isEnabled = true
                    }
                }
                .setOnDismissListener { v.isEnabled = true }
                .show()
        }

        btnSvClear.setOnClickListener { v ->
            androidx.appcompat.app.AlertDialog.Builder(this)
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
                            val outDir = if (variant == "small-full") {
                                File(outDirRoot, "small-full")
                            } else {
                                File(outDirRoot, "small-int8")
                            }
                            if (outDir.exists()) {
                                withContext(Dispatchers.IO) { outDir.deleteRecursively() }
                            }
                            try {
                                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
                            } catch (e: Throwable) {
                                android.util.Log.e(TAG, "Failed to unload SenseVoice recognizer", e)
                            }
                            tvSvDownloadStatus.text = getString(R.string.sv_clear_done)
                        } catch (e: Throwable) {
                            android.util.Log.e(TAG, "Failed to clear model", e)
                            tvSvDownloadStatus.text = getString(R.string.sv_clear_failed)
                        } finally {
                            v.isEnabled = true
                            updateSvDownloadUiVisibility()
                        }
                    }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .create()
                .show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateVendorSummary(state.selectedVendor)
                updateVendorVisibility(state)
                updateSilenceOptionsVisibility(state.autoStopSilenceEnabled)
                updateSfOmniVisibility(state.sfUseOmni)
                updateOpenAiPromptVisibility(state.oaAsrUsePrompt)
                updateVolcStreamOptionsVisibility(state.volcStreamingEnabled)
            }
        }
    }

    private fun updateVendorSummary(vendor: AsrVendor) {
        val vendorOrder = listOf(
            AsrVendor.Volc, AsrVendor.SiliconFlow, AsrVendor.ElevenLabs,
            AsrVendor.OpenAI, AsrVendor.DashScope, AsrVendor.Gemini,
            AsrVendor.Soniox, AsrVendor.SenseVoice
        )
        val vendorItems = listOf(
            getString(R.string.vendor_volc), getString(R.string.vendor_sf),
            getString(R.string.vendor_eleven), getString(R.string.vendor_openai),
            getString(R.string.vendor_dashscope), getString(R.string.vendor_gemini),
            getString(R.string.vendor_soniox), getString(R.string.vendor_sensevoice)
        )
        val idx = vendorOrder.indexOf(vendor).coerceAtLeast(0)
        tvAsrVendor.text = vendorItems[idx]
    }

    private fun updateVendorVisibility(state: AsrSettingsUiState) {
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
            val vis = if (vendor == state.selectedVendor) View.VISIBLE else View.GONE
            views.forEach { v ->
                if (v.visibility != vis) v.visibility = vis
            }
        }
    }

    private fun updateSilenceOptionsVisibility(enabled: Boolean) {
        val vis = if (enabled) View.VISIBLE else View.GONE
        if (tvSilenceWindowLabel.visibility != vis) tvSilenceWindowLabel.visibility = vis
        if (sliderSilenceWindow.visibility != vis) sliderSilenceWindow.visibility = vis
        if (tvSilenceSensitivityLabel.visibility != vis) tvSilenceSensitivityLabel.visibility = vis
        if (sliderSilenceSensitivity.visibility != vis) sliderSilenceSensitivity.visibility = vis
    }

    private fun updateSfOmniVisibility(enabled: Boolean) {
        val til = findViewById<View>(R.id.tilSfOmniPrompt)
        val vis = if (enabled) View.VISIBLE else View.GONE
        if (til.visibility != vis) til.visibility = vis
    }

    private fun updateOpenAiPromptVisibility(enabled: Boolean) {
        val til = findViewById<View>(R.id.tilOpenAiPrompt)
        val vis = if (enabled) View.VISIBLE else View.GONE
        if (til.visibility != vis) til.visibility = vis
    }

    private fun updateVolcStreamOptionsVisibility(enabled: Boolean) {
        val vis = if (enabled) View.VISIBLE else View.GONE
        fun setIfChanged(v: View) { if (v.visibility != vis) v.visibility = vis }
        setIfChanged(findViewById<MaterialSwitch>(R.id.switchVolcVad))
        setIfChanged(findViewById<MaterialSwitch>(R.id.switchVolcNonstream))
        setIfChanged(findViewById<MaterialSwitch>(R.id.switchVolcFirstCharAccel))
        setIfChanged(findViewById<TextView>(R.id.tvVolcLanguageValue))
        setIfChanged(findViewById<View>(R.id.tvVolcLanguageLabel))
    }

    private fun updateSvDownloadUiVisibility() {
        val ready = viewModel.checkSvModelDownloaded(this)
        val btn = findViewById<MaterialButton>(R.id.btnSvDownloadModel)
        val btnClear = findViewById<MaterialButton>(R.id.btnSvClearModel)
        val tv = findViewById<TextView>(R.id.tvSvDownloadStatus)
        btn.visibility = if (ready) View.GONE else View.VISIBLE
        btnClear.visibility = if (ready) View.VISIBLE else View.GONE
        if (ready && tv.text.isNullOrBlank()) {
            tv.text = getString(R.string.sv_download_status_done)
        }
    }

    // ====== Helper Functions ======

    /**
     * Reusable function for showing single-choice dialogs.
     * Reduces repetitive code across vendor language selections.
     */
    private fun showSingleChoiceDialog(
        titleResId: Int,
        items: Array<String>,
        currentIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(titleResId)
            .setSingleChoiceItems(items, currentIndex) { dlg, which ->
                onSelected(which)
                dlg.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /**
     * Extension function to bind EditText changes to Prefs.
     * Simplifies two-way binding setup.
     */
    private fun EditText.bindString(onChange: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onChange(s?.toString() ?: "")
            }
        })
    }

    /**
     * Helper to setup slider with haptic feedback and value change listener.
     */
    private fun setupSlider(slider: Slider, onValueChange: (Float) -> Unit) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                onValueChange(value)
            }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
            override fun onStopTrackingTouch(slider: Slider) = hapticTapIfEnabled(slider)
        })
    }

    private fun hapticTapIfEnabled(view: View?) {
        try {
            if (prefs.micHapticEnabled) {
                view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to perform haptic feedback", e)
        }
    }

    private fun openUrlSafely(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "Failed to open url: $url", e)
            Toast.makeText(this, getString(R.string.error_open_browser), Toast.LENGTH_SHORT).show()
        }
    }


    companion object {
        private const val TAG = "AsrSettingsActivity"
    }
}
