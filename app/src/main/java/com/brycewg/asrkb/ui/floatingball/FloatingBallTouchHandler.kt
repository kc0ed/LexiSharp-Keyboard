package com.brycewg.asrkb.ui.floatingball

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.brycewg.asrkb.store.Prefs

/**
 * 悬浮球触摸处理器
 * 封装复杂的触摸逻辑：拖动、长按、点击判定
 */
class FloatingBallTouchHandler(
    private val context: Context,
    private val prefs: Prefs,
    private val viewManager: FloatingBallViewManager,
    private val windowManager: WindowManager,
    private val listener: TouchEventListener
) {
    companion object {
        private const val TAG = "FloatingBallTouchHandler"
    }

    interface TouchEventListener {
        fun onSingleTap()
        fun onLongPress()
        fun onMoveStarted()
        fun onMoveEnded()
        fun onDragCancelled()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = dp(4)
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    // 触摸状态
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var isDragging = false
    private var longActionFired = false
    private var longPressPosted = false

    private val longPressRunnable = Runnable {
        longPressPosted = false
        longActionFired = true
        hapticFeedback()
        listener.onLongPress()
    }

    /** 创建触摸监听器 */
    fun createTouchListener(isMoveMode: () -> Boolean): View.OnTouchListener {
        return View.OnTouchListener { v, e ->
            val lp = viewManager.getLayoutParams() ?: return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleActionDown(lp, e, isMoveMode())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    handleActionMove(v, lp, e)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handleActionUp(v)
                }
                else -> false
            }
        }
    }

    /** 取消长按回调 */
    fun cancelLongPress() {
        if (longPressPosted) {
            try {
                handler.removeCallbacks(longPressRunnable)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to remove long press callback", e)
            }
            longPressPosted = false
        }
    }

    /** 清理资源 */
    fun cleanup() {
        cancelLongPress()
    }

    // ==================== 私有处理方法 ====================

    private fun handleActionDown(
        lp: WindowManager.LayoutParams,
        e: MotionEvent,
        isMoveMode: Boolean
    ) {
        moved = false
        isDragging = isMoveMode
        longActionFired = false
        downX = e.rawX
        downY = e.rawY
        startX = lp.x
        startY = lp.y

        // 移动模式下不触发长按
        if (!isMoveMode && !longPressPosted) {
            try {
                handler.postDelayed(longPressRunnable, longPressTimeout)
                longPressPosted = true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to post long press callback", e)
            }
        }
    }

    private fun handleActionMove(
        v: View,
        lp: WindowManager.LayoutParams,
        e: MotionEvent
    ): Boolean {
        val dx = (e.rawX - downX).toInt()
        val dy = (e.rawY - downY).toInt()

        if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
            moved = true
        }

        if (!isDragging) {
            // 移动超过阈值，取消长按
            if (moved && longPressPosted) {
                try {
                    handler.removeCallbacks(longPressRunnable)
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to remove long press callback", e)
                }
                longPressPosted = false
            }
            return true
        }

        // 拖动中：更新位置
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val root = viewManager.getBallView() ?: v
        val vw = if (root.width > 0) root.width else lp.width
        val vh = if (root.height > 0) root.height else lp.height
        val nx = (startX + dx).coerceIn(0, screenW - vw)
        val ny = (startY + dy).coerceIn(0, screenH - vh)
        lp.x = nx
        lp.y = ny
        viewManager.updateViewLayout(viewManager.getBallView() ?: v, lp)
        return true
    }

    private fun handleActionUp(v: View): Boolean {
        cancelLongPress()

        if (longActionFired) {
            // 已触发长按动作，抬起时不再处理点击或吸附
        } else if (isDragging) {
            // 移动模式下：若未移动则视为点击（用于退出移动模式）；若已移动则吸附
            if (!moved) {
                hapticFeedback()
                listener.onSingleTap()
            } else {
                try {
                    viewManager.animateSnapToEdge(v) {
                        listener.onMoveEnded()
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to animate snap to edge, falling back to instant snap", e)
                    viewManager.snapToEdge(v)
                    listener.onMoveEnded()
                }
            }
        } else if (!moved) {
            // 非移动模式的点按
            hapticFeedback()
            listener.onSingleTap()
        }

        moved = false
        isDragging = false
        longActionFired = false
        return true
    }

    private fun hapticFeedback() {
        try {
            if (prefs.micHapticEnabled) {
                viewManager.getBallView()?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to perform haptic feedback", e)
        }
    }

    private fun dp(v: Int): Int {
        val d = context.resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }
}
