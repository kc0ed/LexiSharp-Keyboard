package com.brycewg.asrkb

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.google.android.material.color.DynamicColors
import com.brycewg.asrkb.store.Prefs
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.brycewg.asrkb.ui.FloatingImeSwitcherService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 在可用时为所有Activity启用Material You动态颜色 (Android 12+)
        DynamicColors.applyToActivitiesIfAvailable(this)
        // 应用应用内语言设置（空表示跟随系统）
        try {
            val prefs = Prefs(this)
            val tag = prefs.appLanguageTag
            // 兼容旧版本存储的 zh-CN，统一归一化为 zh
            val normalized = when (tag.lowercase()) {
                "zh-cn" -> "zh"
                else -> tag
            }
            if (normalized != tag) prefs.appLanguageTag = normalized
            val locales = if (normalized.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(normalized)
            AppCompatDelegate.setApplicationLocales(locales)
        } catch (_: Throwable) { }

        // 若用户在设置中启用了悬浮球且已授予悬浮窗权限，则启动悬浮球服务
        try {
            val prefs = Prefs(this)
            val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
            if (prefs.floatingSwitcherEnabled && canOverlay) {
                val intent = Intent(this, FloatingImeSwitcherService::class.java).apply {
                    action = FloatingImeSwitcherService.ACTION_SHOW
                }
                startService(intent)
            }
        } catch (_: Throwable) { }
    }
}
