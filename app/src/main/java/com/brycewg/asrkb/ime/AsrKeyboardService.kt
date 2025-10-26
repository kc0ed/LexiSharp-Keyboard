package com.brycewg.asrkb.ime

import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Vibrator
import android.view.LayoutInflater
import android.graphics.Color
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import android.view.ViewConfiguration
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.android.material.color.MaterialColors
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.clipboard.SyncClipboardManager
import com.brycewg.asrkb.store.debug.DebugLogManager

/**
 * ASR 键盘服务
 *
 * 职责：
 * - 管理键盘视图的生命周期
 * - 绑定视图事件到 KeyboardActionHandler
 * - 响应 UI 更新通知
 * - 管理系统回调（onStartInputView, onFinishInputView 等）
 * - 协调剪贴板同步等辅助功能
 *
 * 复杂的业务逻辑已拆分到：
 * - KeyboardActionHandler: 键盘动作处理和状态管理
 * - AsrSessionManager: ASR 引擎生命周期管理
 * - InputConnectionHelper: 输入连接操作封装
 * - BackspaceGestureHandler: 退格手势处理
 */
class AsrKeyboardService : InputMethodService(), KeyboardActionHandler.UiListener {

    companion object {
        const val ACTION_REFRESH_IME_UI = "com.brycewg.asrkb.action.REFRESH_IME_UI"
    }

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    // ========== 组件实例 ==========
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var inputHelper: InputConnectionHelper
    private lateinit var asrManager: AsrSessionManager
    private lateinit var actionHandler: KeyboardActionHandler
    private lateinit var backspaceGestureHandler: BackspaceGestureHandler

    // ========== 视图引用 ==========
    private var rootView: View? = null
    private var btnMic: FloatingActionButton? = null
    private var btnSettings: ImageButton? = null
    private var btnEnter: ImageButton? = null
    private var btnPostproc: ImageButton? = null
    private var btnAiEdit: ImageButton? = null
    private var btnBackspace: ImageButton? = null
    private var btnPromptPicker: ImageButton? = null
    private var btnHide: ImageButton? = null
    private var btnImeSwitcher: ImageButton? = null
    private var btnPunct1: TextView? = null
    private var btnPunct2: TextView? = null
    private var btnPunct3: TextView? = null
    private var btnPunct4: TextView? = null
    private var txtStatus: TextView? = null
    private var groupMicStatus: View? = null
    // 记录麦克风按下的原始Y坐标，用于检测上滑手势
    private var micDownRawY: Float = 0f

    // ========== 剪贴板和其他辅助功能 ==========
    private var clipboardPreviewTimeout: Runnable? = null
    private var prefsReceiver: BroadcastReceiver? = null
    private var syncClipboardManager: SyncClipboardManager? = null
    private var svPreloadTriggered: Boolean = false
    private var suppressReturnPrevImeOnHideOnce: Boolean = false

    // ========== 生命周期 ==========

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)

        // 初始化组件
        inputHelper = InputConnectionHelper("AsrKeyboardService")
        asrManager = AsrSessionManager(this, serviceScope, prefs)
        actionHandler = KeyboardActionHandler(
            this,
            serviceScope,
            prefs,
            asrManager,
            inputHelper,
            LlmPostProcessor()
        )
        backspaceGestureHandler = BackspaceGestureHandler(inputHelper)

        // 设置监听器
        asrManager.setListener(actionHandler)
        actionHandler.setUiListener(this)
        actionHandler.setInputConnectionProvider { currentInputConnection }

        // 构建初始 ASR 引擎
        asrManager.rebuildEngine()

        // 监听设置变化以即时刷新键盘 UI
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_REFRESH_IME_UI) {
                    val v = rootView
                    if (v != null) {
                        applyKeyboardHeightScale(v)
                        applyButtonSwapAccordingToPrefs(v)
                        v.requestLayout()
                    }
                }
            }
        }
        prefsReceiver = r
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                /* context = */ this,
                /* receiver = */ r,
                /* filter = */ IntentFilter(ACTION_REFRESH_IME_UI),
                /* flags = */ androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Throwable) {
            android.util.Log.e("AsrKeyboardService", "Failed to register prefsReceiver", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        asrManager.cleanup()
        serviceScope.cancel()
        try {
            syncClipboardManager?.stop()
        } catch (e: Throwable) {
            android.util.Log.e("AsrKeyboardService", "Failed to stop SyncClipboardManager", e)
        }
        try {
            prefsReceiver?.let { unregisterReceiver(it) }
        } catch (e: Throwable) {
            android.util.Log.e("AsrKeyboardService", "Failed to unregister prefsReceiver", e)
        }
        prefsReceiver = null
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        // IME context often uses a framework theme; wrap with our theme and Material dynamic colors.
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_ASRKeyboard_Ime)
        val dynamicContext = com.google.android.material.color.DynamicColors.wrapContextIfAvailable(themedContext)
        val view = LayoutInflater.from(dynamicContext).inflate(R.layout.keyboard_view, null, false)
        rootView = view

        // 查找所有视图
        bindViews(view)

        // 设置监听器
        setupListeners()

        // 应用偏好设置
        applyButtonSwapAccordingToPrefs(view)
        applyKeyboardHeightScale(view)
        applyPunctuationLabels()

        // 更新初始 UI 状态
        refreshPermissionUi()
        onStateChanged(actionHandler.getCurrentState())

        // 同步系统导航栏颜色
        try {
            view.post { syncSystemBarsToKeyboardBackground(view) }
        } catch (_: Throwable) { }

        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        try {
            DebugLogManager.log(
                category = "ime",
                event = "start_input_view",
                data = mapOf(
                    "pkg" to (info?.packageName ?: ""),
                    "inputType" to (info?.inputType ?: 0),
                    "imeOptions" to (info?.imeOptions ?: 0),
                    "icNull" to (currentInputConnection == null),
                    "isPassword" to isPasswordEditor(info),
                    "isMultiLine" to ((info?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0),
                    "actionId" to ((info?.imeOptions ?: 0) and android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
                )
            )
        } catch (t: Throwable) {
            android.util.Log.w("AsrKeyboardService", "Failed to log start_input_view", t)
        }

        // 键盘面板首次出现时，按需异步预加载本地 SenseVoice
        tryPreloadSenseVoice()

        // 如果当前字段是密码框且用户选择自动切换，切回上一个输入法
        if (prefs.autoSwitchOnPassword && isPasswordEditor(info)) {
            try {
                suppressReturnPrevImeOnHideOnce = true
                val ok = try { switchToPreviousInputMethod() } catch (_: Throwable) { false }
                if (!ok) {
                    val imm = getSystemService(InputMethodManager::class.java)
                    imm?.showInputMethodPicker()
                }
            } catch (_: Throwable) { }
            try {
                requestHideSelf(0)
            } catch (_: Throwable) { }
            return
        }

        // 刷新 UI
        btnImeSwitcher?.visibility = View.VISIBLE
        applyPunctuationLabels()
        refreshPermissionUi()

        // 同步系统栏颜色
        try {
            rootView?.post { syncSystemBarsToKeyboardBackground(rootView) }
        } catch (_: Throwable) { }

        // 更新按钮位置（可能在设置中改变）
        applyButtonSwapAccordingToPrefs(rootView)

        // 若正在录音，恢复中间结果为 composing
        if (asrManager.isRunning()) {
            actionHandler.restorePartialAsComposing(currentInputConnection)
        }

        // 启动剪贴板同步
        startClipboardSync()

        // 预热耳机路由（键盘显示）
        try { BluetoothRouteManager.setImeActive(this, true) } catch (t: Throwable) { android.util.Log.w("AsrKeyboardService", "BluetoothRouteManager setImeActive(true)", t) }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        try {
            DebugLogManager.log("ime", "finish_input_view")
        } catch (_: Throwable) { }
        try {
            syncClipboardManager?.stop()
        } catch (_: Throwable) { }

        // 键盘收起，解除预热（若未在录音）
        try { BluetoothRouteManager.setImeActive(this, false) } catch (t: Throwable) { android.util.Log.w("AsrKeyboardService", "BluetoothRouteManager setImeActive(false)", t) }

        // 如开启：键盘收起后自动切回上一个输入法
        if (prefs.returnPrevImeOnHide) {
            if (suppressReturnPrevImeOnHideOnce) {
                // 清除一次性抑制标记，避免连环切换
                suppressReturnPrevImeOnHideOnce = false
            } else {
                try {
                    val ok = try { switchToPreviousInputMethod() } catch (_: Throwable) { false }
                    if (!ok) {
                        // 若系统未允许切回，不做额外操作
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("AsrKeyboardService", "Auto return previous IME on hide failed", e)
                }
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)

        // 若正在录音，同步中间结果为 composing
        if (asrManager.isRunning()) {
            actionHandler.restorePartialAsComposing(currentInputConnection)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // 避免全屏候选，保持紧凑的麦克风键盘
        return false
    }

    // ========== KeyboardActionHandler.UiListener 实现 ==========

    override fun onStateChanged(state: KeyboardState) {
        when (state) {
            is KeyboardState.Idle -> updateUiIdle()
            is KeyboardState.Listening -> updateUiListening()
            is KeyboardState.Processing -> updateUiProcessing()
            is KeyboardState.AiProcessing -> updateUiAiProcessing()
            is KeyboardState.AiEditListening -> updateUiAiEditListening()
            is KeyboardState.AiEditProcessing -> updateUiAiEditProcessing()
        }

        // 更新中间结果到 composing
        if (state is KeyboardState.Listening && state.partialText != null) {
            currentInputConnection?.let { ic ->
                inputHelper.setComposingText(ic, state.partialText)
            }
        }
    }

    override fun onStatusMessage(message: String) {
        clearStatusTextStyle()
        txtStatus?.text = message
    }

    override fun onVibrate() {
        vibrateTick()
    }

    override fun onShowClipboardPreview(preview: ClipboardPreview) {
        val tv = txtStatus ?: return
        tv.text = preview.displaySnippet

        // 显示圆角遮罩
        try {
            tv.setBackgroundResource(R.drawable.bg_status_chip)
        } catch (_: Throwable) { }

        // 设置内边距
        try {
            val d = tv.resources.displayMetrics.density
            val ph = (12f * d + 0.5f).toInt()
            val pv = (4f * d + 0.5f).toInt()
            tv.setPaddingRelative(ph, pv, ph, pv)
        } catch (_: Throwable) { }

        // 启用点击粘贴
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.handleClipboardPreviewClick(currentInputConnection)
        }

        // 超时自动恢复
        clipboardPreviewTimeout?.let { tv.removeCallbacks(it) }
        val r = Runnable { actionHandler.hideClipboardPreview() }
        clipboardPreviewTimeout = r
        try {
            tv.postDelayed(r, 10_000)
        } catch (_: Throwable) { }
    }

    override fun onHideClipboardPreview() {
        val tv = txtStatus ?: return
        clipboardPreviewTimeout?.let { tv.removeCallbacks(it) }
        clipboardPreviewTimeout = null

        try {
            tv.isClickable = false
            tv.isFocusable = false
            tv.setOnClickListener(null)
            tv.background = null
            tv.setPaddingRelative(0, 0, 0, 0)
        } catch (_: Throwable) { }

        // 恢复默认状态文案
        try {
            if (asrManager.isRunning()) {
                updateUiListening()
            } else {
                updateUiIdle()
            }
        } catch (_: Throwable) { }
    }

    // ========== 视图绑定和监听器设置 ==========

    private fun bindViews(view: View) {
        btnMic = view.findViewById(R.id.btnMic)
        btnSettings = view.findViewById(R.id.btnSettings)
        btnEnter = view.findViewById(R.id.btnEnter)
        btnPostproc = view.findViewById(R.id.btnPostproc)
        btnAiEdit = view.findViewById(R.id.btnAiEdit)
        btnBackspace = view.findViewById(R.id.btnBackspace)
        btnPromptPicker = view.findViewById(R.id.btnPromptPicker)
        btnHide = view.findViewById(R.id.btnHide)
        btnImeSwitcher = view.findViewById(R.id.btnImeSwitcher)
        btnPunct1 = view.findViewById(R.id.btnPunct1)
        btnPunct2 = view.findViewById(R.id.btnPunct2)
        btnPunct3 = view.findViewById(R.id.btnPunct3)
        btnPunct4 = view.findViewById(R.id.btnPunct4)
        txtStatus = view.findViewById(R.id.txtStatus)
        groupMicStatus = view.findViewById(R.id.groupMicStatus)

        // 修复麦克风垂直位置
        var micBaseGroupHeight = -1
        groupMicStatus?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = v.height
            if (h <= 0) return@addOnLayoutChangeListener
            if (micBaseGroupHeight < 0) {
                micBaseGroupHeight = h
                btnMic?.translationY = 0f
            } else {
                val delta = h - micBaseGroupHeight
                btnMic?.translationY = (delta / 2f)
            }
        }
    }

    private fun setupListeners() {
        // 麦克风按钮
        btnMic?.setOnClickListener { v ->
            if (!prefs.micTapToggleEnabled) return@setOnClickListener
            performKeyHaptic(v)
            if (!checkAsrReady()) return@setOnClickListener
            actionHandler.handleMicTapToggle()
        }

        btnMic?.setOnTouchListener { v, event ->
            if (prefs.micTapToggleEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performKeyHaptic(v)
                    if (!checkAsrReady()) {
                        v.performClick()
                        return@setOnTouchListener true
                    }
                    micDownRawY = try { event.rawY } catch (_: Throwable) { event.y }
                    actionHandler.handleMicPressDown()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val slop = try { ViewConfiguration.get(v.context).scaledTouchSlop } catch (_: Throwable) { 16 }
                    val upY = try { event.rawY } catch (_: Throwable) { event.y }
                    val dy = (micDownRawY - upY)
                    val autoEnter = prefs.micSwipeUpAutoEnterEnabled && dy > slop
                    actionHandler.handleMicPressUp(autoEnter)
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    actionHandler.handleMicPressUp(false)
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        // AI 编辑按钮
        btnAiEdit?.setOnClickListener { v ->
            performKeyHaptic(v)
            if (!hasRecordAudioPermission()) {
                refreshPermissionUi()
                return@setOnClickListener
            }
            if (!prefs.hasAsrKeys()) {
                clearStatusTextStyle()
                txtStatus?.text = getString(R.string.hint_need_keys)
                return@setOnClickListener
            }
            if (!prefs.hasLlmKeys()) {
                clearStatusTextStyle()
                txtStatus?.text = getString(R.string.hint_need_llm_keys)
                return@setOnClickListener
            }
            actionHandler.handleAiEditClick(currentInputConnection)
        }

        // 后处理开关：用填充/线性图标表示启用/禁用，不再使用透明度
        btnPostproc?.apply {
            try {
                setImageResource(if (prefs.postProcessEnabled) R.drawable.magic_wand_fill else R.drawable.magic_wand)
            } catch (e: Throwable) {
                android.util.Log.w("AsrKeyboardService", "Failed to set postproc icon (init)", e)
            }
            setOnClickListener { v ->
                performKeyHaptic(v)
                actionHandler.handlePostprocessToggle()
                try {
                    setImageResource(if (prefs.postProcessEnabled) R.drawable.magic_wand_fill else R.drawable.magic_wand)
                } catch (e: Throwable) {
                    android.util.Log.w("AsrKeyboardService", "Failed to set postproc icon (toggle)", e)
                }
            }
        }

        // 退格按钮（委托给手势处理器）
        btnBackspace?.setOnClickListener { v ->
            performKeyHaptic(v)
            inputHelper.sendBackspace(currentInputConnection)
        }

        btnBackspace?.setOnTouchListener { v, event ->
            backspaceGestureHandler.handleTouchEvent(v, event, currentInputConnection)
        }

        // 设置退格手势监听器
        backspaceGestureHandler.setListener(object : BackspaceGestureHandler.Listener {
            override fun onSingleDelete() {
                actionHandler.saveUndoSnapshot(currentInputConnection)
                inputHelper.sendBackspace(currentInputConnection)
            }

            override fun onClearAll() {
                actionHandler.saveUndoSnapshot(currentInputConnection)
            }

            override fun onUndo() {
                actionHandler.handleUndo(currentInputConnection)
            }

            override fun onVibrateRequest() {
                vibrateTick()
            }
        })

        // 其他按钮
        btnSettings?.setOnClickListener { v ->
            performKeyHaptic(v)
            openSettings()
        }

        btnEnter?.setOnClickListener { v ->
            performKeyHaptic(v)
            inputHelper.sendEnter(currentInputConnection)
        }

        btnHide?.setOnClickListener { v ->
            performKeyHaptic(v)
            hideKeyboardPanel()
        }

        btnImeSwitcher?.setOnClickListener { v ->
            performKeyHaptic(v)
            if (prefs.fcitx5ReturnOnImeSwitch) {
                try {
                    if (asrManager.isRunning()) asrManager.stopRecording()
                } catch (_: Throwable) { }
                suppressReturnPrevImeOnHideOnce = true
                val switched = try { switchToPreviousInputMethod() } catch (_: Throwable) { false }
                if (!switched) {
                    showImePicker()
                }
            } else {
                showImePicker()
            }
        }

        btnPromptPicker?.setOnClickListener { v ->
            performKeyHaptic(v)
            showPromptPicker(v)
        }

        // 标点按钮
        btnPunct1?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(currentInputConnection, prefs.punct1)
        }
        btnPunct2?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(currentInputConnection, prefs.punct2)
        }
        btnPunct3?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(currentInputConnection, prefs.punct3)
        }
        btnPunct4?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.commitText(currentInputConnection, prefs.punct4)
        }
    }

    // ========== UI 更新方法 ==========

    private fun updateUiIdle() {
        clearStatusTextStyle()
        txtStatus?.text = getString(R.string.status_idle)
        btnMic?.isSelected = false
        try { btnMic?.setImageResource(R.drawable.microphone) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set mic icon (idle)", e) }
        try { btnAiEdit?.setImageResource(R.drawable.pencil_simple_line) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set AI edit icon (idle)", e) }
        currentInputConnection?.let { inputHelper.finishComposingText(it) }
    }

    private fun updateUiListening() {
        clearStatusTextStyle()
        txtStatus?.text = getString(R.string.status_listening)
        btnMic?.isSelected = true
        try { btnMic?.setImageResource(R.drawable.microphone_fill) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set mic icon (listening)", e) }
        try { btnAiEdit?.setImageResource(R.drawable.pencil_simple_line) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set AI edit icon (listening)", e) }
    }

    private fun updateUiProcessing() {
        clearStatusTextStyle()
        txtStatus?.text = getString(R.string.status_recognizing)
        btnMic?.isSelected = false
        try { btnMic?.setImageResource(R.drawable.microphone) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set mic icon (processing)", e) }
        try { btnAiEdit?.setImageResource(R.drawable.pencil_simple_line) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set AI edit icon (processing)", e) }
    }

    private fun updateUiAiProcessing() {
        clearStatusTextStyle()
        txtStatus?.text = getString(R.string.status_ai_processing)
        btnMic?.isSelected = false
        try { btnMic?.setImageResource(R.drawable.microphone) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set mic icon (ai processing)", e) }
        try { btnAiEdit?.setImageResource(R.drawable.pencil_simple_line) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set AI edit icon (ai processing)", e) }
    }

    private fun updateUiAiEditListening() {
        clearStatusTextStyle()
        txtStatus?.text = getString(R.string.status_ai_edit_listening)
        btnMic?.isSelected = false
        try { btnMic?.setImageResource(R.drawable.microphone_fill) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set mic icon (ai edit listening)", e) }
        try { btnAiEdit?.setImageResource(R.drawable.pencil_simple_line_fill) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set AI edit icon (ai edit listening)", e) }
    }

    private fun updateUiAiEditProcessing() {
        clearStatusTextStyle()
        txtStatus?.text = getString(R.string.status_ai_editing)
        btnMic?.isSelected = false
        try { btnMic?.setImageResource(R.drawable.microphone) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set mic icon (ai edit processing)", e) }
        try { btnAiEdit?.setImageResource(R.drawable.pencil_simple_line_fill) } catch (e: Throwable) { android.util.Log.w("AsrKeyboardService", "Failed to set AI edit icon (ai edit processing)", e) }
    }

    // ========== 辅助方法 ==========

    /**
     * 清除状态文本的粘贴板预览样式（背景遮罩、内边距、点击监听器）
     * 确保普通状态文本不会显示粘贴板预览的样式
     */
    private fun clearStatusTextStyle() {
        val tv = txtStatus ?: return
        try {
            tv.isClickable = false
            tv.isFocusable = false
            tv.setOnClickListener(null)
            tv.background = null
            tv.setPaddingRelative(0, 0, 0, 0)
        } catch (_: Throwable) { }
    }

    override fun onShowRetryChip(label: String) {
        val tv = txtStatus ?: return
        tv.text = label
        // 使用与剪贴板预览相同的芯片样式
        try { tv.setBackgroundResource(R.drawable.bg_status_chip) } catch (_: Throwable) { }
        try {
            val d = tv.resources.displayMetrics.density
            val ph = (12f * d + 0.5f).toInt()
            val pv = (4f * d + 0.5f).toInt()
            tv.setPaddingRelative(ph, pv, ph, pv)
        } catch (_: Throwable) { }
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.handleRetryClick()
        }
    }

    override fun onHideRetryChip() {
        clearStatusTextStyle()
    }

    private fun checkAsrReady(): Boolean {
        if (!hasRecordAudioPermission()) {
            refreshPermissionUi()
            return false
        }
        if (!prefs.hasAsrKeys()) {
            refreshPermissionUi()
            return false
        }
        if (prefs.asrVendor == AsrVendor.SenseVoice) {
            val prepared = try {
                com.brycewg.asrkb.asr.isSenseVoicePrepared()
            } catch (_: Throwable) {
                false
            }
            if (!prepared) {
                val base = try {
                    getExternalFilesDir(null)
                } catch (_: Throwable) {
                    null
                } ?: filesDir
                val probeRoot = java.io.File(base, "sensevoice")
                val variant = try {
                    prefs.svModelVariant
                } catch (_: Throwable) {
                    "small-int8"
                }
                val variantDir = if (variant == "small-full") {
                    java.io.File(probeRoot, "small-full")
                } else {
                    java.io.File(probeRoot, "small-int8")
                }
                val found = com.brycewg.asrkb.asr.findSvModelDir(variantDir)
                    ?: com.brycewg.asrkb.asr.findSvModelDir(probeRoot)
                if (found == null) {
                    clearStatusTextStyle()
                    txtStatus?.text = getString(R.string.error_sensevoice_model_missing)
                    return false
                }
            }
        }
        // 确保引擎匹配当前模式
        asrManager.ensureEngineMatchesMode()
        return true
    }

    private fun refreshPermissionUi() {
        clearStatusTextStyle()
        val granted = hasRecordAudioPermission()
        val hasKeys = prefs.hasAsrKeys()
        if (!granted) {
            btnMic?.isEnabled = false
            txtStatus?.text = getString(R.string.hint_need_permission)
        } else if (!hasKeys) {
            btnMic?.isEnabled = false
            txtStatus?.text = getString(R.string.hint_need_keys)
        } else {
            btnMic?.isEnabled = true
            txtStatus?.text = getString(R.string.status_idle)
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isPasswordEditor(info: EditorInfo?): Boolean {
        if (info == null) return false
        val it = info.inputType
        val klass = it and InputType.TYPE_MASK_CLASS
        val variation = it and InputType.TYPE_MASK_VARIATION
        return when (klass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun applyPunctuationLabels() {
        btnPunct1?.text = prefs.punct1
        btnPunct2?.text = prefs.punct2
        btnPunct3?.text = prefs.punct3
        btnPunct4?.text = prefs.punct4
    }

    private fun vibrateTick() {
        if (!prefs.micHapticEnabled) return
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(android.os.VibrationEffect.createOneShot(20, 50))
        } catch (_: Exception) { }
    }

    private fun performKeyHaptic(view: View?) {
        if (!prefs.micHapticEnabled) return
        try {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        } catch (_: Throwable) { }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun hideKeyboardPanel() {
        if (asrManager.isRunning()) {
            asrManager.stopRecording()
        }
        updateUiIdle()
        try {
            requestHideSelf(0)
        } catch (_: Exception) { }
    }

    private fun showImePicker() {
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showInputMethodPicker()
        } catch (_: Exception) { }
    }

    private fun showPromptPicker(anchor: View) {
        try {
            val presets = prefs.getPromptPresets()
            if (presets.isEmpty()) return
            val popup = androidx.appcompat.widget.PopupMenu(anchor.context, anchor)
            presets.forEachIndexed { idx, p ->
                val item = popup.menu.add(0, idx, idx, p.title)
                item.isCheckable = true
                if (p.id == prefs.activePromptId) item.isChecked = true
            }
            popup.menu.setGroupCheckable(0, true, true)
            popup.setOnMenuItemClickListener { mi ->
                val position = mi.itemId
                val preset = presets.getOrNull(position) ?: return@setOnMenuItemClickListener false
                prefs.activePromptId = preset.id
                clearStatusTextStyle()
                txtStatus?.text = getString(R.string.switched_preset, preset.title)
                true
            }
            popup.show()
        } catch (_: Throwable) { }
    }

    private fun tryPreloadSenseVoice() {
        try {
            if (!svPreloadTriggered) {
                val p = prefs
                if (p.asrVendor == AsrVendor.SenseVoice && p.svPreloadEnabled) {
                    val prepared = try {
                        com.brycewg.asrkb.asr.isSenseVoicePrepared()
                    } catch (_: Throwable) {
                        false
                    }
                    if (!prepared) {
                        try {
                            rootView?.post {
                                clearStatusTextStyle()
                                txtStatus?.text = getString(R.string.sv_loading_model)
                            }
                        } catch (_: Throwable) { }
                        serviceScope.launch(Dispatchers.Default) {
                            try {
                                com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(
                                    this@AsrKeyboardService,
                                    p,
                                    onLoadStart = null,
                                    onLoadDone = {
                                        try {
                                            rootView?.post {
                                                clearStatusTextStyle()
                                                txtStatus?.text = getString(R.string.sv_model_ready)
                                                rootView?.postDelayed({
                                                    clearStatusTextStyle()
                                                    if (asrManager.isRunning()) {
                                                        txtStatus?.text = getString(R.string.status_listening)
                                                    } else {
                                                        txtStatus?.text = getString(R.string.status_idle)
                                                    }
                                                }, 1200)
                                            }
                                        } catch (_: Throwable) { }
                                    },
                                    suppressToastOnStart = true
                                )
                            } catch (_: Throwable) { }
                        }
                    }
                }
                svPreloadTriggered = true
            }
        } catch (_: Throwable) { }
    }

    private fun startClipboardSync() {
        try {
            if (prefs.syncClipboardEnabled) {
                if (syncClipboardManager == null) {
                    syncClipboardManager = SyncClipboardManager(
                        this,
                        prefs,
                        serviceScope,
                        object : SyncClipboardManager.Listener {
                            override fun onPulledNewContent(text: String) {
                                try {
                                    rootView?.post { actionHandler.showClipboardPreview(text) }
                                } catch (_: Throwable) { }
                            }

                            override fun onUploadSuccess() {
                                try {
                                    rootView?.post {
                                        clearStatusTextStyle()
                                        txtStatus?.text = getString(R.string.sc_status_uploaded)
                                    }
                                } catch (_: Throwable) { }
                            }
                        }
                    )
                }
                syncClipboardManager?.start()
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        syncClipboardManager?.proactiveUploadIfChanged()
                    } catch (_: Throwable) { }
                    try {
                        syncClipboardManager?.pullNow(true)
                    } catch (_: Throwable) { }
                }
            } else {
                syncClipboardManager?.stop()
            }
        } catch (_: Throwable) { }
    }

    private fun applyButtonSwapAccordingToPrefs(view: View?) {
        if (view == null) return
        val ai = btnAiEdit ?: return
        val ime = btnImeSwitcher ?: return
        val lpAi = ai.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
        val lpIme = ime.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
        val unset = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET

        val sixPx = try {
            val d = view.resources.displayMetrics.density
            (6f * d + 0.5f).toInt()
        } catch (_: Throwable) {
            0
        }

        if (prefs.swapAiEditWithImeSwitcher) {
            lpAi.startToStart = unset
            lpAi.endToEnd = unset
            lpAi.endToStart = unset
            lpAi.startToEnd = R.id.btnSettings
            lpAi.marginStart = sixPx
            lpAi.marginEnd = 0
            lpAi.horizontalBias = 0.5f
            ai.layoutParams = lpAi

            lpIme.startToStart = unset
            lpIme.endToEnd = unset
            lpIme.startToEnd = R.id.btnAiEdit
            lpIme.endToStart = R.id.btnEnter
            lpIme.marginStart = 0
            lpIme.marginEnd = sixPx
            lpIme.horizontalBias = 1.0f
            ime.layoutParams = lpIme
        } else {
            lpIme.startToStart = unset
            lpIme.endToEnd = unset
            lpIme.endToStart = unset
            lpIme.startToEnd = R.id.btnSettings
            lpIme.marginStart = sixPx
            lpIme.marginEnd = 0
            lpIme.horizontalBias = 0.5f
            ime.layoutParams = lpIme

            lpAi.startToStart = unset
            lpAi.endToEnd = unset
            lpAi.startToEnd = R.id.btnImeSwitcher
            lpAi.endToStart = R.id.btnEnter
            lpAi.marginStart = 0
            lpAi.marginEnd = sixPx
            lpAi.horizontalBias = 1.0f
            ai.layoutParams = lpAi
        }
    }

    private fun applyKeyboardHeightScale(view: View?) {
        if (view == null) return
        val tier = try {
            prefs.keyboardHeightTier
        } catch (_: Throwable) {
            1
        }
        val scale = when (tier) {
            2 -> 1.15f
            3 -> 1.30f
            else -> 1.0f
        }

        fun dp(v: Float): Int {
            val d = view.resources.displayMetrics.density
            return (v * d + 0.5f).toInt()
        }

        // 应用底部间距（无论是否缩放都需要）
        try {
            val fl = view as? android.widget.FrameLayout
            if (fl != null) {
                val ps = fl.paddingStart
                val pe = fl.paddingEnd
                val pt = dp(8f * scale)
                val basePb = dp(12f * scale)
                // 添加用户设置的底部间距
                val extraPadding = try {
                    dp(prefs.keyboardBottomPaddingDp.toFloat())
                } catch (_: Throwable) {
                    0
                }
                val pb = basePb + extraPadding
                fl.setPaddingRelative(ps, pt, pe, pb)
            }
        } catch (_: Throwable) { }

        // 如果没有缩放，不需要调整按钮大小
        if (scale == 1.0f) return

        try {
            val topRow = view.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.rowTop)
            if (topRow != null) {
                val lp = topRow.layoutParams
                lp.height = dp(80f * scale)
                topRow.layoutParams = lp
            }
        } catch (_: Throwable) { }

        fun scaleSquareButton(id: Int) {
            try {
                val v = view.findViewById<View>(id) ?: return
                val lp = v.layoutParams
                lp.width = dp(40f * scale)
                lp.height = dp(40f * scale)
                v.layoutParams = lp
            } catch (_: Throwable) { }
        }

        val ids40 = intArrayOf(
            R.id.btnHide, R.id.btnPostproc, R.id.btnBackspace, R.id.btnPromptPicker,
            R.id.btnSettings, R.id.btnImeSwitcher, R.id.btnEnter, R.id.btnAiEdit,
            R.id.btnPunct1, R.id.btnPunct2, R.id.btnPunct3, R.id.btnPunct4
        )
        ids40.forEach { scaleSquareButton(it) }

        try {
            btnMic?.customSize = dp(72f * scale)
        } catch (_: Throwable) { }

        try {
            val tv = view.findViewById<TextView>(R.id.txtStatus)
            val lp = tv?.layoutParams as? android.widget.LinearLayout.LayoutParams
            if (lp != null) {
                lp.marginStart = dp(90f * scale)
                lp.marginEnd = dp(90f * scale)
                tv.layoutParams = lp
            }
        } catch (_: Throwable) { }
    }

    private fun resolveKeyboardSurfaceColor(from: View? = null): Int {
        val ctx = from?.context ?: this
        return try {
            MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurface, Color.BLACK)
        } catch (_: Throwable) {
            Color.BLACK
        }
    }

    @Suppress("DEPRECATION")
    private fun syncSystemBarsToKeyboardBackground(anchorView: View? = null) {
        val w = window?.window ?: return
        val color = resolveKeyboardSurfaceColor(anchorView)
        try {
            w.navigationBarColor = color
        } catch (_: Throwable) { }
        val isLight = try {
            ColorUtils.calculateLuminance(color) > 0.5
        } catch (_: Throwable) {
            false
        }
        val controller = WindowInsetsControllerCompat(w, anchorView ?: w.decorView)
        controller.isAppearanceLightNavigationBars = isLight
    }
}
