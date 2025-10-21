package com.brycewg.asrkb.ui.floating

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floating.RadialMenuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 悬浮球：当当前输入法不是本应用 IME 时显示，点击快速呼出系统输入法选择器，
 * 方便用户切换到 ASR 键盘。
 */
class FloatingImeSwitcherService : BaseFloatingService() {

    companion object {
        private const val TAG = "FloatingImeSwitcher"
        const val ACTION_SHOW = "com.brycewg.asrkb.action.FLOATING_SHOW"
        const val ACTION_HIDE = "com.brycewg.asrkb.action.FLOATING_HIDE"
        const val ACTION_HINT_IME_VISIBLE = "com.brycewg.asrkb.action.IME_VISIBLE"
        const val ACTION_HINT_IME_HIDDEN = "com.brycewg.asrkb.action.IME_HIDDEN"
    }

    private var imeVisible: Boolean = false
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var radialMenuHelper: RadialMenuHelper

    private val settingsObserver = object : android.database.ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            updateBallVisibility()
        }
    }

    private val hintReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_HINT_IME_VISIBLE -> {
                    imeVisible = true
                    updateBallVisibility()
                }
                ACTION_HINT_IME_HIDDEN -> {
                    imeVisible = false
                    updateBallVisibility()
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    override fun onCreate() {
        super.onCreate()

        // 初始化轮盘菜单辅助类
        radialMenuHelper = RadialMenuHelper(
            context = this,
            windowManager = windowManager,
            prefs = prefs,
            serviceScope = serviceScope,
            getBallCenter = { getBallCenterSnapshot() },
            isBallOnLeft = { isBallOnLeft() },
            currentUiAlpha = { currentUiAlpha() },
            dp = { dp(it) },
            hapticTap = { hapticTap(it) }
        )

        // 监听系统当前输入法变化
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                false,
                settingsObserver
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register settings observer", e)
        }

        // 监听无障碍服务发来的 IME 显示/隐藏提示
        registerImeVisibilityReceiver()
        updateBallVisibility()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> updateBallVisibility()
            ACTION_HIDE -> removeBall()
            ACTION_HINT_IME_VISIBLE -> {
                imeVisible = true
                updateBallVisibility()
            }
            ACTION_HINT_IME_HIDDEN -> {
                imeVisible = false
                updateBallVisibility()
            }
            else -> updateBallVisibility()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            contentResolver.unregisterContentObserver(settingsObserver)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unregister settings observer", e)
        }
        try {
            unregisterReceiver(hintReceiver)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unregister hint receiver", e)
        }
        try {
            serviceScope.cancel()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to cancel service scope", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== BaseFloatingService 抽象方法实现 ====================

    override fun createBallView(): View {
        val iv = ImageView(this)
        iv.setImageResource(R.drawable.logo)
        iv.clearColorFilter()
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        iv.background = ContextCompat.getDrawable(this, R.drawable.bg_floating_ball)
        val pad = dp(6)
        iv.setPadding(pad, pad, pad, pad)
        iv.contentDescription = getString(R.string.cd_floating_switcher)
        iv.setOnClickListener {
            hapticTap(iv)
            onBallClick()
        }
        return iv
    }

    override fun getDefaultBallSizeDp(): Int = 28

    override fun onBallClick() {
        // 若处于移动模式：点按退出移动模式，不触发其他动作
        if (moveModeEnabled) {
            exitMoveMode()
            return
        }

        // 目标：尽量一键切到我们的键盘；若不可行，唤起系统输入法选择器
        invokeImePickerOrSettings()
    }

    override fun onShowRadialMenu() {
        showRadialMenu()
    }

    override fun onTouchEnd() {
        updateBallVisibility()
    }

    // ==================== 悬浮球可见性管理 ====================

    private fun updateBallVisibility() {
        val forceVisible = (radialMenuView != null || vendorMenuView != null || moveModeEnabled || touchActiveGuard)

        if (!prefs.floatingSwitcherEnabled || !hasOverlayPermission()) {
            removeBall()
            return
        }

        // 如果启用了悬浮球语音识别模式，则不显示切换输入法的悬浮球
        if (prefs.floatingAsrEnabled) {
            removeBall()
            return
        }

        // 交互时强制显示
        if (forceVisible) {
            ensureBall()
            applyBallSize()
            applyBallAlpha()
            return
        }

        // 开启"仅在输入法面板显示时显示"，但当前未检测到输入场景 -> 隐藏
        if (prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible) {
            removeBall()
            return
        }

        if (isOurImeCurrent()) {
            removeBall()
            return
        }

        ensureBall()
        applyBallSize()
        applyBallAlpha()
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    // ==================== 输入法相关操作 ====================

    private fun invokeImePickerOrSettings() {
        radialMenuHelper.invokeImePicker()
    }

    private fun isOurImeCurrent(): Boolean {
        return try {
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val expectedId = "$packageName/.ime.AsrKeyboardService"
            current == expectedId
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to check if our IME is current", e)
            false
        }
    }

    // ==================== 广播接收器注册 ====================

    private fun registerImeVisibilityReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_HINT_IME_VISIBLE)
                addAction(ACTION_HINT_IME_HIDDEN)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(hintReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(hintReceiver, filter)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register IME visibility receiver", e)
        }
    }

    // ==================== 轮盘菜单 ====================

    private fun showRadialMenu() {
        if (radialMenuView != null) return

        val items = radialMenuHelper.createStandardMenuItems(
            onPromptPresetClick = { onPickPromptPreset() },
            onAsrVendorClick = { onPickAsrVendor() },
            onImePickerClick = { onInvokeImePickerFromMenu() },
            onMoveModeClick = { onEnableMoveMode() },
            onPostprocToggleClick = { onTogglePostproc() },
            onClipboardUploadClick = { onUploadClipboard() },
            onClipboardPullClick = { onPullClipboard() }
        )

        val menuView = radialMenuHelper.showRadialMenu(items) {
            radialMenuView = null
            updateBallVisibility()
        }
        radialMenuView = menuView
        updateBallVisibility()
    }

    private fun onPickPromptPreset() {
        touchActiveGuard = true
        val subView = radialMenuHelper.showPromptPresetMenu(
            onPresetSelected = { id, title ->
                radialMenuHelper.handlePromptPresetChange(id, title)
            },
            onMenuClosed = {
                vendorMenuView = null
                touchActiveGuard = false
                updateBallVisibility()
            }
        )
        vendorMenuView = subView
    }

    private fun onPickAsrVendor() {
        touchActiveGuard = true
        val currentVendor = try {
            prefs.asrVendor
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get current ASR vendor", e)
            AsrVendor.Volc
        }

        val subView = radialMenuHelper.showAsrVendorMenu(
            onVendorSelected = { newVendor ->
                if (newVendor != currentVendor) {
                    radialMenuHelper.handleAsrVendorChange(
                        newVendor,
                        currentVendor,
                        getVendorName(newVendor)
                    )
                }
            },
            onMenuClosed = {
                vendorMenuView = null
                touchActiveGuard = false
                updateBallVisibility()
            }
        )
        vendorMenuView = subView
    }

    private fun onInvokeImePickerFromMenu() {
        radialMenuHelper.hideSubMenu()
        invokeImePickerOrSettings()
    }

    private fun onEnableMoveMode() {
        moveModeEnabled = true
        // 进入移动模式时应关闭所有菜单，避免遮挡导致无法拖动/误触
        radialMenuHelper.hideRadialMenu()
        radialMenuHelper.hideSubMenu()
        radialMenuView = null
        vendorMenuView = null
        try {
            Toast.makeText(this, getString(R.string.toast_move_mode_on), Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show move mode toast", e)
        }
        updateBallVisibility()
    }

    private fun onTogglePostproc() {
        radialMenuHelper.handlePostprocToggle()
    }

    private fun onUploadClipboard() {
        radialMenuHelper.handleClipboardUpload { ok ->
            try {
                Toast.makeText(
                    this,
                    getString(if (ok) R.string.sc_status_uploaded else R.string.sc_test_failed),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to show clipboard upload result", e)
            }
        }
    }

    private fun onPullClipboard() {
        radialMenuHelper.handleClipboardPull { ok ->
            try {
                Toast.makeText(
                    this,
                    getString(if (ok) R.string.sc_test_success else R.string.sc_test_failed),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to show clipboard pull result", e)
            }
        }
    }

    // ==================== 移动模式 ====================

    private fun exitMoveMode() {
        moveModeEnabled = false
        ballView?.let {
            try {
                animateSnapToEdge(it)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to animate snap to edge", e)
                snapToEdge(it)
            }
        }
        try {
            persistBallPosition()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist ball position", e)
        }
        radialMenuHelper.hideRadialMenu()
        radialMenuHelper.hideSubMenu()
        radialMenuView = null
        vendorMenuView = null
    }

    // ==================== 辅助方法 ====================

    private fun getVendorName(vendor: AsrVendor): String {
        return when (vendor) {
            AsrVendor.Volc -> getString(R.string.vendor_volc)
            AsrVendor.SiliconFlow -> getString(R.string.vendor_sf)
            AsrVendor.ElevenLabs -> getString(R.string.vendor_eleven)
            AsrVendor.OpenAI -> getString(R.string.vendor_openai)
            AsrVendor.DashScope -> getString(R.string.vendor_dashscope)
            AsrVendor.Gemini -> getString(R.string.vendor_gemini)
            AsrVendor.Soniox -> getString(R.string.vendor_soniox)
            AsrVendor.SenseVoice -> getString(R.string.vendor_sensevoice)
        }
    }
}
