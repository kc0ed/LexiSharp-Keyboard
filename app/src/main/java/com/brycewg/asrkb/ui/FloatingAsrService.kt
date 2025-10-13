package com.brycewg.asrkb.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.StreamingAsrEngine
import com.brycewg.asrkb.asr.VolcFileAsrEngine
import com.brycewg.asrkb.asr.SiliconFlowFileAsrEngine
import com.brycewg.asrkb.asr.ElevenLabsFileAsrEngine
import com.brycewg.asrkb.asr.OpenAiFileAsrEngine
import com.brycewg.asrkb.asr.DashscopeFileAsrEngine
import com.brycewg.asrkb.asr.GeminiFileAsrEngine
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrAccessibilityService.FocusContext
import com.brycewg.asrkb.asr.VolcStreamAsrEngine
import com.brycewg.asrkb.asr.SonioxFileAsrEngine
import com.brycewg.asrkb.asr.SonioxStreamAsrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.core.graphics.toColorInt

/**
 * 悬浮球语音识别服务
 * 在其他输入法激活时也能通过悬浮球进行语音识别
 */
class FloatingAsrService : Service(), StreamingAsrEngine.Listener {

    companion object {
        private const val TAG = "FloatingAsrService"
        const val ACTION_SHOW = "com.brycewg.asrkb.action.FLOATING_ASR_SHOW"
        const val ACTION_HIDE = "com.brycewg.asrkb.action.FLOATING_ASR_HIDE"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private var ballView: View? = null
    private var ballIcon: ImageView? = null
    private var ballProgress: ProgressBar? = null
    private var lp: WindowManager.LayoutParams? = null
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var asrEngine: StreamingAsrEngine? = null
    private val postproc = LlmPostProcessor()
    
    private var isRecording = false
    private var isProcessing = false
    private var progressAnimator: ObjectAnimator? = null
    private var currentToast: Toast? = null
    // 预览会话上下文：记录焦点输入框前后缀与上次中间结果，便于动态预览
    private var focusContext: FocusContext? = null
    private var lastPartialForPreview: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private var imeVisible: Boolean = false
    private val hintReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FloatingImeSwitcherService.ACTION_HINT_IME_VISIBLE -> {
                    imeVisible = true
                    updateVisibilityByPref()
                }
                FloatingImeSwitcherService.ACTION_HINT_IME_HIDDEN -> {
                    imeVisible = false
                    updateVisibilityByPref()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        asrEngine = buildEngineForCurrentMode()
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(FloatingImeSwitcherService.ACTION_HINT_IME_VISIBLE)
                addAction(FloatingImeSwitcherService.ACTION_HINT_IME_HIDDEN)
            }
            registerReceiver(hintReceiver, filter)
        } catch (_: Throwable) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, floatingAsrEnabled=${prefs.floatingAsrEnabled}")
        when (intent?.action) {
            ACTION_SHOW -> updateVisibilityByPref()
            ACTION_HIDE -> hideBall()
            FloatingImeSwitcherService.ACTION_HINT_IME_VISIBLE -> {
                imeVisible = true
                updateVisibilityByPref()
            }
            FloatingImeSwitcherService.ACTION_HINT_IME_HIDDEN -> {
                imeVisible = false
                updateVisibilityByPref()
            }
            else -> showBall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideBall()
        asrEngine?.stop()
        serviceScope.cancel()
        try { unregisterReceiver(hintReceiver) } catch (_: Throwable) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBall() {
        Log.d(TAG, "showBall called: floatingAsrEnabled=${prefs.floatingAsrEnabled}, hasOverlay=${hasOverlayPermission()}")
        if (!prefs.floatingAsrEnabled || !hasOverlayPermission()) {
            Log.w(TAG, "Cannot show ball: permission or setting issue")
            hideBall()
            return
        }

        if (prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible) {
            Log.d(TAG, "Pref requires IME visible; hiding for now")
            hideBall()
            return
        }

        if (ballView != null) {
            Log.d(TAG, "Ball already shown; updating alpha/size")
            applyBallAlpha()
            applyBallSize()
            return
        }
        
        val view = LayoutInflater.from(this).inflate(R.layout.floating_asr_ball, null, false)
        ballIcon = view.findViewById(R.id.ballIcon)
        ballProgress = view.findViewById(R.id.ballProgress)
        
        ballIcon?.setOnClickListener { onBallClick() }
        // 在根视图与图标上都绑定拖动监听；内部统一更新根视图位置，避免对子视图误设 WindowManager.LayoutParams
        attachDrag(view)
        ballIcon?.let { attachDrag(it) }
        
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val size = try { prefs.floatingBallSizeDp } catch (_: Throwable) { 56 }
        val params = WindowManager.LayoutParams(
            dp(size), dp(size),
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
            windowManager.addView(view, params)
            ballView = view
            lp = params
            applyBallAlpha()
            applyBallSize()
            Log.d(TAG, "Ball view added successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add ball view", e)
        }
    }

    private fun updateVisibilityByPref() {
        if (!prefs.floatingAsrEnabled) {
            hideBall(); return
        }
        if (prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible) {
            hideBall(); return
        }
        showBall()
    }

    private fun hideBall() {
        val v = ballView ?: return
        try { windowManager.removeView(v) } catch (_: Throwable) { }
        ballView = null
        lp = null
    }

    private fun applyBallAlpha() {
        val a = try { prefs.floatingSwitcherAlpha } catch (_: Throwable) { 1.0f }
        ballView?.alpha = a
    }

    private fun applyBallSize() {
        val v = ballView ?: return
        val p = lp ?: return
        val size = try { prefs.floatingBallSizeDp } catch (_: Throwable) { 56 }
        p.width = dp(size)
        p.height = dp(size)
        try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) { }
    }

    private fun onBallClick() {
        // 检查无障碍权限
        if (!AsrAccessibilityService.isEnabled()) {
            Log.w(TAG, "Accessibility service not enabled")
            showToast(getString(R.string.toast_need_accessibility_perm))
            // 跳转到无障碍设置
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to open accessibility settings", e)
            }
            return
        }

        if (isRecording) {
            // 停止录音
            stopRecording()
        } else {
            // 开始录音
            startRecording()
        }
    }

    private fun invokeImePicker() {
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            if (!isOurImeEnabled(imm)) {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
                return
            }
            val intent = Intent(this, ImePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            try {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivity(intent)
            } catch (_: Throwable) { }
        }
    }

    private fun isOurImeEnabled(imm: InputMethodManager?): Boolean {
        val list = try { imm?.enabledInputMethodList } catch (_: Throwable) { null }
        if (list?.any { it.packageName == packageName } == true) return true
        return try {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            val id = "$packageName/.ime.AsrKeyboardService"
            enabled?.contains(id) == true || (enabled?.split(':')?.any { it.startsWith(packageName) } == true)
        } catch (_: Throwable) { false }
    }

    private fun startRecording() {
        Log.d(TAG, "startRecording called")
        // 检查权限
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "No record audio permission")
            showToast(getString(R.string.hint_need_permission))
            return
        }

        if (!prefs.hasAsrKeys()) {
            Log.w(TAG, "No ASR keys configured")
            showToast(getString(R.string.hint_need_keys))
            return
        }

        isRecording = true
        updateBallState()
        showToast(getString(R.string.floating_asr_recording))

        asrEngine = buildEngineForCurrentMode()
        Log.d(TAG, "ASR engine created: ${asrEngine?.javaClass?.simpleName}")
        // 记录开始录音时的焦点上下文（prefix/suffix），用于最终写入时避免覆盖原有内容。
        // 流式与非流式都需要此上下文；仅预览（onPartial）在流式下才会使用。
        focusContext = AsrAccessibilityService.getCurrentFocusContext()
        lastPartialForPreview = null
        asrEngine?.start()
    }

    private fun stopRecording() {
        Log.d(TAG, "stopRecording called")
        isRecording = false
        asrEngine?.stop()

        if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
            // 需要后处理,显示处理中状态
            isProcessing = true
            updateBallState()
            showToast(getString(R.string.floating_asr_recognizing))
        } else {
            updateBallState()
            showToast(getString(R.string.floating_asr_recognizing))
        }
    }

    private fun showToast(message: String) {
        handler.post {
            try {
                // 取消之前的 Toast
                currentToast?.cancel()
                // 创建并显示新的 Toast
                currentToast = Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT)
                currentToast?.show()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to show toast: $message", e)
            }
        }
    }

    private fun updateBallState() {
        handler.post {
            Log.d(TAG, "updateBallState: recording=$isRecording, processing=$isProcessing")
            if (isRecording) {
                // 录音中:图标变红色,顺时针旋转动画
                ballIcon?.setColorFilter("#F44336".toColorInt())
            } else if (isProcessing) {
                // 处理中:图标变蓝色,逆时针旋转动画
                ballIcon?.setColorFilter("#2196F3".toColorInt())
            } else {
                // 空闲:图标恢复默认颜色,停止动画
                ballIcon?.clearColorFilter()
            }
        }
    }

    private fun stopProgressAnimation() {
        progressAnimator?.cancel()
        progressAnimator = null
        ballProgress?.visibility = View.GONE
        ballProgress?.rotation = 0f
    }

    override fun onFinal(text: String) {
        Log.d(TAG, "onFinal called with text: $text")
        serviceScope.launch {
            var finalText = text

            if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
                // AI后处理
                Log.d(TAG, "Starting AI post-processing")
                isProcessing = true
                updateBallState()
                showToast(getString(R.string.floating_asr_processing))

                val raw = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(text) else text
                finalText = try {
                    postproc.process(raw, prefs).ifBlank { raw }
                } catch (e: Throwable) {
                    Log.e(TAG, "Post-processing failed", e)
                    raw
                }

                if (prefs.trimFinalTrailingPunct) {
                    finalText = trimTrailingPunctuation(finalText)
                }
                Log.d(TAG, "Post-processing completed: $finalText")
            } else if (prefs.trimFinalTrailingPunct) {
                finalText = trimTrailingPunctuation(text)
            }

            isProcessing = false
            isRecording = false
            updateBallState()

            // 插入文本：优先使用开始录音时的焦点快照；若为空，再尝试在提交时获取一次快照，避免覆盖
            if (finalText.isNotEmpty()) {
                val ctx = focusContext ?: AsrAccessibilityService.getCurrentFocusContext()
                val toWrite = if (ctx != null) ctx.prefix + finalText + ctx.suffix else finalText
                Log.d(TAG, "Inserting text: $toWrite (previewCtx=${ctx != null})")
                AsrAccessibilityService.insertText(this@FloatingAsrService, toWrite)
                showToast(getString(R.string.floating_asr_completed))
            } else {
                Log.w(TAG, "Final text is empty")
            }
            // 结束会话，清理状态
            focusContext = null
            lastPartialForPreview = null
        }
    }

    override fun onPartial(text: String) {
        // 仅在录音中且存在焦点可编辑框时进行动态预览；
        // 无焦点时按需忽略（不预览）。
        if (!isRecording) return
        val ctx = focusContext ?: return
        if (text.isEmpty()) return
        if (lastPartialForPreview == text) return
        val toWrite = ctx.prefix + text + ctx.suffix
        Log.d(TAG, "onPartial preview: $text")
        // 切到主线程，静默写入，避免 Toast/Looper 异常与刷屏
        serviceScope.launch {
            AsrAccessibilityService.insertTextSilent(toWrite)
        }
        lastPartialForPreview = text
    }

    override fun onError(message: String) {
        Log.e(TAG, "onError called: $message")
        serviceScope.launch {
            isRecording = false
            isProcessing = false
            updateBallState()
            showToast(getString(R.string.floating_asr_error, message))
            focusContext = null
            lastPartialForPreview = null
        }
    }

    private fun trimTrailingPunctuation(s: String): String {
        if (s.isEmpty()) return s
        val regex = Regex("[\\p{Punct}，。！？；、：]+$")
        return s.replace(regex, "")
    }

    private fun buildEngineForCurrentMode(): StreamingAsrEngine? {
        return when (prefs.asrVendor) {
            AsrVendor.Volc -> if (prefs.hasVolcKeys()) {
                if (prefs.volcStreamingEnabled) {
                    VolcStreamAsrEngine(this, serviceScope, prefs, this)
                } else {
                    VolcFileAsrEngine(this, serviceScope, prefs, this) { }
                }
            } else null
            AsrVendor.SiliconFlow -> if (prefs.hasSfKeys()) {
                SiliconFlowFileAsrEngine(this, serviceScope, prefs, this) { }
            } else null
            AsrVendor.ElevenLabs -> if (prefs.hasElevenKeys()) {
                ElevenLabsFileAsrEngine(this, serviceScope, prefs, this) { }
            } else null
            AsrVendor.OpenAI -> if (prefs.hasOpenAiKeys()) {
                OpenAiFileAsrEngine(this, serviceScope, prefs, this) { }
            } else null
            AsrVendor.DashScope -> if (prefs.hasDashKeys()) {
                DashscopeFileAsrEngine(this, serviceScope, prefs, this) { }
            } else null
            AsrVendor.Gemini -> if (prefs.hasGeminiKeys()) {
                GeminiFileAsrEngine(this, serviceScope, prefs, this) { }
            } else null
            AsrVendor.Soniox -> if (prefs.hasSonioxKeys()) {
                if (prefs.sonioxStreamingEnabled) {
                    SonioxStreamAsrEngine(this, serviceScope, prefs, this)
                } else {
                    SonioxFileAsrEngine(this, serviceScope, prefs, this) { }
                }
            } else null
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private var edgeAnimator: ValueAnimator? = null

    private fun attachDrag(target: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var isDragging = false
        var longActionFired = false // 是否已触发2s长按动作（呼出输入法选择器）
        val touchSlop = dp(4)
        val tinyMoveSlop = dp(6) // “极小范围移动”阈值
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val longHoldSwitchTimeout = 2000L // 2 秒长按触发输入法选择器
        var longPressPosted = false
        var switchImePosted = false
        val longPressRunnable = Runnable {
            isDragging = true
            longPressPosted = false
        }
        val switchImeRunnable = Runnable {
            switchImePosted = false
            longActionFired = true
            // 触发输入法选择器
            invokeImePicker()
        }

        target.setOnTouchListener { v, e ->
            val p = lp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    edgeAnimator?.cancel()
                    moved = false
                    isDragging = false
                    longActionFired = false
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    if (!longPressPosted) {
                        handler.postDelayed(longPressRunnable, longPressTimeout)
                        longPressPosted = true
                    }
                    if (!switchImePosted) {
                        handler.postDelayed(switchImeRunnable, longHoldSwitchTimeout)
                        switchImePosted = true
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) moved = true

                    // 若在2秒长按触发前移动超过“极小阈值”，则取消输入法选择器触发
                    if ((kotlin.math.abs(dx) > tinyMoveSlop || kotlin.math.abs(dy) > tinyMoveSlop) && switchImePosted) {
                        handler.removeCallbacks(switchImeRunnable)
                        switchImePosted = false
                    }

                    if (!isDragging) {
                        if (moved && longPressPosted) {
                            handler.removeCallbacks(longPressRunnable)
                            longPressPosted = false
                        }
                        return@setOnTouchListener true
                    }

                    val dm = resources.displayMetrics
                    val screenW = dm.widthPixels
                    val screenH = dm.heightPixels
                    val root = ballView ?: v
                    val vw = if (root.width > 0) root.width else p.width
                    val vh = if (root.height > 0) root.height else p.height
                    val nx = (startX + dx).coerceIn(0, screenW - vw)
                    val ny = (startY + dy).coerceIn(0, screenH - vh)
                    p.x = nx
                    p.y = ny
                    try { windowManager.updateViewLayout(ballView ?: v, p) } catch (_: Throwable) { }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (longPressPosted) {
                        handler.removeCallbacks(longPressRunnable)
                        longPressPosted = false
                    }
                    if (switchImePosted) {
                        handler.removeCallbacks(switchImeRunnable)
                        switchImePosted = false
                    }
                    if (longActionFired) {
                        // 已触发2秒长按动作，抬起时不再处理点击或吸附
                    } else if (!isDragging && !moved) {
                        // 由根视图处理点击，避免子视图消费导致拖动无法触发
                        onBallClick()
                    } else if (isDragging) {
                        try { animateSnapToEdge(v) } catch (_: Throwable) { snapToEdge(v) }
                    }
                    moved = false
                    isDragging = false
                    longActionFired = false
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(v: View) {
        val p = lp ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val def = try { prefs.floatingBallSizeDp } catch (_: Throwable) { 56 }
        val root = ballView ?: v
        val vw = if (root.width > 0) root.width else dp(def)
        val vh = if (root.height > 0) root.height else dp(def)
        val margin = dp(0)

        val centerX = p.x + vw / 2
        val targetX = if (centerX < screenW / 2) margin else (screenW - vw - margin)
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val targetY = p.y.coerceIn(minY, maxY)

        p.x = targetX
        p.y = targetY
        try { windowManager.updateViewLayout(ballView ?: v, p) } catch (_: Throwable) { }
    }

    private fun animateSnapToEdge(v: View) {
        val p = lp ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val def = try { prefs.floatingBallSizeDp } catch (_: Throwable) { 56 }
        val root = ballView ?: v
        val vw = if (root.width > 0) root.width else dp(def)
        val vh = if (root.height > 0) root.height else dp(def)
        val margin = dp(0)

        val centerX = p.x + vw / 2
        val targetX = if (centerX < screenW / 2) margin else (screenW - vw - margin)
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val targetY = p.y.coerceIn(minY, maxY)

        val startX = p.x
        val startY = p.y
        val dx = targetX - startX
        val dy = targetY - startY

        edgeAnimator?.cancel()
        edgeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                p.x = (startX + dx * f).toInt()
                p.y = (startY + dy * f).toInt()
                try { windowManager.updateViewLayout(ballView ?: v, p) } catch (_: Throwable) { }
            }
            start()
        }
    }

    private fun dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }
}
