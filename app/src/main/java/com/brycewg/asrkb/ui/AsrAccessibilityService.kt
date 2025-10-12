package com.brycewg.asrkb.ui

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 无障碍服务,用于悬浮球语音识别后将文本插入到当前焦点的输入框中
 */
class AsrAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AsrAccessibilityService"
        const val ACTION_INSERT_TEXT = "com.brycewg.asrkb.action.INSERT_TEXT"
        const val EXTRA_TEXT = "text"

        private var instance: AsrAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null

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
        
        private fun copyToClipboard(context: Context, text: String) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ASR Result", text)
                clipboard.setPrimaryClip(clip)
            } catch (_: Throwable) { }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要监听事件,仅用于插入文本
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
}

