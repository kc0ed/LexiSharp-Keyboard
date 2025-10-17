package com.brycewg.asrkb.ui

import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
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
                // 缩短延迟时间，立即关闭透明 Activity 避免闪烁
                handler.postDelayed({
                    finish()
                    if (Build.VERSION.SDK_INT >= 34) {
                        // 关闭过渡动画（API 34+）
                        overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
                    } else {
                        overridePendingTransition(0, 0)
                    }
                }, 100)
            }
        }
    }
}
