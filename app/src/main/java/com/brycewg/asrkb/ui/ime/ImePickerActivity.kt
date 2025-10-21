package com.brycewg.asrkb.ui.ime

import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity

/**
 * 透明过渡页：置前后立刻唤起系统输入法选择器，然后自行 finish。
 * 目的：避免从后台 Service 直接调用 showInputMethodPicker() 被系统忽略的问题。
 *
 * **实现原理**：
 * 1. 使用透明主题的 Activity 作为中转页面，确保有前台窗口上下文
 * 2. 依赖 onWindowFocusChanged(true) 回调来确保 Activity 已完全置前且获得焦点
 * 3. 使用 Handler.post() 将 showInputMethodPicker() 调用延迟到下一帧，避免时序问题
 * 4. 100ms 延迟 finish() 确保选择器弹窗已完全显示后再关闭透明 Activity
 *
 * **为什么需要窗口焦点和延迟**：
 * - Android 系统要求 showInputMethodPicker() 必须从具有窗口焦点的前台组件调用
 * - 直接在 onCreate/onResume 中调用可能因窗口未完全渲染而被系统忽略
 * - onWindowFocusChanged(true) 是最可靠的"已置前"信号
 * - Handler.post() 确保在 UI 线程空闲时执行，避免与系统窗口管理冲突
 * - finish() 延迟避免过早销毁导致选择器弹窗异常关闭
 */
class ImePickerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "ImePickerActivity"

        /**
         * 延迟关闭 Activity 的时间（毫秒）
         * 确保输入法选择器弹窗完全显示后再销毁透明 Activity
         */
        private const val FINISH_DELAY_MS = 100L
    }

    /**
     * 标记是否已经触发过输入法选择器
     * 防止窗口焦点多次变化导致重复调用
     */
    private var launched = false

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 无需设置布局，保持透明
        Log.d(TAG, "onCreate: Transparent IME picker activity created")
    }

    /**
     * 监听窗口焦点变化，在获得焦点后唤起输入法选择器
     *
     * **为什么在这里调用而不是 onCreate/onResume**：
     * - onCreate: 窗口尚未创建，无法保证系统接受 showInputMethodPicker() 调用
     * - onResume: Activity 可见但可能未获得焦点，某些设备上会被忽略
     * - onWindowFocusChanged(true): 窗口已完全渲染且获得焦点，是最可靠的时机
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        // 仅在获得焦点且未启动过时触发
        if (!hasFocus || launched) return

        launched = true
        Log.d(TAG, "Window gained focus, launching IME picker")

        // 使用 Handler.post() 延迟到下一帧执行，确保窗口状态完全稳定
        handler.post {
            try {
                val imm = getSystemService(InputMethodManager::class.java)
                if (imm != null) {
                    imm.showInputMethodPicker()
                    Log.d(TAG, "IME picker shown successfully")
                } else {
                    Log.w(TAG, "InputMethodManager is null, cannot show picker")
                }
            } catch (e: Exception) {
                // 捕获所有可能的异常（SecurityException, IllegalStateException 等）
                // 不再兜底跳系统设置，避免多次界面跳转造成割裂
                Log.e(TAG, "Failed to show IME picker", e)
            } finally {
                // 延迟关闭透明 Activity，确保选择器弹窗已完全显示
                scheduleFinish()
            }
        }
    }

    /**
     * 延迟关闭 Activity 并禁用过渡动画
     */
    private fun scheduleFinish() {
        handler.postDelayed({
            Log.d(TAG, "Finishing transparent activity")
            finish()

            // 禁用关闭动画，避免用户看到透明 Activity 的退出效果
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ 使用新 API
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }, FINISH_DELAY_MS)
    }

    /**
     * Activity 进入后台后自动销毁
     *
     * **生命周期优化**：
     * - 当用户在输入法选择器中选择后，本 Activity 会进入 onPause 状态
     * - 此时透明 Activity 已完成使命，应立即销毁以释放资源
     * - 避免长时间驻留在后台任务栈中
     */
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: Activity paused, scheduling cleanup")

        // 如果已经触发过选择器，在进入后台时立即 finish
        // 这比依赖延迟回调更及时，避免资源浪费
        if (launched) {
            handler.removeCallbacksAndMessages(null) // 清理未执行的延迟任务
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理 Handler 回调，防止内存泄漏
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "onDestroy: Activity destroyed")
    }
}
