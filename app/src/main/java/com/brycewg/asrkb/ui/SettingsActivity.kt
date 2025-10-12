package com.brycewg.asrkb.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import androidx.appcompat.app.AppCompatActivity
import com.brycewg.asrkb.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptPreset
import com.brycewg.asrkb.asr.AsrVendor
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {

    private var wasAccessibilityEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        wasAccessibilityEnabled = isAccessibilityServiceEnabled()

        findViewById<Button>(R.id.btnEnable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btnChoose).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<Button>(R.id.btnAllPermissions).setOnClickListener {
            requestAllPermissions()
        }

        findViewById<Button>(R.id.btnShowGuide).setOnClickListener {
            val view = LayoutInflater.from(this).inflate(R.layout.dialog_guide, null, false)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.title_quick_guide)
                .setView(view)
                .setPositiveButton(R.string.btn_close, null)
                .show()
        }

        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            checkForUpdates()
        }

        val prefs = Prefs(this)
        val etAppKey = findViewById<EditText>(R.id.etAppKey)
        val etAccessKey = findViewById<EditText>(R.id.etAccessKey)
        val etSfApiKey = findViewById<EditText>(R.id.etSfApiKey)
        val etSfModel = findViewById<EditText>(R.id.etSfModel)
        val etElevenApiKey = findViewById<EditText>(R.id.etElevenApiKey)
        val etElevenModel = findViewById<EditText>(R.id.etElevenModel)
        // DashScope
        val etDashApiKey = findViewById<EditText>(R.id.etDashApiKey)
        val etDashModel = findViewById<EditText>(R.id.etDashModel)
        // OpenAI ASR
        val etOpenAiAsrEndpoint = findViewById<EditText>(R.id.etOpenAiAsrEndpoint)
        val etOpenAiApiKey = findViewById<EditText>(R.id.etOpenAiApiKey)
        val etOpenAiModel = findViewById<EditText>(R.id.etOpenAiModel)
        // Gemini
        val etGeminiApiKey = findViewById<EditText>(R.id.etGeminiApiKey)
        val etGeminiModel = findViewById<EditText>(R.id.etGeminiModel)
        val groupVolc = findViewById<View>(R.id.groupVolc)
        val switchVolcStreaming = findViewById<MaterialSwitch>(R.id.switchVolcStreaming)
        val groupSf = findViewById<View>(R.id.groupSf)
        val groupEleven = findViewById<View>(R.id.groupEleven)
        val groupDash = findViewById<View>(R.id.groupDashScope)
        val groupOpenAi = findViewById<View>(R.id.groupOpenAI)
        val groupGemini = findViewById<View>(R.id.groupGemini)
        // Titles for vendor sections
        val titleVolc = findViewById<View>(R.id.titleVolc)
        val titleSf = findViewById<View>(R.id.titleSf)
        val titleEleven = findViewById<View>(R.id.titleEleven)
        val titleOpenAi = findViewById<View>(R.id.titleOpenAI)
        val titleDash = findViewById<View>(R.id.titleDash)
        val titleGemini = findViewById<View>(R.id.titleGemini)

        val spAsrVendor = findViewById<Spinner>(R.id.spAsrVendor)
        val spLanguage = findViewById<Spinner>(R.id.spLanguage)
        val tvAsrTotalChars = findViewById<TextView>(R.id.tvAsrTotalChars)
        val switchTrimTrailingPunct = findViewById<MaterialSwitch>(R.id.switchTrimTrailingPunct)
        val switchShowImeSwitcher = findViewById<MaterialSwitch>(R.id.switchShowImeSwitcher)
        val switchAutoSwitchPassword = findViewById<MaterialSwitch>(R.id.switchAutoSwitchPassword)
        val switchFloating = findViewById<MaterialSwitch>(R.id.switchFloatingSwitcher)
        val switchFloatingOnlyWhenImeVisible = findViewById<MaterialSwitch>(R.id.switchFloatingOnlyWhenImeVisible)
        val sliderFloatingAlpha = findViewById<Slider>(R.id.sliderFloatingAlpha)
        val sliderFloatingSize = findViewById<Slider>(R.id.sliderFloatingSize)
        val switchFloatingAsr = findViewById<MaterialSwitch>(R.id.switchFloatingAsr)
        val switchMicHaptic = findViewById<MaterialSwitch>(R.id.switchMicHaptic)

        // LLM相关字段
        val spLlmProfiles = findViewById<Spinner>(R.id.spLlmProfiles)
        val etLlmProfileName = findViewById<EditText>(R.id.etLlmProfileName)
        val etLlmEndpoint = findViewById<EditText>(R.id.etLlmEndpoint)
        val etLlmApiKey = findViewById<EditText>(R.id.etLlmApiKey)
        val etLlmModel = findViewById<EditText>(R.id.etLlmModel)
        val etLlmTemperature = findViewById<EditText>(R.id.etLlmTemperature)
        val etLlmPrompt = findViewById<EditText>(R.id.etLlmPrompt)
        val etLlmPromptTitle = findViewById<EditText>(R.id.etLlmPromptTitle)
        val spPromptPresets = findViewById<Spinner>(R.id.spPromptPresets)
        // 自定义标点符号输入
        val etPunct1 = findViewById<EditText>(R.id.etPunct1)
        val etPunct2 = findViewById<EditText>(R.id.etPunct2)
        val etPunct3 = findViewById<EditText>(R.id.etPunct3)
        val etPunct4 = findViewById<EditText>(R.id.etPunct4)

        fun applyPrefsToUi() {
            etAppKey.setText(prefs.appKey)
            etAccessKey.setText(prefs.accessKey)
            etSfApiKey.setText(prefs.sfApiKey)
            etSfModel.setText(prefs.sfModel)
            etElevenApiKey.setText(prefs.elevenApiKey)
            etElevenModel.setText(prefs.elevenModelId)
            etGeminiApiKey.setText(prefs.gemApiKey)
            etGeminiModel.setText(prefs.gemModel)
            etDashApiKey.setText(prefs.dashApiKey)
            etDashModel.setText(prefs.dashModel)
            etOpenAiAsrEndpoint.setText(prefs.oaAsrEndpoint)
            etOpenAiApiKey.setText(prefs.oaAsrApiKey)
            etOpenAiModel.setText(prefs.oaAsrModel)
            switchTrimTrailingPunct.isChecked = prefs.trimFinalTrailingPunct
            switchShowImeSwitcher.isChecked = prefs.showImeSwitcherButton
            switchAutoSwitchPassword.isChecked = prefs.autoSwitchOnPassword
            switchMicHaptic.isChecked = prefs.micHapticEnabled
            switchVolcStreaming.isChecked = prefs.volcStreamingEnabled
            switchFloating.isChecked = prefs.floatingSwitcherEnabled
            switchFloatingOnlyWhenImeVisible.isChecked = prefs.floatingSwitcherOnlyWhenImeVisible
            sliderFloatingAlpha.value = (prefs.floatingSwitcherAlpha * 100f).coerceIn(30f, 100f)
            try { sliderFloatingSize.value = prefs.floatingBallSizeDp.toFloat() } catch (_: Throwable) { }
            switchFloatingAsr.isChecked = prefs.floatingAsrEnabled
            // 互斥兜底：若导入配置造成两者同开，优先显示语音识别，关闭输入法切换
            if (switchFloating.isChecked && switchFloatingAsr.isChecked) {
                switchFloating.isChecked = false
            }
            val activeLlm = prefs.getActiveLlmProvider()
            etLlmProfileName.setText(activeLlm?.name ?: "")
            etLlmEndpoint.setText(activeLlm?.endpoint ?: prefs.llmEndpoint)
            etLlmApiKey.setText(activeLlm?.apiKey ?: prefs.llmApiKey)
            etLlmModel.setText(activeLlm?.model ?: prefs.llmModel)
            etLlmTemperature.setText(((activeLlm?.temperature ?: prefs.llmTemperature)).toString())
            etPunct1.setText(prefs.punct1)
            etPunct2.setText(prefs.punct2)
            etPunct3.setText(prefs.punct3)
            etPunct4.setText(prefs.punct4)
            // 统计：显示历史语音识别总字数
            try {
                tvAsrTotalChars.text = getString(R.string.label_asr_total_chars, prefs.totalAsrChars)
            } catch (_: Throwable) { }
            // 注意：下拉框（Spinner）需在设置 adapter 后再更新 selection，
            // 这里仅更新输入框/开关等即时可用的视图。
        }
        applyPrefsToUi()
        // 使两个悬浮球开关互斥，并在开启时检查悬浮窗权限
        var suppressSwitchChange = false
        // 开启悬浮球时主动引导申请悬浮窗权限 + 互斥
        switchFloating.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchChange) return@setOnCheckedChangeListener
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, getString(R.string.toast_need_overlay_perm), Toast.LENGTH_LONG).show()
                    suppressSwitchChange = true
                    switchFloating.isChecked = false
                    suppressSwitchChange = false
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                        startActivity(intent)
                    } catch (_: Throwable) { }
                    return@setOnCheckedChangeListener
                }
                // 互斥：关闭语音识别悬浮球
                if (switchFloatingAsr.isChecked) {
                    suppressSwitchChange = true
                    switchFloatingAsr.isChecked = false
                    suppressSwitchChange = false
                }
            }
        }

        // 开启悬浮球语音识别时检查悬浮窗权限 + 互斥
        switchFloatingAsr.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchChange) return@setOnCheckedChangeListener
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, getString(R.string.toast_need_overlay_perm), Toast.LENGTH_LONG).show()
                    suppressSwitchChange = true
                    switchFloatingAsr.isChecked = false
                    suppressSwitchChange = false
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                        startActivity(intent)
                    } catch (_: Throwable) { }
                    return@setOnCheckedChangeListener
                }
                // 需要无障碍权限，直接引导前往设置
                if (!isAccessibilityServiceEnabled()) {
                    Toast.makeText(this, getString(R.string.toast_need_accessibility_perm), Toast.LENGTH_LONG).show()
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    } catch (_: Throwable) { }
                }
                // 互斥：关闭切换输入法悬浮球
                if (switchFloating.isChecked) {
                    suppressSwitchChange = true
                    switchFloating.isChecked = false
                    suppressSwitchChange = false
                }
            }
        }
        // 已在 applyPrefsToUi 中统一设置上述字段
        // 提示词预设
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

        // LLM 多配置下拉与初始化
        fun refreshLlmProfilesSpinner() {
            val profiles = prefs.getLlmProviders()
            val titles = profiles.map { it.name }
            spLlmProfiles.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
            val idx = profiles.indexOfFirst { it.id == prefs.activeLlmId }.let { if (it < 0) 0 else it }
            if (idx in titles.indices) spLlmProfiles.setSelection(idx)
        }
        refreshLlmProfilesSpinner()

        spLlmProfiles.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val profiles = prefs.getLlmProviders()
                val p = profiles.getOrNull(position)
                if (p != null) {
                    prefs.activeLlmId = p.id
                    etLlmProfileName.setText(p.name)
                    etLlmEndpoint.setText(p.endpoint)
                    etLlmApiKey.setText(p.apiKey)
                    etLlmModel.setText(p.model)
                    etLlmTemperature.setText(p.temperature.toString())
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ASR供应商选择器设置
        // 统一的供应商顺序，便于 index<->vendor 互转与复用
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
        // 应用语言选择器设置
        val languageItems = listOf(
            getString(R.string.lang_follow_system),
            getString(R.string.lang_zh_cn),
            getString(R.string.lang_en)
        )
        spLanguage.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languageItems)
        val savedTag = prefs.appLanguageTag
        spLanguage.setSelection(
            when (savedTag) {
                "zh", "zh-CN", "zh-Hans" -> 1
                "en" -> 2
                else -> 0
            }
        )
        val languageSpinnerInitialized = true
        fun applyVendorVisibility(v: AsrVendor) {
            // 通过映射统一控制各供应商标题与内容分组可见性
            val groups = mapOf(
                AsrVendor.Volc to listOf(titleVolc, groupVolc),
                AsrVendor.SiliconFlow to listOf(titleSf, groupSf),
                AsrVendor.ElevenLabs to listOf(titleEleven, groupEleven),
                AsrVendor.OpenAI to listOf(titleOpenAi, groupOpenAi),
                AsrVendor.DashScope to listOf(titleDash, groupDash),
                AsrVendor.Gemini to listOf(titleGemini, groupGemini)
            )
            groups.forEach { (vendor, views) ->
                val vis = if (vendor == v) View.VISIBLE else View.GONE
                views.forEach { it.visibility = vis }
            }
        }
        applyVendorVisibility(prefs.asrVendor)

        spAsrVendor.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val vendor = vendorOrder.getOrNull(position) ?: AsrVendor.Volc
                prefs.asrVendor = vendor
                applyVendorVisibility(vendor)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        spLanguage.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!languageSpinnerInitialized) return
                val newTag = when (position) {
                    // 使用通用中文标签，避免区域标签在部分设备上匹配异常
                    1 -> "zh-CN"
                    2 -> "en"
                    else -> "" // 跟随系统
                }
                if (newTag != prefs.appLanguageTag) {
                    prefs.appLanguageTag = newTag
                    val locales = if (newTag.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(newTag)
                    AppCompatDelegate.setApplicationLocales(locales)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        spPromptPresets.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val p = presets.getOrNull(position) ?: return
                prefs.activePromptId = p.id
                etLlmPromptTitle.setText(p.title)
                etLlmPrompt.setText(p.content)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { }
        }

        findViewById<Button>(R.id.btnLlmAddProfile).setOnClickListener {
            val name = etLlmProfileName.text?.toString()?.ifBlank { getString(R.string.untitled_profile) } ?: getString(R.string.untitled_profile)
            val endpoint = etLlmEndpoint.text?.toString()?.ifBlank { Prefs.DEFAULT_LLM_ENDPOINT } ?: Prefs.DEFAULT_LLM_ENDPOINT
            val apiKey = etLlmApiKey.text?.toString() ?: ""
            val model = etLlmModel.text?.toString()?.ifBlank { Prefs.DEFAULT_LLM_MODEL } ?: Prefs.DEFAULT_LLM_MODEL
            val temp = etLlmTemperature.text?.toString()?.toFloatOrNull()?.coerceIn(0f, 2f) ?: Prefs.DEFAULT_LLM_TEMPERATURE
            val id = java.util.UUID.randomUUID().toString()
            val cur = prefs.getLlmProviders().toMutableList()
            cur.add(Prefs.LlmProvider(id, name, endpoint, apiKey, model, temp))
            prefs.setLlmProviders(cur)
            prefs.activeLlmId = id
            refreshLlmProfilesSpinner()
            Toast.makeText(this, getString(R.string.toast_llm_profile_added), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnLlmDeleteProfile).setOnClickListener {
            val cur = prefs.getLlmProviders().toMutableList()
            if (cur.isEmpty()) return@setOnClickListener
            val idx = cur.indexOfFirst { it.id == prefs.activeLlmId }
            if (idx >= 0) {
                cur.removeAt(idx)
                prefs.setLlmProviders(cur)
                // 同步旧字段到新的活动项
                val active = prefs.getActiveLlmProvider()
                if (active != null) {
                    prefs.llmEndpoint = active.endpoint
                    prefs.llmApiKey = active.apiKey
                    prefs.llmModel = active.model
                    prefs.llmTemperature = active.temperature
                }
                applyPrefsToUi()
                refreshLlmProfilesSpinner()
                Toast.makeText(this, getString(R.string.toast_llm_profile_deleted), Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnSaveKeys).setOnClickListener {
            // 保存供应商特定密钥
            prefs.appKey = etAppKey.text?.toString() ?: ""
            prefs.accessKey = etAccessKey.text?.toString() ?: ""
            prefs.sfApiKey = etSfApiKey.text?.toString() ?: ""
            prefs.sfModel = etSfModel.text?.toString()?.ifBlank { Prefs.DEFAULT_SF_MODEL } ?: Prefs.DEFAULT_SF_MODEL
            prefs.elevenApiKey = etElevenApiKey.text?.toString() ?: ""
            prefs.elevenModelId = etElevenModel.text?.toString() ?: ""
            // DashScope 设置
            prefs.dashApiKey = etDashApiKey.text?.toString() ?: ""
            prefs.dashModel = etDashModel.text?.toString()?.ifBlank { Prefs.DEFAULT_DASH_MODEL } ?: Prefs.DEFAULT_DASH_MODEL
            // OpenAI ASR 设置
            prefs.oaAsrEndpoint = etOpenAiAsrEndpoint.text?.toString()?.ifBlank { Prefs.DEFAULT_OA_ASR_ENDPOINT } ?: Prefs.DEFAULT_OA_ASR_ENDPOINT
            prefs.oaAsrApiKey = etOpenAiApiKey.text?.toString() ?: ""
            prefs.oaAsrModel = etOpenAiModel.text?.toString()?.ifBlank { Prefs.DEFAULT_OA_ASR_MODEL } ?: Prefs.DEFAULT_OA_ASR_MODEL
            // Gemini 设置
            prefs.gemApiKey = etGeminiApiKey.text?.toString() ?: ""
            prefs.gemModel = etGeminiModel.text?.toString()?.ifBlank { Prefs.DEFAULT_GEM_MODEL } ?: Prefs.DEFAULT_GEM_MODEL
            // 开关设置
            prefs.trimFinalTrailingPunct = switchTrimTrailingPunct.isChecked
            prefs.showImeSwitcherButton = switchShowImeSwitcher.isChecked
            prefs.autoSwitchOnPassword = switchAutoSwitchPassword.isChecked
            prefs.micHapticEnabled = switchMicHaptic.isChecked
            // 悬浮球透明度（百分比转 0-1）
            prefs.floatingSwitcherAlpha = (sliderFloatingAlpha.value / 100f).coerceIn(0.2f, 1.0f)
            // 悬浮球大小（dp）
            prefs.floatingBallSizeDp = sliderFloatingSize.value.toInt().coerceIn(28, 96)

            // 悬浮球语音识别开关
            val newFloatingAsr = switchFloatingAsr.isChecked
            val wasFloatingAsr = prefs.floatingAsrEnabled

            // 悬浮球切换输入法开关
            var newFloating = switchFloating.isChecked
            val wasFloating = prefs.floatingSwitcherEnabled
            
            // 互斥：若两者均为开，则优先保留最后操作后仍为开的那个（UI层已互斥，这里兜底）
            if (newFloating && newFloatingAsr) {
                // 默认优先语音识别悬浮球
                newFloating = false
                switchFloating.isChecked = false
            }

            prefs.floatingAsrEnabled = newFloatingAsr
            prefs.floatingSwitcherEnabled = newFloating
            prefs.floatingSwitcherOnlyWhenImeVisible = switchFloatingOnlyWhenImeVisible.isChecked
            // 若用户开启“仅在键盘显示时显示悬浮球”，引导打开无障碍服务用于场景判定
            if (prefs.floatingSwitcherOnlyWhenImeVisible && !isAccessibilityServiceEnabled()) {
                Toast.makeText(this, getString(R.string.toast_need_accessibility_perm), Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                } catch (_: Throwable) { }
            }
            if (newFloating != wasFloating) {
                if (newFloating) {
                    // 检查悬浮窗权限
                    if (true && !Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, getString(R.string.toast_need_overlay_perm), Toast.LENGTH_LONG).show()
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:$packageName".toUri())
                            startActivity(intent)
                        } catch (_: Throwable) { }
                    }
                    try {
                        val intent = Intent(this, FloatingImeSwitcherService::class.java).apply { action = FloatingImeSwitcherService.ACTION_SHOW }
                        startService(intent)
                    } catch (_: Throwable) { }
                } else {
                    try {
                        val intent = Intent(this, FloatingImeSwitcherService::class.java).apply { action = FloatingImeSwitcherService.ACTION_HIDE }
                        startService(intent)
                    } catch (_: Throwable) { }
                }
            }
            // 通知服务刷新透明度
            if (prefs.floatingSwitcherEnabled) {
                try {
                    val intent = Intent(this, FloatingImeSwitcherService::class.java).apply { action = FloatingImeSwitcherService.ACTION_SHOW }
                    startService(intent)
                } catch (_: Throwable) { }
            }

            // 悬浮球语音识别服务管理
            if (newFloatingAsr != wasFloatingAsr) {
                if (newFloatingAsr) {
                    // 检查悬浮窗权限
                    if (!Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, getString(R.string.toast_need_overlay_perm), Toast.LENGTH_LONG).show()
                    } else {
                        try {
                            val intent = Intent(this, FloatingAsrService::class.java).apply { action = FloatingAsrService.ACTION_SHOW }
                            startService(intent)
                        } catch (_: Throwable) { }
                    }

                    // 需要无障碍权限，直接引导前往设置
                    if (!isAccessibilityServiceEnabled()) {
                        Toast.makeText(this, getString(R.string.toast_need_accessibility_perm), Toast.LENGTH_LONG).show()
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            startActivity(intent)
                        } catch (_: Throwable) { }
                    }
                } else {
                    try {
                        val intent = Intent(this, FloatingAsrService::class.java).apply { action = FloatingAsrService.ACTION_HIDE }
                        startService(intent)
                        stopService(Intent(this, FloatingAsrService::class.java))
                    } catch (_: Throwable) { }
                }
            }
            // 刷新悬浮球语音识别服务(仅需要悬浮窗权限)
            if (prefs.floatingAsrEnabled && Settings.canDrawOverlays(this)) {
                try {
                    val intent = Intent(this, FloatingAsrService::class.java).apply { action = FloatingAsrService.ACTION_SHOW }
                    startService(intent)
                } catch (_: Throwable) { }
            }
            // 大语言模型相关设置（保存为当前选中配置，同时同步旧字段）
            val endpoint = etLlmEndpoint.text?.toString()?.ifBlank { Prefs.DEFAULT_LLM_ENDPOINT } ?: Prefs.DEFAULT_LLM_ENDPOINT
            val apiKey = etLlmApiKey.text?.toString() ?: ""
            val model = etLlmModel.text?.toString()?.ifBlank { Prefs.DEFAULT_LLM_MODEL } ?: Prefs.DEFAULT_LLM_MODEL
            val tempVal = etLlmTemperature.text?.toString()?.toFloatOrNull()?.coerceIn(0f, 2f) ?: Prefs.DEFAULT_LLM_TEMPERATURE
            val name = etLlmProfileName.text?.toString()?.ifBlank { getString(R.string.untitled_profile) } ?: getString(R.string.untitled_profile)
            val curProfiles = prefs.getLlmProviders().toMutableList()
            val idxProfile = curProfiles.indexOfFirst { it.id == prefs.activeLlmId }
            if (idxProfile >= 0) {
                val id = curProfiles[idxProfile].id
                curProfiles[idxProfile] = Prefs.LlmProvider(id, name, endpoint, apiKey, model, tempVal)
            } else {
                val id = java.util.UUID.randomUUID().toString()
                curProfiles.add(Prefs.LlmProvider(id, name, endpoint, apiKey, model, tempVal))
                prefs.activeLlmId = id
            }
            prefs.setLlmProviders(curProfiles)
            // 同步旧字段用于兼容
            prefs.llmEndpoint = endpoint
            prefs.llmApiKey = apiKey
            prefs.llmModel = model
            prefs.llmTemperature = tempVal
            // 自定义标点符号按钮
            prefs.punct1 = etPunct1.text?.toString() ?: Prefs.DEFAULT_PUNCT_1
            prefs.punct2 = etPunct2.text?.toString() ?: Prefs.DEFAULT_PUNCT_2
            prefs.punct3 = etPunct3.text?.toString() ?: Prefs.DEFAULT_PUNCT_3
            prefs.punct4 = etPunct4.text?.toString() ?: Prefs.DEFAULT_PUNCT_4
            // 更新当前预设标题/内容并设为活动状态
            val newTitle = etLlmPromptTitle.text?.toString()?.ifBlank { getString(R.string.untitled_preset) } ?: getString(R.string.untitled_preset)
            val newContent = etLlmPrompt.text?.toString() ?: Prefs.DEFAULT_LLM_PROMPT
            val currentIdx = presets.indexOfFirst { it.id == prefs.activePromptId }
            val updated = if (currentIdx >= 0) presets.toMutableList() else presets
            if (currentIdx >= 0) {
                updated[currentIdx] = updated[currentIdx].copy(title = newTitle, content = newContent)
                prefs.setPromptPresets(updated)
                prefs.activePromptId = updated[currentIdx].id
            } else {
                // 没有活动预设？创建一个
                val created = PromptPreset(java.util.UUID.randomUUID().toString(), newTitle, newContent)
                val newList = presets.toMutableList().apply { add(created) }
                prefs.setPromptPresets(newList)
                prefs.activePromptId = created.id
            }
            refreshSpinnerSelection()
            try { (spLlmProfiles.adapter as? ArrayAdapter<*>)?.clear() } catch (_: Throwable) {}
            refreshLlmProfilesSpinner()
            // 保存火山引擎流式开关
            prefs.volcStreamingEnabled = switchVolcStreaming.isChecked
            Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnAddPromptPreset).setOnClickListener {
            val title = etLlmPromptTitle.text?.toString()?.ifBlank { getString(R.string.untitled_preset) } ?: getString(R.string.untitled_preset)
            val content = etLlmPrompt.text?.toString() ?: Prefs.DEFAULT_LLM_PROMPT
            val created = PromptPreset(java.util.UUID.randomUUID().toString(), title, content)
            val newList = prefs.getPromptPresets().toMutableList().apply { add(created) }
            prefs.setPromptPresets(newList)
            prefs.activePromptId = created.id
            refreshSpinnerSelection()
            Toast.makeText(this, getString(R.string.toast_preset_added), Toast.LENGTH_SHORT).show()
        }

        // 设置导入/导出
        val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(Prefs(this).exportJsonString().toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    val name = uri.lastPathSegment ?: "settings.json"
                    Toast.makeText(this, getString(R.string.toast_export_success, name), Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                    Toast.makeText(this, getString(R.string.toast_export_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    val ok = Prefs(this).importJsonString(json)
                    if (ok) {
                        // 重新从 Prefs 读取并刷新界面与下拉框，确保导入立即生效
                        applyPrefsToUi()
                        // 刷新提示词预设
                        refreshSpinnerSelection()
                        // 刷新 LLM 配置列表
                        try { (spLlmProfiles.adapter as? ArrayAdapter<*>)?.clear() } catch (_: Throwable) {}
                        // 函数在上方定义
                        run {
                            val profiles = prefs.getLlmProviders()
                            val titles = profiles.map { it.name }
                            spLlmProfiles.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
                            val idx = profiles.indexOfFirst { it.id == prefs.activeLlmId }.let { if (it < 0) 0 else it }
                            if (idx in titles.indices) spLlmProfiles.setSelection(idx)
                        }
                        // 同步 ASR 供应商选择与可见性
                        spAsrVendor.setSelection(vendorOrder.indexOf(prefs.asrVendor).coerceAtLeast(0))
                        // 同步语言选择（将触发 onItemSelected 从而应用语言）
                        val tag = prefs.appLanguageTag
                        spLanguage.setSelection(
                            when (tag) {
                                "zh", "zh-CN", "zh-Hans" -> 1
                                "en" -> 2
                                else -> 0
                            }
                        )
                        Toast.makeText(this, getString(R.string.toast_import_success), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.toast_import_failed), Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Throwable) {
                    Toast.makeText(this, getString(R.string.toast_import_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        findViewById<Button>(R.id.btnExportSettings).setOnClickListener {
            val fileName = "asr_keyboard_settings_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault()).format(java.util.Date()) + ".json"
            exportLauncher.launch(fileName)
        }
        findViewById<Button>(R.id.btnImportSettings).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain"))
        }
    }


    override fun onResume() {
        super.onResume()

        // 检查无障碍服务是否刚刚被启用
        Prefs(this)
        val isNowEnabled = isAccessibilityServiceEnabled()

        if (!wasAccessibilityEnabled && isNowEnabled) {
            // 无障碍服务刚刚被启用
            Log.d("SettingsActivity", "Accessibility service just enabled")
            Toast.makeText(this, "无障碍服务已启用,现在可以自动插入文本了", Toast.LENGTH_SHORT).show()
        }

        wasAccessibilityEnabled = isNowEnabled
    }

    private fun requestAllPermissions() {
        val prefs = Prefs(this)

        // 1. 麦克风权限
        startActivity(Intent(this, PermissionActivity::class.java))

        // 2. 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.toast_need_overlay_perm), Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                startActivity(intent)
            } catch (_: Throwable) { }
        }

        // 3. 通知权限 (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        // 4. 如果启用了悬浮球语音识别,还需要无障碍权限
        if (prefs.floatingAsrEnabled && !isAccessibilityServiceEnabled()) {
            Toast.makeText(this, getString(R.string.toast_need_accessibility_perm), Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } catch (_: Throwable) { }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/com.brycewg.asrkb.ui.AsrAccessibilityService"
        val enabledServicesSetting = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: Throwable) {
            Log.e("SettingsActivity", "Failed to get accessibility services")
            return false
        }
        Log.d("SettingsActivity", "Expected: $expectedComponentName")
        Log.d("SettingsActivity", "Enabled services: $enabledServicesSetting")
        val result = enabledServicesSetting?.contains(expectedComponentName) == true
        Log.d("SettingsActivity", "Accessibility service enabled: $result")
        return result
    }

    private fun checkForUpdates() {
        // 显示检查中的提示
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.update_checking)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    checkGitHubRelease()
                }
                progressDialog.dismiss()

                if (result.hasUpdate) {
                    showUpdateDialog(result.currentVersion, result.latestVersion, result.downloadUrl, result.updateTime, result.releaseNotes)
                } else {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.update_no_update),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Log.e("SettingsActivity", "Update check failed", e)

                // 显示错误对话框，提供手动查看选项
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle(R.string.update_check_failed)
                    .setMessage(e.message ?: "Unknown error")
                    .setPositiveButton(R.string.btn_manual_check) { _, _ ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BryceWG/LexiSharp-Keyboard/releases"))
                            startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(this@SettingsActivity, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        }
    }

    private data class UpdateCheckResult(
        val hasUpdate: Boolean,
        val currentVersion: String,
        val latestVersion: String,
        val downloadUrl: String,
        val updateTime: String? = null,
        val releaseNotes: String? = null
    )

    private fun checkGitHubRelease(): UpdateCheckResult {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        // 从 jsDelivr CDN 读取版本文件（无限制，国内快）
        val jsdelivrUrl = "https://cdn.jsdelivr.net/gh/BryceWG/LexiSharp-Keyboard@main/version.json"

        val request = Request.Builder()
            .url(jsdelivrUrl)
            .addHeader("User-Agent", "LexiSharp-Android")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}")
            }

            val body = response.body?.string() ?: throw Exception("Empty response")
            val json = JSONObject(body)

            val latestVersion = json.optString("version", "").removePrefix("v")
            if (latestVersion.isEmpty()) {
                throw Exception("Invalid version format")
            }

            val downloadUrl = json.optString("download_url", "https://github.com/BryceWG/LexiSharp-Keyboard/releases")
            val updateTime = json.optString("update_time", null)
            val releaseNotes = json.optString("release_notes", null)

            val currentVersion = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }

            val hasUpdate = compareVersions(latestVersion, currentVersion) > 0
            return UpdateCheckResult(hasUpdate, currentVersion, latestVersion, downloadUrl, updateTime, releaseNotes)
        }
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val v1Parts = version1.split(".").mapNotNull { it.toIntOrNull() }
        val v2Parts = version2.split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(v1Parts.size, v2Parts.size)
        for (i in 0 until maxLength) {
            val v1 = v1Parts.getOrNull(i) ?: 0
            val v2 = v2Parts.getOrNull(i) ?: 0
            if (v1 != v2) {
                return v1.compareTo(v2)
            }
        }
        return 0
    }

    private fun showUpdateDialog(
        currentVersion: String,
        latestVersion: String,
        downloadUrl: String,
        updateTime: String? = null,
        releaseNotes: String? = null
    ) {
        // 构建消息内容
        val messageBuilder = StringBuilder()
        messageBuilder.append(getString(R.string.update_dialog_message, currentVersion, latestVersion))

        // 添加更新时间（如果有）
        if (!updateTime.isNullOrEmpty()) {
            messageBuilder.append("\n\n")
            // 尝试格式化时间戳
            val formattedTime = try {
                // 解析 ISO 8601 格式并转换为本地时间
                val utcFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
                utcFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val date = utcFormat.parse(updateTime)

                val localFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                localFormat.format(date)
            } catch (e: Exception) {
                updateTime // 如果解析失败，直接显示原始时间
            }
            messageBuilder.append("更新时间：$formattedTime")
        }

        // 添加发布说明（如果有）
        if (!releaseNotes.isNullOrEmpty() && releaseNotes != "版本 $latestVersion 已发布") {
            messageBuilder.append("\n\n")
            messageBuilder.append("更新说明：\n$releaseNotes")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(messageBuilder.toString())
            .setPositiveButton(R.string.btn_download) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }
}
