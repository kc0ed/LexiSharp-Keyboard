package com.brycewg.asrkb.ui.floatingball

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.ContextThemeWrapper
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.widgets.ProcessingSpinnerView
import com.google.android.material.color.DynamicColors

/**
 * 悬浮球视图管理器
 * 负责管理 WindowManager、视图创建、位置管理和动画
 */
class FloatingBallViewManager(
    private val context: Context,
    private val prefs: Prefs,
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "FloatingBallViewManager"
    }

    private var ballView: View? = null
    private var ballIcon: ImageView? = null
    private var ballProgress: ProgressBar? = null
    private var processingSpinner: ProcessingSpinnerView? = null
    private var ripple1: View? = null
    private var ripple2: View? = null
    private var ripple3: View? = null
    private var lp: WindowManager.LayoutParams? = null

    // 动画
    private var pulseAnimator: ValueAnimator? = null
    private var partialFeedbackAnimator: ValueAnimator? = null
    private var rippleAnimators: MutableList<Animator> = mutableListOf()
    private var edgeAnimator: ValueAnimator? = null
    private var errorVisualActive: Boolean = false
    private var completionResetPosted: Boolean = false
    private var monetContext: Context? = null

    /** 获取悬浮球视图 */
    fun getBallView(): View? = ballView

    /** 获取布局参数 */
    fun getLayoutParams(): WindowManager.LayoutParams? = lp

    /** 显示悬浮球 */
    fun showBall(onClickListener: (View) -> Unit, onTouchListener: View.OnTouchListener): Boolean {
        if (ballView != null) {
            applyBallAlpha()
            applyBallSize()
            return true
        }

        try {
            val themedCtx = ContextThemeWrapper(context, R.style.Theme_ASRKeyboard)
            val dynCtx = DynamicColors.wrapContextIfAvailable(themedCtx)
            monetContext = dynCtx

            val view = LayoutInflater.from(dynCtx).inflate(R.layout.floating_asr_ball, null, false)
            ballIcon = view.findViewById(R.id.ballIcon)
            ballProgress = view.findViewById(R.id.ballProgress)
            ripple1 = view.findViewById(R.id.ripple1)
            ripple2 = view.findViewById(R.id.ripple2)
            ripple3 = view.findViewById(R.id.ripple3)
            val ballContainer = try {
                view.findViewById<android.widget.FrameLayout>(R.id.ballContainer)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to find ballContainer", e)
                null
            }

            // 获取 Monet 动态颜色
            val colorSecondaryContainer = getMonetColor(
                com.google.android.material.R.attr.colorSecondaryContainer,
                0xFF6200EE.toInt()
            )
            val colorSecondary = getMonetColor(
                com.google.android.material.R.attr.colorSecondary,
                colorSecondaryContainer
            )

            // 设置 ProgressBar 颜色
            setupProgressBarColor(colorSecondary)

            // 设置波纹背景
            setupRippleBackgrounds(colorSecondary)

            // 设置处理中旋转动画
            setupProcessingSpinner(ballContainer, colorSecondary)

            // 绑定点击和拖动监听
            ballIcon?.setOnClickListener(onClickListener)
            view.setOnTouchListener(onTouchListener)
            ballIcon?.setOnTouchListener(onTouchListener)

            // 创建 WindowManager.LayoutParams
            val params = createWindowLayoutParams()

            // 添加视图
            windowManager.addView(view, params)
            ballView = view
            lp = params
            applyBallAlpha()
            applyBallSize()
            Log.d(TAG, "Ball view added successfully")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add ball view", e)
            return false
        }
    }

    /** 隐藏悬浮球 */
    fun hideBall() {
        val v = ballView ?: return
        try {
            persistBallPosition()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist ball position", e)
        }
        try {
            windowManager.removeView(v)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to remove ball view", e)
        }
        ballView = null
        lp = null
    }

    /** 应用悬浮球透明度 */
    fun applyBallAlpha() {
        val alpha = try {
            prefs.floatingSwitcherAlpha
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get alpha, using default", e)
            1.0f
        }
        ballView?.alpha = alpha
    }

    /** 应用悬浮球大小 */
    fun applyBallSize() {
        val v = ballView ?: return
        val p = lp ?: return
        val size = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get size, using default", e)
            56
        }
        p.width = dp(size)
        p.height = dp(size)
        try {
            windowManager.updateViewLayout(v, p)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update view layout", e)
        }
    }

    /** 更新悬浮球状态显示 */
    fun updateStateVisual(state: FloatingBallState) {
        // 清除颜色滤镜（错误视觉期间不清除）
        if (!errorVisualActive) ballIcon?.clearColorFilter()

        when (state) {
            is FloatingBallState.Recording -> {
                ballProgress?.visibility = View.GONE
                processingSpinner?.visibility = View.GONE
                stopProcessingSpinner()
                startRippleAnimation()
            }
            is FloatingBallState.Processing -> {
                stopRippleAnimation()
                ballProgress?.visibility = View.GONE
                processingSpinner?.visibility = View.VISIBLE
                startProcessingSpinner()
            }
            is FloatingBallState.Error -> {
                stopRippleAnimation()
                ballProgress?.visibility = View.GONE
                processingSpinner?.visibility = View.GONE
                stopProcessingSpinner()
                playErrorShakeAnimation()
            }
            else -> {
                // Idle, MoveMode
                stopRippleAnimation()
                ballProgress?.visibility = View.GONE
                processingSpinner?.visibility = View.GONE
                stopProcessingSpinner()
                resetIconScale()
            }
        }
    }

    /** 显示完成对勾 */
    fun showCompletionTick(durationMs: Long = 1000L) {
        val icon = ballIcon ?: return
        try {
            icon.setImageResource(R.drawable.ic_check)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to set check icon", e)
            return
        }
        if (!completionResetPosted) {
            completionResetPosted = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    ballIcon?.setImageResource(R.drawable.ic_mic)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to reset icon", e)
                }
                completionResetPosted = false
            }, durationMs)
        }
    }

    /** 吸附到边缘（带动画） */
    fun animateSnapToEdge(v: View, onComplete: (() -> Unit)? = null) {
        val p = lp ?: return
        val (targetX, targetY) = calculateSnapTarget(v)

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
                try {
                    windowManager.updateViewLayout(ballView ?: v, p)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to update layout during snap animation", e)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistBallPosition()
                    onComplete?.invoke()
                }
            })
            start()
        }
    }

    /** 吸附到边缘（无动画） */
    fun snapToEdge(v: View) {
        val p = lp ?: return
        val (targetX, targetY) = calculateSnapTarget(v)
        p.x = targetX
        p.y = targetY
        try {
            windowManager.updateViewLayout(ballView ?: v, p)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update layout during snap", e)
        }
        persistBallPosition()
    }

    /** 获取悬浮球中心点 */
    fun getBallCenterSnapshot(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val sizeDp = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get ball size, using default", e)
            56
        }
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (lp?.width ?: dp(sizeDp))
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (lp?.height ?: dp(sizeDp))
        val px = lp?.x ?: run {
            try {
                prefs.floatingBallPosX
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get ball X position, using default", e)
                (dm.widthPixels - vw) / 2
            }
        }
        val py = lp?.y ?: run {
            try {
                prefs.floatingBallPosY
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to get ball Y position, using default", e)
                (dm.heightPixels - vh) / 2
            }
        }
        return (px + vw / 2) to (py + vh / 2)
    }

    /** 更新窗口位置 */
    fun updateViewLayout(v: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(v, params)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update view layout", e)
        }
    }

    /** 清理所有动画 */
    fun cleanup() {
        stopPulseAnimation()
        stopRippleAnimation()
        stopProcessingSpinner()
        partialFeedbackAnimator?.cancel()
        partialFeedbackAnimator = null
        edgeAnimator?.cancel()
        edgeAnimator = null
    }

    // ==================== 私有辅助方法 ====================

    private fun getMonetColor(attr: Int, fallback: Int): Int {
        val ctx = monetContext ?: context
        return try {
            val tv = android.util.TypedValue()
            val theme = ctx.theme
            if (theme.resolveAttribute(attr, tv, true)) tv.data else fallback
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to resolve theme attribute $attr, using fallback", e)
            fallback
        }
    }

    private fun setupProgressBarColor(color: Int) {
        ballProgress?.let { pb ->
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    pb.indeterminateTintList = android.content.res.ColorStateList.valueOf(color)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to set progress bar tint", e)
            }
        }
    }

    private fun setupRippleBackgrounds(color: Int) {
        val rippleStrokeColor = applyAlpha(color, 1.0f)
        val strokeWidthPx = dp(4)
        val dashWidthPx = dp(8).toFloat()
        val dashGapPx = dp(6).toFloat()
        listOf(ripple1, ripple2, ripple3).forEach { ripple ->
            ripple?.let {
                try {
                    val drawable = android.graphics.drawable.GradientDrawable()
                    drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                    drawable.setColor(android.graphics.Color.TRANSPARENT)
                    drawable.setStroke(strokeWidthPx, rippleStrokeColor, dashWidthPx, dashGapPx)
                    it.background = drawable
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to set ripple background", e)
                }
            }
        }
    }

    private fun setupProcessingSpinner(ballContainer: android.widget.FrameLayout?, color: Int) {
        try {
            if (processingSpinner == null && ballContainer != null) {
                processingSpinner = ProcessingSpinnerView(context).apply {
                    isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setSpinnerColor(applyAlpha(color, 0.6f))
                    setStrokeWidth(dp(4).toFloat())
                    setSweepAngle(110f)
                    visibility = View.GONE
                }
                val lpSpinner = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER }
                ballContainer.addView(processingSpinner, lpSpinner)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to setup processing spinner", e)
        }
    }

    private fun createWindowLayoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val size = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get ball size, using default", e)
            56
        }
        val params = WindowManager.LayoutParams(
            dp(size), dp(size),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        // 恢复上次位置
        try {
            val sx = prefs.floatingBallPosX
            val sy = prefs.floatingBallPosY
            val dm = context.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val vw = params.width
            val vh = params.height
            if (sx >= 0 && sy >= 0) {
                params.x = sx.coerceIn(0, (screenW - vw).coerceAtLeast(0))
                params.y = sy.coerceIn(0, (screenH - vh).coerceAtLeast(0))
            } else {
                params.x = dp(12)
                params.y = dp(180)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to restore ball position, using default", e)
            params.x = dp(12)
            params.y = dp(180)
        }

        return params
    }

    private fun calculateSnapTarget(v: View): Pair<Int, Int> {
        val p = lp ?: return 0 to 0
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val def = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get ball size for snap calculation", e)
            56
        }
        val root = ballView ?: v
        val vw = if (root.width > 0) root.width else dp(def)
        val vh = if (root.height > 0) root.height else dp(def)
        val margin = dp(0)

        val bottomSnapThreshold = dp(64)
        val bottomY = (screenH - vh - margin)
        val bottomDist = bottomY - p.y

        return if (bottomDist <= bottomSnapThreshold) {
            val targetY = bottomY
            val minX = margin
            val maxX = (screenW - vw - margin).coerceAtLeast(minX)
            val targetX = p.x.coerceIn(minX, maxX)
            targetX to targetY
        } else {
            val centerX = p.x + vw / 2
            val targetX = if (centerX < screenW / 2) margin else (screenW - vw - margin)
            val minY = margin
            val maxY = (screenH - vh - margin).coerceAtLeast(minY)
            val targetY = p.y.coerceIn(minY, maxY)
            targetX to targetY
        }
    }

    private fun persistBallPosition() {
        val p = lp ?: return
        try {
            prefs.floatingBallPosX = p.x
            prefs.floatingBallPosY = p.y
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist ball position", e)
        }
    }

    // ==================== 动画方法 ====================

    private fun startPulseAnimation() {
        stopPulseAnimation()
        val icon = ballIcon ?: return

        pulseAnimator = ValueAnimator.ofFloat(0.85f, 1.15f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                icon.scaleX = scale
                icon.scaleY = scale
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private fun startRippleAnimation() {
        stopRippleAnimation()

        val ripples = listOf(ripple1, ripple2, ripple3)
        ripples.forEachIndexed { index, ripple ->
            ripple ?: return@forEachIndexed

            val delay = index * 500L

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                startDelay = delay
                interpolator = android.view.animation.LinearInterpolator()

                addUpdateListener { anim ->
                    val progress = anim.animatedValue as Float
                    val scale = 0.10f + progress * 0.90f
                    ripple.scaleX = scale
                    ripple.scaleY = scale
                    val alpha = (1f - progress)
                    ripple.alpha = alpha
                    ripple.visibility = if (alpha > 0.01f) View.VISIBLE else View.INVISIBLE
                }

                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationRepeat(animation: Animator) {
                        ripple.scaleX = 0.10f
                        ripple.scaleY = 0.10f
                    }
                })

                start()
            }

            rippleAnimators.add(animator)
        }
    }

    private fun stopRippleAnimation() {
        rippleAnimators.forEach { it.cancel() }
        rippleAnimators.clear()
        ripple1?.visibility = View.INVISIBLE
        ripple2?.visibility = View.INVISIBLE
        ripple3?.visibility = View.INVISIBLE
        ripple1?.alpha = 0f
        ripple2?.alpha = 0f
        ripple3?.alpha = 0f
    }

    private fun startProcessingSpinner() {
        try {
            processingSpinner?.start()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to start processing spinner", e)
        }
    }

    private fun stopProcessingSpinner() {
        try {
            processingSpinner?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to stop processing spinner", e)
        }
    }

    private fun resetIconScale() {
        ballIcon?.let {
            it.scaleX = 1.0f
            it.scaleY = 1.0f
        }
    }

    private fun playErrorShakeAnimation() {
        val icon = ballIcon ?: return

        errorVisualActive = true
        try {
            icon.animate().cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel icon animation", e)
        }
        icon.setColorFilter("#FF1744".toColorInt())

        val shake = ValueAnimator.ofFloat(0f, -16f, 16f, -12f, 12f, -6f, 6f, 0f).apply {
            duration = 500
            addUpdateListener { anim ->
                icon.translationX = (anim.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    icon.translationX = 0f
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            icon.clearColorFilter()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Failed to clear color filter", e)
                        }
                        errorVisualActive = false
                    }, 1000)
                }
            })
            start()
        }
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun dp(v: Int): Int {
        val d = context.resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }
}
