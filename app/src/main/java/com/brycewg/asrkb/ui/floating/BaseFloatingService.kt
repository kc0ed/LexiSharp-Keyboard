package com.brycewg.asrkb.ui.floating

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.brycewg.asrkb.store.Prefs

/**
 * 悬浮球服务的抽象基类，提供通用的窗口管理、拖动、吸附和位置保存功能
 */
abstract class BaseFloatingService : Service() {

    companion object {
        private const val TAG = "BaseFloatingService"
    }

    protected lateinit var windowManager: WindowManager
    protected lateinit var prefs: Prefs
    protected val handler = Handler(Looper.getMainLooper())

    // 悬浮球视图与布局参数
    protected var ballView: View? = null
    protected var lp: WindowManager.LayoutParams? = null

    // 轮盘菜单与供应商选择面板
    protected var radialMenuView: View? = null
    protected var vendorMenuView: View? = null

    // 移动模式与交互保护标志
    protected var moveModeEnabled: Boolean = false
    protected var touchActiveGuard: Boolean = false

    // 边缘吸附动画
    private var edgeAnimator: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        hideRadialMenu()
        hideVendorMenu()
        removeBall()
    }

    /**
     * 子类实现：创建悬浮球视图
     */
    protected abstract fun createBallView(): View

    /**
     * 子类实现：获取悬浮球默认大小（DP）
     */
    protected abstract fun getDefaultBallSizeDp(): Int

    /**
     * 子类实现：处理悬浮球点击事件
     */
    protected abstract fun onBallClick()

    /**
     * 子类实现：处理长按显示轮盘菜单
     */
    protected abstract fun onShowRadialMenu()

    /**
     * 确保悬浮球已添加到窗口
     */
    protected fun ensureBall() {
        if (ballView != null) return

        val view = createBallView()
        attachDrag(view)

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val size = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get ball size from prefs", e)
            getDefaultBallSizeDp()
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

        // 恢复上次位置或使用默认值
        restoreBallPosition(params)

        try {
            windowManager.addView(view, params)
            ballView = view
            lp = params
            Log.d(TAG, "Ball view added successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add ball view", e)
        }
    }

    /**
     * 从窗口移除悬浮球
     */
    protected fun removeBall() {
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

    /**
     * 应用悬浮球透明度
     */
    protected fun applyBallAlpha() {
        val alpha = try {
            prefs.floatingSwitcherAlpha
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get ball alpha from prefs", e)
            1.0f
        }
        ballView?.alpha = alpha
    }

    /**
     * 应用悬浮球大小
     */
    protected fun applyBallSize() {
        val v = ballView ?: return
        val p = lp ?: return
        val size = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get ball size from prefs", e)
            getDefaultBallSizeDp()
        }
        p.width = dp(size)
        p.height = dp(size)
        try {
            windowManager.updateViewLayout(v, p)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update ball size", e)
        }
    }

    /**
     * 为视图附加拖动与长按监听
     */
    protected fun attachDrag(target: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var isDragging = false
        var longActionFired = false
        val touchSlop = dp(4)
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var longPressPosted = false
        val longPressRunnable = Runnable {
            longPressPosted = false
            longActionFired = true
            onShowRadialMenu()
        }

        target.setOnTouchListener { v, e ->
            val p = lp ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    edgeAnimator?.cancel()
                    moved = false
                    isDragging = moveModeEnabled
                    longActionFired = false
                    touchActiveGuard = true
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    if (!moveModeEnabled && !longPressPosted) {
                        handler.postDelayed(longPressRunnable, longPressTimeout)
                        longPressPosted = true
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
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
                    try {
                        windowManager.updateViewLayout(root, p)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to update view layout during drag", e)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (longPressPosted) {
                        handler.removeCallbacks(longPressRunnable)
                        longPressPosted = false
                    }
                    if (longActionFired) {
                        // 已触发长按，不处理点击
                    } else if (isDragging) {
                        if (!moved) {
                            hapticTap(v)
                            onBallClick()
                        } else {
                            try {
                                animateSnapToEdge(v)
                            } catch (e: Throwable) {
                                Log.e(TAG, "Failed to animate snap to edge", e)
                                snapToEdge(v)
                            }
                        }
                    } else if (!moved) {
                        hapticTap(v)
                        onBallClick()
                    }
                    moved = false
                    isDragging = false
                    longActionFired = false
                    touchActiveGuard = false
                    onTouchEnd()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 子类可重写：触摸结束后的回调
     */
    protected open fun onTouchEnd() {
        // 默认实现为空
    }

    /**
     * 隐藏轮盘菜单
     */
    protected fun hideRadialMenu() {
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
     * 隐藏供应商选择菜单
     */
    protected fun hideVendorMenu() {
        vendorMenuView?.let { v ->
            try {
                cancelAllAnimations(v)
                windowManager.removeView(v)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to hide vendor menu", e)
            }
            vendorMenuView = null
        }
    }

    /**
     * 递归取消视图树中的所有动画
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
     * 吸附到边缘（无动画）
     */
    protected fun snapToEdge(v: View) {
        val p = lp ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val def = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get ball size for snap", e)
            getDefaultBallSizeDp()
        }
        val root = ballView ?: v
        val vw = if (root.width > 0) root.width else dp(def)
        val vh = if (root.height > 0) root.height else dp(def)
        val margin = dp(0)

        val bottomSnapThreshold = dp(64)
        val bottomY = (screenH - vh - margin)
        val bottomDist = bottomY - p.y

        val targetX: Int
        val targetY: Int
        if (bottomDist <= bottomSnapThreshold) {
            targetY = bottomY
            val minX = margin
            val maxX = (screenW - vw - margin).coerceAtLeast(minX)
            targetX = p.x.coerceIn(minX, maxX)
        } else {
            val centerX = p.x + vw / 2
            targetX = if (centerX < screenW / 2) margin else (screenW - vw - margin)
            val minY = margin
            val maxY = (screenH - vh - margin).coerceAtLeast(minY)
            targetY = p.y.coerceIn(minY, maxY)
        }

        p.x = targetX
        p.y = targetY
        try {
            windowManager.updateViewLayout(root, p)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update view layout during snap", e)
        }
        persistBallPosition()
    }

    /**
     * 吸附到边缘（带动画）
     */
    protected fun animateSnapToEdge(v: View) {
        val p = lp ?: return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val def = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get ball size for animated snap", e)
            getDefaultBallSizeDp()
        }
        val root = ballView ?: v
        val vw = if (root.width > 0) root.width else dp(def)
        val vh = if (root.height > 0) root.height else dp(def)
        val margin = dp(0)

        val bottomSnapThreshold = dp(64)
        val bottomY = (screenH - vh - margin)
        val bottomDist = bottomY - p.y

        val targetX: Int
        val targetY: Int
        if (bottomDist <= bottomSnapThreshold) {
            targetY = bottomY
            val minX = margin
            val maxX = (screenW - vw - margin).coerceAtLeast(minX)
            targetX = p.x.coerceIn(minX, maxX)
        } else {
            val centerX = p.x + vw / 2
            targetX = if (centerX < screenW / 2) margin else (screenW - vw - margin)
            val minY = margin
            val maxY = (screenH - vh - margin).coerceAtLeast(minY)
            targetY = p.y.coerceIn(minY, maxY)
        }

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
                    windowManager.updateViewLayout(root, p)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to update view layout during animation", e)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    persistBallPosition()
                }
            })
            start()
        }
    }

    /**
     * 持久化悬浮球位置
     */
    protected fun persistBallPosition() {
        val p = lp ?: return
        try {
            prefs.floatingBallPosX = p.x
            prefs.floatingBallPosY = p.y
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist ball position", e)
        }
    }

    /**
     * 恢复悬浮球位置
     */
    private fun restoreBallPosition(params: WindowManager.LayoutParams) {
        try {
            val sx = prefs.floatingBallPosX
            val sy = prefs.floatingBallPosY
            val dm = resources.displayMetrics
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
            Log.e(TAG, "Failed to restore ball position", e)
            params.x = dp(12)
            params.y = dp(180)
        }
    }

    /**
     * 判断悬浮球是否在屏幕左侧
     */
    protected fun isBallOnLeft(): Boolean {
        val p = lp ?: return true
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val vw = ballView?.width ?: p.width
        val centerX = p.x + vw / 2
        return centerX < w / 2
    }

    /**
     * 获取悬浮球中心点坐标
     */
    protected fun getBallCenterSnapshot(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val sizeDp = try {
            prefs.floatingBallSizeDp
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get ball size for center calculation", e)
            getDefaultBallSizeDp()
        }
        val vw = (ballView?.width?.takeIf { it > 0 }) ?: (lp?.width ?: dp(sizeDp))
        val vh = (ballView?.height?.takeIf { it > 0 }) ?: (lp?.height ?: dp(sizeDp))
        val px = lp?.x ?: run {
            try {
                prefs.floatingBallPosX
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to get ball X position", e)
                (dm.widthPixels - vw) / 2
            }
        }
        val py = lp?.y ?: run {
            try {
                prefs.floatingBallPosY
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to get ball Y position", e)
                (dm.heightPixels - vh) / 2
            }
        }
        return (px + vw / 2) to (py + vh / 2)
    }

    /**
     * 获取当前 UI 透明度
     */
    protected fun currentUiAlpha(): Float = try {
        prefs.floatingSwitcherAlpha
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to get UI alpha", e)
        1f
    }

    /**
     * DP 转 PX
     */
    protected fun dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }

    /**
     * 触发触觉反馈
     */
    protected fun hapticTap(view: View?) {
        try {
            if (prefs.micHapticEnabled) {
                view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to perform haptic feedback", e)
        }
    }
}
