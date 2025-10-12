package com.brycewg.asrkb.ui

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.brycewg.asrkb.store.Prefs

/**
 * 无障碍服务,用于悬浮球语音识别后将文本插入到当前焦点的输入框中
 */
class AsrAccessibilityService : AccessibilityService() {

    /**
     * 焦点输入框上下文：用于在悬浮球语音识别期间进行“前缀 + 预览 + 后缀”的拼接写入。
     * - prefix：选区前文本
     * - suffix：选区后文本
     */
    data class FocusContext(
        val prefix: String,
        val suffix: String
    )

    companion object {
        private const val TAG = "AsrAccessibilityService"
        const val ACTION_INSERT_TEXT = "com.brycewg.asrkb.action.INSERT_TEXT"
        const val EXTRA_TEXT = "text"

        private var instance: AsrAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null

        /**
         * 读取当前焦点可编辑节点的文本与选区，转换为前后缀快照；
         * 若无可编辑焦点，则返回 null（不进行预览）。
         */
        fun getCurrentFocusContext(): FocusContext? {
            val svc = instance ?: return null
            return try {
                val root = svc.rootInActiveWindow ?: return null
                val focused = svc.findFocusedEditableNode(root)
                if (focused != null) {
                    // 检查是否正在显示提示文本(hint text)，如果是则视为空字符串
                    val full = if (focused.isShowingHintText) {
                        ""
                    } else {
                        focused.text?.toString() ?: ""
                    }
                    val selStart = focused.textSelectionStart.takeIf { it >= 0 } ?: full.length
                    val selEnd = focused.textSelectionEnd.takeIf { it >= 0 } ?: full.length
                    val start = selStart.coerceIn(0, full.length)
                    val end = selEnd.coerceIn(0, full.length)
                    val s = minOf(start, end)
                    val e = maxOf(start, end)
                    @Suppress("DEPRECATION")
                    focused.recycle()
                    FocusContext(
                        prefix = full.substring(0, s),
                        suffix = full.substring(e, full.length)
                    )
                } else null
            } catch (_: Throwable) { null }
        }

        fun insertText(context: Context, text: String): Boolean {
            Log.d(TAG, "insertText called with: $text, service enabled: ${instance != null}")
            val service = instance
            if (service == null) {
                // 无障碍服务未启用,复制到剪贴板
                Log.w(TAG, "Accessibility service not enabled, copying to clipboard")
                copyToClipboard(context, text)
                Toast.makeText(context, context.getString(com.brycewg.asrkb.R.string.floating_asr_copied), Toast.LENGTH_SHORT).show()
                return false
            }

            return service.performInsertText(text)
        }

        /**
         * 静默写入文本：不弹 Toast、不复制到剪贴板（用于中间结果预览）。
         * 返回是否写入成功。
         */
        fun insertTextSilent(text: String): Boolean {
            val service = instance ?: return false
            return service.performInsertTextSilent(text)
        }
        
        private fun copyToClipboard(context: Context, text: String) {
            try {
                val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ASR Result", text)
                clipboard.setPrimaryClip(clip)
            } catch (_: Throwable) { }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
        // 刚连接时推送一次当前输入场景状态
        try { handler.post { tryDispatchImeVisibilityHint() } } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingCheck = false
    private var lastImeSceneActive: Boolean? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 现用于辅助判断“仅在输入法面板显示时显示悬浮球”的场景
        // 为避免频繁遍历树，做轻量节流
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            return
        }
        if (!pendingCheck) {
            pendingCheck = true
            handler.postDelayed({
                pendingCheck = false
                tryDispatchImeVisibilityHint()
            }, 120)
        }
    }

    private fun tryDispatchImeVisibilityHint() {
        val prefs = try { Prefs(this) } catch (_: Throwable) { null } ?: return
        if (!prefs.floatingSwitcherOnlyWhenImeVisible) return
        val anyFloatingEnabled = prefs.floatingSwitcherEnabled || prefs.floatingAsrEnabled
        if (!anyFloatingEnabled) return

        // 优先：是否存在输入法窗口（更接近“键盘显示”）
        val active = isImeWindowVisible() || hasEditableFocusNow()
        val prev = lastImeSceneActive
        if (prev == null || prev != active) {
            lastImeSceneActive = active
            try {
                val action = if (active) FloatingImeSwitcherService.ACTION_HINT_IME_VISIBLE
                             else FloatingImeSwitcherService.ACTION_HINT_IME_HIDDEN
                // 通知输入法切换悬浮球
                try {
                    val i1 = Intent(this, FloatingImeSwitcherService::class.java).apply { this.action = action }
                    startService(i1)
                } catch (_: Throwable) { }
                // 通知语音识别悬浮球
                try {
                    val i2 = Intent(this, FloatingAsrService::class.java).apply { this.action = action }
                    startService(i2)
                } catch (_: Throwable) { }
            } catch (_: Throwable) { }
        }
    }

    override fun onInterrupt() {
        // 服务被中断
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_INSERT_TEXT) {
            val text = intent.getStringExtra(EXTRA_TEXT)
            if (!text.isNullOrEmpty()) {
                performInsertText(text)
            }
        }
        return START_NOT_STICKY
    }

    private fun performInsertText(text: String): Boolean {
        try {
            Log.d(TAG, "performInsertText called")
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                Log.w(TAG, "Root node is null")
                copyToClipboard(this, text)
                Toast.makeText(this, getString(com.brycewg.asrkb.R.string.floating_asr_copied), Toast.LENGTH_SHORT).show()
                return false
            }

            val focusedNode = findFocusedEditableNode(rootNode)

            if (focusedNode != null) {
                // 找到可编辑的焦点节点,插入文本
                Log.d(TAG, "Found focused editable node, inserting text")
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                // Note: recycle() is deprecated in API 31+, but we keep it for compatibility
                @Suppress("DEPRECATION")
                focusedNode.recycle()

                if (success) {
                    Log.d(TAG, "Text inserted successfully")
                    Toast.makeText(this, getString(com.brycewg.asrkb.R.string.floating_asr_inserted), Toast.LENGTH_SHORT).show()
                    return true
                } else {
                    Log.w(TAG, "Failed to insert text")
                }
            } else {
                Log.w(TAG, "No focused editable node found")
            }

            // 未找到焦点节点或插入失败,复制到剪贴板
            copyToClipboard(this, text)
            Toast.makeText(this, getString(com.brycewg.asrkb.R.string.floating_asr_copied), Toast.LENGTH_SHORT).show()
            return false
        } catch (e: Throwable) {
            // 发生错误,复制到剪贴板
            Log.e(TAG, "Error inserting text", e)
            copyToClipboard(this, text)
            Toast.makeText(this, getString(com.brycewg.asrkb.R.string.floating_asr_copied), Toast.LENGTH_SHORT).show()
            return false
        }
    }

    // 静默版本：仅尝试设置文本，不做 Toast/复制
    private fun performInsertTextSilent(text: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false
            val focusedNode = findFocusedEditableNode(rootNode)
            if (focusedNode != null) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                @Suppress("DEPRECATION")
                focusedNode.recycle()
                success
            } else false
        } catch (_: Throwable) { false }
    }

    private fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 首先尝试查找具有焦点的可编辑节点
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            return focused
        }
        @Suppress("DEPRECATION")
        focused?.recycle()

        // 递归查找可编辑且有焦点的节点
        return findEditableNodeRecursive(root)
    }

    private fun findEditableNodeRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNodeRecursive(child)
            if (result != null) {
                @Suppress("DEPRECATION")
                child.recycle()
                return result
            }
            @Suppress("DEPRECATION")
            child.recycle()
        }

        return null
    }

    private fun hasEditableFocusNow(): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val ok = focused.isEditable || (focused.className?.toString()?.contains("EditText", true) == true)
                @Suppress("DEPRECATION")
                focused.recycle()
                if (ok) return true
            }
            // 回退：遍历查找是否存在可编辑且聚焦的节点
            val found = findEditableNodeRecursive(root)
            @Suppress("DEPRECATION")
            root.recycle()
            found != null
        } catch (_: Throwable) { false }
    }

    private fun isImeWindowVisible(): Boolean {
        return try {
            val ws = windows ?: return false
            var visible = false
            for (w in ws) {
                try {
                    if (w?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        // 兼容性判定：活跃/聚焦任一为真则认为可见
                        if (w.isActive || w.isFocused) {
                            visible = true
                            break
                        }
                    }
                } catch (_: Throwable) { }
            }
            visible
        } catch (_: Throwable) { false }
    }
}
