package com.example.asrkeyboard.ui

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.example.asrkeyboard.R
import com.example.asrkeyboard.store.Prefs

/**
 * 悬浮球：当当前输入法不是本应用 IME 时显示，点击快速呼出系统输入法选择器，
 * 方便用户切换到 ASR 键盘。
 */
class FloatingImeSwitcherService : Service() {

    companion object {
        const val ACTION_SHOW = "com.example.asrkeyboard.action.FLOATING_SHOW"
        const val ACTION_HIDE = "com.example.asrkeyboard.action.FLOATING_HIDE"
    }

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private val settingsObserver = object : android.database.ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            updateBallVisibility()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        // 监听系统当前输入法变化
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                false,
                settingsObserver
            )
        } catch (_: Throwable) { }
        updateBallVisibility()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> updateBallVisibility()
            ACTION_HIDE -> removeBall()
            else -> updateBallVisibility()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { contentResolver.unregisterContentObserver(settingsObserver) } catch (_: Throwable) { }
        removeBall()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
    }

    private fun updateBallVisibility() {
        val prefs = Prefs(this)
        if (!prefs.floatingSwitcherEnabled || !hasOverlayPermission()) {
            removeBall()
            return
        }
        val isOurIme = isOurImeCurrent()
        if (isOurIme) removeBall() else ensureBall()
    }

    private fun ensureBall() {
        if (ballView != null) return
        val iv = ImageView(this)
        iv.setImageResource(R.drawable.ic_keyboard)
        iv.setColorFilter(ContextCompat.getColor(this, android.R.color.white))
        iv.background = ContextCompat.getDrawable(this, R.drawable.bg_floating_ball)
        val pad = dp(12)
        iv.setPadding(pad, pad, pad, pad)
        iv.contentDescription = getString(R.string.cd_floating_switcher)
        iv.setOnClickListener { onBallClick() }
        attachDrag(iv)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            dp(56), dp(56),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(12)
        params.y = dp(180)

        try {
            windowManager.addView(iv, params)
            ballView = iv
            lp = params
        } catch (_: Throwable) { }
    }

    private fun removeBall() {
        val v = ballView ?: return
        try { windowManager.removeView(v) } catch (_: Throwable) { }
        ballView = null
        lp = null
    }

    private fun onBallClick() {
        // 目标：尽量一键切到我们的键盘；若不可行，唤起系统输入法选择器
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            if (!isOurImeEnabled(imm)) {
                // 未启用 -> 打开系统设置页让用户启用
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
                return
            }
            // 普通应用无法直接设定输入法为本 IME，这里以系统输入法选择器作为快速入口
            imm?.showInputMethodPicker()
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
            } catch (_: Throwable) { }
        }
    }

    private fun isOurImeEnabled(imm: InputMethodManager?): Boolean {
        val list = try { imm?.enabledInputMethodList } catch (_: Throwable) { null }
        return list?.any { it.packageName == packageName } == true
    }

    private fun isOurImeCurrent(): Boolean {
        return try {
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val expectedId = "$packageName/.ime.AsrKeyboardService"
            current == expectedId
        } catch (_: Throwable) {
            false
        }
    }

    private fun attachDrag(target: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val touchSlop = dp(4)

        target.setOnTouchListener { v, e ->
            val p = lp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) moved = true
                    p.x = startX + dx
                    p.y = startY + dy
                    try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) { }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) v.performClick()
                    moved = false
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }
}
