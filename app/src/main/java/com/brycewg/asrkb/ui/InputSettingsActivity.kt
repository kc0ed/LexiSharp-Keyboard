package com.brycewg.asrkb.ui

import android.os.Bundle
import android.view.View
import android.view.HapticFeedbackConstants
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.materialswitch.MaterialSwitch
import android.content.Intent

class InputSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_settings)

        val prefs = Prefs(this)

        val switchTrimTrailingPunct = findViewById<MaterialSwitch>(R.id.switchTrimTrailingPunct)
        val switchAutoSwitchPassword = findViewById<MaterialSwitch>(R.id.switchAutoSwitchPassword)
        val switchMicHaptic = findViewById<MaterialSwitch>(R.id.switchMicHaptic)
        val switchMicTapToggle = findViewById<MaterialSwitch>(R.id.switchMicTapToggle)
        val switchSwapAiEditWithSwitcher = findViewById<MaterialSwitch>(R.id.switchSwapAiEditWithSwitcher)
        val switchFcitx5ReturnOnSwitcher = findViewById<MaterialSwitch>(R.id.switchFcitx5ReturnOnSwitcher)
        val switchHideRecentTasks = findViewById<MaterialSwitch>(R.id.switchHideRecentTasks)
        val tvKeyboardHeight = findViewById<TextView>(R.id.tvKeyboardHeightValue)
        val tvLanguage = findViewById<TextView>(R.id.tvLanguageValue)

        fun applyPrefsToUi() {
            switchTrimTrailingPunct.isChecked = prefs.trimFinalTrailingPunct
            switchAutoSwitchPassword.isChecked = prefs.autoSwitchOnPassword
            switchMicHaptic.isChecked = prefs.micHapticEnabled
            switchMicTapToggle.isChecked = prefs.micTapToggleEnabled
            switchSwapAiEditWithSwitcher.isChecked = prefs.swapAiEditWithImeSwitcher
            switchFcitx5ReturnOnSwitcher.isChecked = prefs.fcitx5ReturnOnImeSwitch
            switchHideRecentTasks.isChecked = prefs.hideRecentTaskCard
        }
        applyPrefsToUi()

        // 键盘高度：三档（点击弹出单选对话框）
        val kbHeightOptions = listOf(
            getString(R.string.keyboard_height_small),
            getString(R.string.keyboard_height_medium),
            getString(R.string.keyboard_height_large)
        )
        fun updateKbHeightSummary() {
            val idx = (prefs.keyboardHeightTier - 1).coerceIn(0, 2)
            tvKeyboardHeight.text = kbHeightOptions[idx]
        }
        updateKbHeightSummary()
        tvKeyboardHeight.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val checked = (prefs.keyboardHeightTier - 1).coerceIn(0, 2)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.label_keyboard_height)
                .setSingleChoiceItems(kbHeightOptions.toTypedArray(), checked) { dlg, which ->
                    val tier = (which + 1).coerceIn(1, 3)
                    if (prefs.keyboardHeightTier != tier) {
                        prefs.keyboardHeightTier = tier
                        updateKbHeightSummary()
                        try { sendBroadcast(Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI)) } catch (_: Throwable) { }
                    }
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        // 应用语言选择（点击弹出单选对话框）
        val languageItems = listOf(
            getString(R.string.lang_follow_system),
            getString(R.string.lang_zh_cn),
            getString(R.string.lang_en)
        )
        fun updateLanguageSummary() {
            val tag = prefs.appLanguageTag
            val idx = when (tag) {
                "zh", "zh-CN", "zh-Hans" -> 1
                "en" -> 2
                else -> 0
            }
            tvLanguage.text = languageItems[idx]
        }
        updateLanguageSummary()
        tvLanguage.setOnClickListener { v ->
            hapticTapIfEnabled(v)
            val curIdx = when (prefs.appLanguageTag) {
                "zh", "zh-CN", "zh-Hans" -> 1
                "en" -> 2
                else -> 0
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.label_language)
                .setSingleChoiceItems(languageItems.toTypedArray(), curIdx) { dlg, which ->
                    val newTag = when (which) {
                        1 -> "zh-CN"
                        2 -> "en"
                        else -> ""
                    }
                    if (newTag != prefs.appLanguageTag) {
                        prefs.appLanguageTag = newTag
                        updateLanguageSummary()
                        val locales = if (newTag.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(newTag)
                        AppCompatDelegate.setApplicationLocales(locales)
                    }
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }

        // 监听与保存
        switchTrimTrailingPunct.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.trimFinalTrailingPunct = isChecked
        }
        switchAutoSwitchPassword.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.autoSwitchOnPassword = isChecked
        }
        switchMicHaptic.setOnCheckedChangeListener { btn, isChecked ->
            prefs.micHapticEnabled = isChecked
            try { btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Throwable) { }
        }
        switchMicTapToggle.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.micTapToggleEnabled = isChecked
        }
        switchSwapAiEditWithSwitcher.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.swapAiEditWithImeSwitcher = isChecked
            try { sendBroadcast(Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI)) } catch (_: Throwable) { }
        }
        switchFcitx5ReturnOnSwitcher.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.fcitx5ReturnOnImeSwitch = isChecked
        }
        switchHideRecentTasks.setOnCheckedChangeListener { btn, isChecked ->
            hapticTapIfEnabled(btn)
            prefs.hideRecentTaskCard = isChecked
            applyExcludeFromRecents(isChecked)
        }

        // 移除 Spinner 即时触发逻辑，改为点击对话框选择

        // 初始应用一次“从最近任务中排除”设置
        applyExcludeFromRecents(prefs.hideRecentTaskCard)
    }

    private fun hapticTapIfEnabled(view: View?) {
        try {
            if (Prefs(this).micHapticEnabled) view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) { }
    }

    private fun applyExcludeFromRecents(enabled: Boolean) {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks?.forEach { it.setExcludeFromRecents(enabled) }
        } catch (_: Throwable) { }
    }
}
