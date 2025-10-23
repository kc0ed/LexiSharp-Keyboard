package com.brycewg.asrkb.ui.settings.floating

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floating.FloatingServiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FloatingSettingsActivity 的 ViewModel
 * 管理悬浮球设置的状态和业务逻辑
 */
class FloatingSettingsViewModel : ViewModel() {

    companion object {
        private const val TAG = "FloatingSettingsVM"
    }

    // UI 状态
    private val _asrEnabled = MutableStateFlow(false)
    val asrEnabled: StateFlow<Boolean> = _asrEnabled.asStateFlow()

    private val _onlyWhenImeVisible = MutableStateFlow(true)
    val onlyWhenImeVisible: StateFlow<Boolean> = _onlyWhenImeVisible.asStateFlow()

    private val _alpha = MutableStateFlow(1.0f)
    val alpha: StateFlow<Float> = _alpha.asStateFlow()

    private val _sizeDp = MutableStateFlow(44)
    val sizeDp: StateFlow<Int> = _sizeDp.asStateFlow()

    private val _writeCompatEnabled = MutableStateFlow(true)
    val writeCompatEnabled: StateFlow<Boolean> = _writeCompatEnabled.asStateFlow()

    private val _writePasteEnabled = MutableStateFlow(false)
    val writePasteEnabled: StateFlow<Boolean> = _writePasteEnabled.asStateFlow()

    private val _imeVisibilityCompatEnabled = MutableStateFlow(false)
    val imeVisibilityCompatEnabled: StateFlow<Boolean> = _imeVisibilityCompatEnabled.asStateFlow()

    /**
     * 初始化状态，从 Prefs 加载
     */
    fun initialize(context: Context) {
        try {
            val prefs = Prefs(context)
            _asrEnabled.value = prefs.floatingAsrEnabled
            _onlyWhenImeVisible.value = prefs.floatingSwitcherOnlyWhenImeVisible
            _alpha.value = (prefs.floatingSwitcherAlpha * 100f).coerceIn(30f, 100f)
            _sizeDp.value = prefs.floatingBallSizeDp
            _writeCompatEnabled.value = prefs.floatingWriteTextCompatEnabled
            _writePasteEnabled.value = prefs.floatingWriteTextPasteEnabled
            _imeVisibilityCompatEnabled.value = prefs.floatingImeVisibilityCompatEnabled
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize state", e)
        }
    }

    /**
     * 处理语音识别悬浮球开关变化
     * @return 是否需要请求权限（overlay 或 accessibility）
     */
    fun handleAsrToggle(
        context: Context,
        enabled: Boolean,
        serviceManager: FloatingServiceManager
    ): PermissionRequest? {
        try {
            val prefs = Prefs(context)

            // 检查悬浮窗权限
            if (enabled && !Settings.canDrawOverlays(context)) {
                Log.w(TAG, "ASR toggle: missing overlay permission")
                _asrEnabled.value = false
                prefs.floatingAsrEnabled = false
                return PermissionRequest.OVERLAY
            }

            // 检查无障碍权限
            if (enabled && !isAccessibilityServiceEnabled(context)) {
                Log.w(TAG, "ASR toggle: missing accessibility permission")
                return PermissionRequest.ACCESSIBILITY
            }

            // 更新状态
            _asrEnabled.value = enabled
            prefs.floatingAsrEnabled = enabled

            // 启动或停止服务
            if (enabled) {
                serviceManager.showAsrService()
            } else {
                serviceManager.hideAsrService()
            }

            Log.d(TAG, "ASR toggled: $enabled")
            return null
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to handle ASR toggle", e)
            return null
        }
    }

    /**
     * 处理"仅在键盘显示时显示悬浮球"开关变化
     */
    fun handleOnlyWhenImeVisibleToggle(
        context: Context,
        enabled: Boolean,
        serviceManager: FloatingServiceManager
    ): PermissionRequest? {
        try {
            val prefs = Prefs(context)
            _onlyWhenImeVisible.value = enabled
            prefs.floatingSwitcherOnlyWhenImeVisible = enabled

            // 如果启用且缺少无障碍权限，需要提示
            if (enabled && !isAccessibilityServiceEnabled(context)) {
                Log.w(TAG, "OnlyWhenImeVisible enabled but accessibility not granted")
                return PermissionRequest.ACCESSIBILITY
            }

            // 刷新服务显示状态
            serviceManager.refreshAsrService(_asrEnabled.value)

            Log.d(TAG, "OnlyWhenImeVisible toggled: $enabled")
            return null
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to handle OnlyWhenImeVisible toggle", e)
            return null
        }
    }

    /**
     * 处理键盘可见性兼容模式开关变化
     */
    fun handleImeVisibilityCompatToggle(
        context: Context,
        enabled: Boolean
    ): PermissionRequest? {
        try {
            val prefs = Prefs(context)
            _imeVisibilityCompatEnabled.value = enabled
            prefs.floatingImeVisibilityCompatEnabled = enabled

            // 如果同时启用"仅在键盘显示时显示"且缺少无障碍权限，需要提示
            if (_onlyWhenImeVisible.value && enabled && !isAccessibilityServiceEnabled(context)) {
                Log.w(TAG, "ImeVisibilityCompat enabled but accessibility not granted")
                return PermissionRequest.ACCESSIBILITY
            }

            Log.d(TAG, "ImeVisibilityCompat toggled: $enabled")
            return null
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to handle ImeVisibilityCompat toggle", e)
            return null
        }
    }

    /**
     * 更新悬浮球透明度
     */
    fun updateAlpha(
        context: Context,
        alphaPercent: Float,
        serviceManager: FloatingServiceManager
    ) {
        try {
            val prefs = Prefs(context)
            _alpha.value = alphaPercent
            prefs.floatingSwitcherAlpha = (alphaPercent / 100f).coerceIn(0.2f, 1.0f)

            // 刷新服务以应用新透明度
            serviceManager.refreshAsrService(_asrEnabled.value)

            Log.d(TAG, "Alpha updated: $alphaPercent")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update alpha", e)
        }
    }

    /**
     * 更新悬浮球大小
     */
    fun updateSize(
        context: Context,
        sizeDp: Int,
        serviceManager: FloatingServiceManager
    ) {
        try {
            val prefs = Prefs(context)
            _sizeDp.value = sizeDp
            prefs.floatingBallSizeDp = sizeDp.coerceIn(28, 96)

            // 刷新语音识别悬浮球（当前仅保留语音悬浮球）
            if (_asrEnabled.value) {
                serviceManager.showAsrService()
            }

            Log.d(TAG, "Size updated: $sizeDp")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update size", e)
        }
    }

    /**
     * 处理写入兼容模式开关变化
     */
    fun handleWriteCompatToggle(context: Context, enabled: Boolean) {
        try {
            val prefs = Prefs(context)
            _writeCompatEnabled.value = enabled
            prefs.floatingWriteTextCompatEnabled = enabled
            Log.d(TAG, "WriteCompat toggled: $enabled")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to handle WriteCompat toggle", e)
        }
    }

    /**
     * 处理写入粘贴方案开关变化
     */
    fun handleWritePasteToggle(context: Context, enabled: Boolean) {
        try {
            val prefs = Prefs(context)
            _writePasteEnabled.value = enabled
            prefs.floatingWriteTextPasteEnabled = enabled
            Log.d(TAG, "WritePaste toggled: $enabled")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to handle WritePaste toggle", e)
        }
    }

    /**
     * 更新兼容包名列表
     */
    fun updateWriteCompatPackages(context: Context, packages: String) {
        try {
            val prefs = Prefs(context)
            prefs.floatingWriteCompatPackages = packages
            Log.d(TAG, "WriteCompatPackages updated")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update WriteCompatPackages", e)
        }
    }

    /**
     * 更新粘贴方案包名列表
     */
    fun updateWritePastePackages(context: Context, packages: String) {
        try {
            val prefs = Prefs(context)
            prefs.floatingWritePastePackages = packages
            Log.d(TAG, "WritePastePackages updated")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update WritePastePackages", e)
        }
    }

    /**
     * 更新键盘可见性兼容包名列表
     */
    fun updateImeVisibilityCompatPackages(context: Context, packages: String) {
        try {
            val prefs = Prefs(context)
            prefs.floatingImeVisibilityCompatPackages = packages
            Log.d(TAG, "ImeVisibilityCompatPackages updated")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update ImeVisibilityCompatPackages", e)
        }
    }

    /**
     * 检查无障碍服务是否已启用
     */
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = "${context.packageName}/com.brycewg.asrkb.ui.AsrAccessibilityService"
        val enabledServicesSetting = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to check accessibility service", e)
            return false
        }
        return enabledServicesSetting?.contains(expectedComponentName) == true
    }

    /**
     * 权限请求类型
     */
    enum class PermissionRequest {
        OVERLAY,
        ACCESSIBILITY
    }
}
