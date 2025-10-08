package com.example.asrkeyboard

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.example.asrkeyboard.ui.FloatingImeSwitcherService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 在可用时为所有Activity启用Material You动态颜色 (Android 12+)
        DynamicColors.applyToActivitiesIfAvailable(this)
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
