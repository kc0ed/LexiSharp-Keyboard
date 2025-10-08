package com.brycewg.asrkb.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity

/**
 * 透明过渡页：置前后立刻唤起系统输入法选择器，然后自行 finish。
 * 目的：避免从后台 Service 直接调用 showInputMethodPicker() 被系统忽略的问题。
 */
class ImePickerActivity : ComponentActivity() {
    private var launched = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || launched) return
        launched = true
        handler.post {
            try {
                val imm = getSystemService(InputMethodManager::class.java)
                imm?.showInputMethodPicker()
            } catch (_: Throwable) {
                // 不再兜底跳系统设置，避免多次界面跳转造成割裂
            } finally {
                // 延长一点时间，减少系统可能再启动设置页时的界面闪烁
                handler.postDelayed({ finish() }, 500)
            }
        }
    }
}
