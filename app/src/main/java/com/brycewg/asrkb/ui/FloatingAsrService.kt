package com.brycewg.asrkb.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        asrEngine = buildEngineForCurrentMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, floatingAsrEnabled=${prefs.floatingAsrEnabled}")
        when (intent?.action) {
            ACTION_SHOW -> showBall()
            ACTION_HIDE -> hideBall()
            else -> showBall()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideBall()
        asrEngine?.stop()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBall() {
        Log.d(TAG, "showBall called: floatingAsrEnabled=${prefs.floatingAsrEnabled}, hasOverlay=${hasOverlayPermission()}")
        if (!prefs.floatingAsrEnabled || !hasOverlayPermission()) {
            Log.w(TAG, "Cannot show ball: permission or setting issue")
            hideBall()
            return
        }

        if (ballView != null) {
            Log.d(TAG, "Ball already shown")
            return
        }
        
        val view = LayoutInflater.from(this).inflate(R.layout.floating_asr_ball, null, false)
        ballIcon = view.findViewById(R.id.ballIcon)
        ballProgress = view.findViewById(R.id.ballProgress)
        
        ballIcon?.setOnClickListener { onBallClick() }
        attachDrag(view)
        
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
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
            windowManager.addView(view, params)
            ballView = view
            lp = params
            applyBallAlpha()
            Log.d(TAG, "Ball view added successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add ball view", e)
        }
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

    private fun onBallClick() {
        if (isRecording) {
            // 停止录音
            stopRecording()
        } else {
            // 开始录音
            startRecording()
        }
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
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to show toast: $message", e)
            }
        }
    }

    private fun updateBallState() {
        handler.post {
            Log.d(TAG, "updateBallState: recording=$isRecording, processing=$isProcessing")
            if (isRecording) {
                // 录音中:背景变浅红色,顺时针旋转动画
                ballIcon?.setColorFilter(Color.parseColor("#FFCDD2"))
                startProgressAnimation(false)
            } else if (isProcessing) {
                // 处理中:背景恢复,逆时针旋转动画
                ballIcon?.clearColorFilter()
                startProgressAnimation(true)
            } else {
                // 空闲:背景恢复,停止动画
                ballIcon?.clearColorFilter()
                stopProgressAnimation()
            }
        }
    }

    private fun startProgressAnimation(reverse: Boolean) {
        stopProgressAnimation()

        val progress = ballProgress ?: return
        progress.visibility = View.VISIBLE

        // 使用旋转动画
        progressAnimator = ObjectAnimator.ofFloat(progress, "rotation", 0f, if (reverse) -360f else 360f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        Log.d(TAG, "Progress animation started, reverse=$reverse")
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

            // 插入文本
            if (finalText.isNotEmpty()) {
                Log.d(TAG, "Inserting text: $finalText")
                AsrAccessibilityService.insertText(this@FloatingAsrService, finalText)
                showToast(getString(R.string.floating_asr_completed))
            } else {
                Log.w(TAG, "Final text is empty")
            }
        }
    }

    override fun onError(message: String) {
        Log.e(TAG, "onError called: $message")
        serviceScope.launch {
            isRecording = false
            isProcessing = false
            updateBallState()
            showToast(getString(R.string.floating_asr_error, message))
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
                VolcFileAsrEngine(this, serviceScope, prefs, this) { }
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
                    if (!moved) {
                        v.performClick()
                    } else {
                        try { snapToEdge(v) } catch (_: Throwable) { }
                    }
                    moved = false
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
        val vw = if (v.width > 0) v.width else dp(56)
        val vh = if (v.height > 0) v.height else dp(56)
        val margin = dp(0)

        val centerX = p.x + vw / 2
        val targetX = if (centerX < screenW / 2) margin else (screenW - vw - margin)
        val minY = margin
        val maxY = (screenH - vh - margin).coerceAtLeast(minY)
        val targetY = p.y.coerceIn(minY, maxY)

        p.x = targetX
        p.y = targetY
        try { windowManager.updateViewLayout(v, p) } catch (_: Throwable) { }
    }

    private fun dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }
}

