package com.brycewg.asrkb.ui.floating

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.ui.ime.ImePickerActivity
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.clipboard.SyncClipboardManager
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 轮盘菜单辅助类，用于显示和管理悬浮球的轮盘菜单及其子菜单（如厂商选择、Prompt 预设）
 */
class RadialMenuHelper(
    private val context: Context,
    private val windowManager: WindowManager,
    private val prefs: Prefs,
    private val serviceScope: CoroutineScope,
    private val getBallCenter: () -> Pair<Int, Int>,
    private val isBallOnLeft: () -> Boolean,
    private val currentUiAlpha: () -> Float,
    private val dp: (Int) -> Int,
    private val hapticTap: (View?) -> Unit
) {

    companion object {
        private const val TAG = "RadialMenuHelper"
    }

    private var radialMenuView: View? = null
    private var subMenuView: View? = null

    /**
     * 轮盘菜单项数据类
     */
    data class RadialMenuItem(
        val iconRes: Int,
        val label: String,
        val contentDescription: String,
        val onClick: () -> Unit
    )

    /**
     * 显示轮盘菜单
     * @param items 菜单项列表
     * @param onMenuClosed 菜单关闭时的回调
     */
    fun showRadialMenu(items: List<RadialMenuItem>, onMenuClosed: () -> Unit): View? {
        if (radialMenuView != null) return radialMenuView

        val root = android.widget.FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnClickListener {
                hideRadialMenu()
                onMenuClosed()
            }
        }
        root.alpha = currentUiAlpha()

        val isLeft = isBallOnLeft()
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_panel_round)
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
        }

        items.forEachIndexed { index, item ->
            val row = buildMenuItem(item.iconRes, item.label, item.contentDescription) {
                // 先关闭一级菜单，再执行动作，避免与二级菜单重叠
                hideRadialMenu()
                onMenuClosed()
                // 再执行对应的点击逻辑（例如弹出二级菜单）
                item.onClick()
            }
            val lpRow = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) lpRow.topMargin = dp(6)
            container.addView(row, lpRow)
        }

        container.alpha = 0f
        container.translationX = if (isLeft) dp(8).toFloat() else -dp(8).toFloat()

        val paramsContainer = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        root.addView(container, paramsContainer)

        container.post {
            positionMenu(container, isLeft)
        }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // 不获取焦点，仅处理触摸，避免阻塞系统返回键等；
            // 保持全屏遮罩以接收空白区域点击关闭。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        return try {
            windowManager.addView(root, params)
            radialMenuView = root
            root
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show radial menu", e)
            null
        }
    }

    /**
     * 隐藏轮盘菜单
     */
    fun hideRadialMenu() {
        radialMenuView?.let { v ->
            try {
                cancelAllAnimations(v)
                windowManager.removeView(v)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to hide radial menu", e)
            }
            radialMenuView = null
        }
    }

    /**
     * 显示 ASR 厂商选择子菜单
     */
    fun showAsrVendorMenu(onVendorSelected: (AsrVendor) -> Unit, onMenuClosed: () -> Unit): View? {
        hideSubMenu()

        val root = android.widget.FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnClickListener {
                hideSubMenu()
                onMenuClosed()
            }
        }
        root.alpha = currentUiAlpha()

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_panel_round)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }

        val title = android.widget.TextView(context).apply {
            text = context.getString(R.string.label_choose_asr_vendor)
            setTextColor(0xFF111111.toInt())
            textSize = 16f
            setPadding(0, 0, 0, dp(4))
        }
        container.addView(
            title,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val entries = listOf(
            AsrVendor.Volc to context.getString(R.string.vendor_volc),
            AsrVendor.SiliconFlow to context.getString(R.string.vendor_sf),
            AsrVendor.ElevenLabs to context.getString(R.string.vendor_eleven),
            AsrVendor.OpenAI to context.getString(R.string.vendor_openai),
            AsrVendor.DashScope to context.getString(R.string.vendor_dashscope),
            AsrVendor.Gemini to context.getString(R.string.vendor_gemini),
            AsrVendor.Soniox to context.getString(R.string.vendor_soniox),
            AsrVendor.SenseVoice to context.getString(R.string.vendor_sensevoice)
        )

        val current = try {
            prefs.asrVendor
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get current ASR vendor", e)
            AsrVendor.Volc
        }

        entries.forEach { (vendor, name) ->
            val tv = android.widget.TextView(context).apply {
                text = if (vendor == current) "✓  $name" else name
                setTextColor(0xFF222222.toInt())
                textSize = 14f
                setPadding(dp(6), dp(8), dp(6), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onVendorSelected(vendor)
                    hideSubMenu()
                    onMenuClosed()
                }
            }
            container.addView(
                tv,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        return showSubMenu(root, container)
    }

    /**
     * 显示 Prompt 预设选择子菜单
     */
    fun showPromptPresetMenu(onPresetSelected: (String, String) -> Unit, onMenuClosed: () -> Unit): View? {
        hideSubMenu()

        val root = android.widget.FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            setOnClickListener {
                hideSubMenu()
                onMenuClosed()
            }
        }
        root.alpha = currentUiAlpha()

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_panel_round)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }

        val title = android.widget.TextView(context).apply {
            text = context.getString(R.string.label_llm_prompt_presets)
            setTextColor(0xFF111111.toInt())
            textSize = 16f
            setPadding(0, 0, 0, dp(4))
        }
        container.addView(
            title,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val presets = try {
            prefs.getPromptPresets()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get prompt presets", e)
            emptyList()
        }
        val activeId = try {
            prefs.activePromptId
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get active prompt ID", e)
            ""
        }

        presets.forEach { preset ->
            val tv = android.widget.TextView(context).apply {
                text = if (preset.id == activeId) "✓  ${preset.title}" else preset.title
                setTextColor(0xFF222222.toInt())
                textSize = 14f
                setPadding(dp(6), dp(8), dp(6), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onPresetSelected(preset.id, preset.title)
                    hideSubMenu()
                    onMenuClosed()
                }
            }
            container.addView(
                tv,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        return showSubMenu(root, container)
    }

    /**
     * 隐藏子菜单
     */
    fun hideSubMenu() {
        subMenuView?.let { v ->
            try {
                cancelAllAnimations(v)
                windowManager.removeView(v)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to hide sub menu", e)
            }
            subMenuView = null
        }
    }

    /**
     * 通用的子菜单显示逻辑
     */
    private fun showSubMenu(root: android.widget.FrameLayout, container: android.widget.LinearLayout): View? {
        val paramsContainer = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        root.addView(container, paramsContainer)

        val (cx, cy) = getBallCenter()
        val isLeft = cx < (context.resources.displayMetrics.widthPixels / 2)
        container.alpha = 0f
        container.translationX = if (isLeft) dp(8).toFloat() else -dp(8).toFloat()

        container.post {
            positionSubMenu(container, cx, cy, isLeft)
        }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // 同上：不抢焦点，确保点击空白可关闭
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        return try {
            windowManager.addView(root, params)
            subMenuView = root
            root
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show sub menu", e)
            null
        }
    }

    /**
     * 构建单个菜单项视图
     */
    private fun buildMenuItem(iconRes: Int, label: String, cd: String, onClick: () -> Unit): View {
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(context, R.drawable.ripple_capsule)
            val p = dp(10)
            setPadding(p, p, p, p)
            isClickable = true
            isFocusable = true
            contentDescription = cd
            setOnClickListener {
                hapticTap(this)
                onClick()
            }
        }

        val iv = ImageView(context).apply {
            setImageResource(iconRes)
            try {
                setColorFilter(0xFF111111.toInt())
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to set color filter on menu icon", e)
            }
        }

        val tv = android.widget.TextView(context).apply {
            text = label
            setTextColor(0xFF111111.toInt())
            textSize = 12f
            setPadding(dp(6), 0, 0, 0)
        }

        layout.addView(iv, android.widget.LinearLayout.LayoutParams(dp(18), dp(18)))
        layout.addView(
            tv,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return layout
    }

    /**
     * 定位轮盘菜单
     */
    private fun positionMenu(container: View, isLeft: Boolean) {
        try {
            val dm = context.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val (centerX, centerY) = getBallCenter()
            val offset = dp(16)
            val w = container.width
            val h = container.height
            val lpC = container.layoutParams as android.widget.FrameLayout.LayoutParams
            val left = if (isLeft) (centerX + offset) else (centerX - offset - w)
            val top = centerY - h / 2
            lpC.leftMargin = left.coerceIn(0, (screenW - w).coerceAtLeast(0))
            lpC.topMargin = top.coerceIn(dp(8), (screenH - h - dp(8)).coerceAtLeast(0))
            container.layoutParams = lpC
            container.animate().alpha(1f).translationX(0f).setDuration(160).start()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to position menu", e)
        }
    }

    /**
     * 定位子菜单
     */
    private fun positionSubMenu(container: View, cx: Int, cy: Int, isLeft: Boolean) {
        try {
            val dm = context.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val offset = dp(16)
            val w = container.width
            val h = container.height
            val lpC = container.layoutParams as android.widget.FrameLayout.LayoutParams
            val left = if (isLeft) (cx + offset) else (cx - offset - w)
            val top = cy - h / 2
            lpC.leftMargin = left.coerceIn(0, (screenW - w).coerceAtLeast(0))
            lpC.topMargin = top.coerceIn(0, (screenH - h).coerceAtLeast(0))
            container.layoutParams = lpC
            container.animate().alpha(1f).translationX(0f).setDuration(160).start()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to position sub menu", e)
        }
    }

    /**
     * 递归取消所有动画
     */
    private fun cancelAllAnimations(view: View) {
        try {
            view.animate().cancel()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to cancel animation", e)
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                cancelAllAnimations(view.getChildAt(i))
            }
        }
    }

    /**
     * 创建标准的轮盘菜单项
     */
    fun createStandardMenuItems(
        onPromptPresetClick: () -> Unit,
        onAsrVendorClick: () -> Unit,
        onImePickerClick: () -> Unit,
        onMoveModeClick: () -> Unit,
        onPostprocToggleClick: () -> Unit,
        onClipboardUploadClick: () -> Unit,
        onClipboardPullClick: () -> Unit
    ): List<RadialMenuItem> {
        return listOf(
            RadialMenuItem(
                R.drawable.ic_prompt,
                context.getString(R.string.label_radial_switch_prompt),
                context.getString(R.string.label_radial_switch_prompt),
                onPromptPresetClick
            ),
            RadialMenuItem(
                R.drawable.ic_waveform,
                context.getString(R.string.label_radial_switch_asr),
                context.getString(R.string.label_radial_switch_asr),
                onAsrVendorClick
            ),
            RadialMenuItem(
                R.drawable.ic_keyboard,
                context.getString(R.string.label_radial_switch_ime),
                context.getString(R.string.label_radial_switch_ime),
                onImePickerClick
            ),
            RadialMenuItem(
                R.drawable.ic_move,
                context.getString(R.string.label_radial_move),
                context.getString(R.string.label_radial_move),
                onMoveModeClick
            ),
            RadialMenuItem(
                if (try {
                        prefs.postProcessEnabled
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to get postProcessEnabled state", e)
                        false
                    }
                ) R.drawable.ic_star_filled else R.drawable.ic_star_outline,
                context.getString(R.string.label_radial_postproc),
                context.getString(R.string.label_radial_postproc),
                onPostprocToggleClick
            ),
            RadialMenuItem(
                R.drawable.ic_stat_upload,
                context.getString(R.string.label_radial_clipboard_upload),
                context.getString(R.string.label_radial_clipboard_upload),
                onClipboardUploadClick
            ),
            RadialMenuItem(
                R.drawable.ic_stat_download,
                context.getString(R.string.label_radial_clipboard_pull),
                context.getString(R.string.label_radial_clipboard_pull),
                onClipboardPullClick
            )
        )
    }

    // ==================== 通用操作方法 ====================

    /**
     * 处理 ASR 厂商选择
     */
    fun handleAsrVendorChange(newVendor: AsrVendor, oldVendor: AsrVendor, vendorName: String) {
        try {
            prefs.asrVendor = newVendor
            // 离开 SenseVoice 时卸载
            if (oldVendor == AsrVendor.SenseVoice && newVendor != AsrVendor.SenseVoice) {
                try {
                    com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to unload SenseVoice recognizer", e)
                }
            } else if (newVendor == AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
                // 切回 SenseVoice 且开启预加载时预热
                serviceScope.launch(Dispatchers.Default) {
                    try {
                        com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(context, prefs)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to preload SenseVoice", e)
                    }
                }
            }
            Toast.makeText(context, vendorName, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to change ASR vendor", e)
        }
    }

    /**
     * 处理 Prompt 预设选择
     */
    fun handlePromptPresetChange(presetId: String, presetTitle: String) {
        try {
            prefs.activePromptId = presetId
            Toast.makeText(
                context,
                context.getString(R.string.switched_preset, presetTitle),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to change prompt preset", e)
        }
    }

    /**
     * 切换 AI 后处理开关
     */
    fun handlePostprocToggle() {
        try {
            val newVal = !prefs.postProcessEnabled
            prefs.postProcessEnabled = newVal
            val msg = context.getString(
                R.string.status_postproc,
                if (newVal) context.getString(R.string.toggle_on) else context.getString(R.string.toggle_off)
            )
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to toggle postproc", e)
        }
    }

    /**
     * 上传剪贴板
     */
    fun handleClipboardUpload(onComplete: (Boolean) -> Unit) {
        try {
            val mgr = SyncClipboardManager(context, prefs, serviceScope)
            serviceScope.launch(Dispatchers.IO) {
                val ok = try {
                    mgr.uploadOnce()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to upload clipboard", e)
                    false
                }
                launch(Dispatchers.Main) {
                    onComplete(ok)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initiate clipboard upload", e)
        }
    }

    /**
     * 拉取剪贴板
     */
    fun handleClipboardPull(onComplete: (Boolean) -> Unit) {
        try {
            val mgr = SyncClipboardManager(context, prefs, serviceScope)
            serviceScope.launch(Dispatchers.IO) {
                val ok = try {
                    mgr.pullNow(updateClipboard = true).first
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to pull clipboard", e)
                    false
                }
                launch(Dispatchers.Main) {
                    onComplete(ok)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initiate clipboard pull", e)
        }
    }

    /**
     * 调用输入法选择器
     */
    fun invokeImePicker() {
        try {
            val imm = context.getSystemService(InputMethodManager::class.java)
            if (!isOurImeEnabled(imm)) {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
            val intent = Intent(context, ImePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to invoke IME picker", e)
            try {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Throwable) {
                Log.e(TAG, "Failed to open IME settings", e2)
            }
        }
    }

    /**
     * 判断本应用输入法是否已启用
     */
    private fun isOurImeEnabled(imm: InputMethodManager?): Boolean {
        val list = try {
            imm?.enabledInputMethodList
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get enabled IME list", e)
            null
        }
        if (list?.any { it.packageName == context.packageName } == true) return true
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            )
            val id = "${context.packageName}/.ime.AsrKeyboardService"
            enabled?.contains(id) == true || (enabled?.split(':')
                ?.any { it.startsWith(context.packageName) } == true)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to check if our IME is enabled", e)
            false
        }
    }
}
