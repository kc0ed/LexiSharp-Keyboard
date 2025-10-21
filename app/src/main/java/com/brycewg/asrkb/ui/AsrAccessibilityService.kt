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
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.ui.floating.FloatingAsrService
import com.brycewg.asrkb.ui.floating.FloatingImeSwitcherService

/**
 * 无障碍服务,用于悬浮球语音识别后将文本插入到当前焦点的输入框中
 */
class AsrAccessibilityService : AccessibilityService() {

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    /**
     * 焦点输入框上下文：用于在悬浮球语音识别期间进行"前缀 + 预览 + 后缀"的拼接写入。
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
            return svc.withFocusedEditableNode { focused ->
                val text = focused.text?.toString()
                val full = if (isNodeShowingHint(focused, text)) "" else (text ?: "")

                val selStart = focused.textSelectionStart.takeIf { it >= 0 } ?: full.length
                val selEnd = focused.textSelectionEnd.takeIf { it >= 0 } ?: full.length
                val start = selStart.coerceIn(0, full.length)
                val end = selEnd.coerceIn(0, full.length)
                val s = minOf(start, end)
                val e = maxOf(start, end)

                FocusContext(
                    prefix = full.substring(0, s),
                    suffix = full.substring(e, full.length)
                )
            }
        }

        /**
         * 判断节点当前是否显示提示文本（而非用户输入内容）。
         * 三重检查：
         * 1. 标准 isShowingHintText API
         * 2. 文本与 hintText 相同
         * 3. 文本与 contentDescription 相同 (Telegram 等应用的非标准实现)
         */
        private fun isNodeShowingHint(node: AccessibilityNodeInfo, text: String?): Boolean {
            if (node.isShowingHintText) return true
            if (text.isNullOrEmpty()) return false

            val hint = try {
                node.hintText?.toString()
            } catch (e: Throwable) {
                Log.e(TAG, "Error getting hint text from node", e)
                null
            }

            val desc = try {
                node.contentDescription?.toString()
            } catch (e: Throwable) {
                Log.e(TAG, "Error getting content description from node", e)
                null
            }

            return (text == hint) || (text == desc)
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

        /**
         * 获取当前活动窗口的包名（尽力而为）。
         */
        fun getActiveWindowPackage(): String? {
            val service = instance ?: return null
            return try {
                // 优先 rootInActiveWindow
                val root = service.rootInActiveWindow
                val pkg = root?.packageName?.toString()
                if (!pkg.isNullOrEmpty()) return pkg

                // 回退：遍历窗口，取 active/focused 的 root 包名
                val ws = service.windows
                if (ws != null) {
                    for (w in ws) {
                        try {
                            if (w == null) continue
                            if (w.isActive || w.isFocused) {
                                val r = w.root
                                val p = r?.packageName?.toString()
                                if (!p.isNullOrEmpty()) return p
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error getting package from window", e)
                        }
                    }
                }
                null
            } catch (e: Throwable) {
                Log.e(TAG, "Error getting active window package", e)
                null
            }
        }

        /**
         * 静默粘贴文本：临时放入剪贴板并对焦点输入框执行 PASTE 动作。
         * 成功返回 true，不弹 Toast、不修改用户可见状态。
         */
        fun pasteTextSilent(text: String): Boolean {
            val service = instance ?: return false
            return service.performPasteTextSilent(text)
        }

        /**
         * 选中全部并以粘贴方式替换为给定文本（静默）。
         * - 备份并恢复剪贴板；
         * - 尝试设置选区为[0, 当前文本长度]，失败则回退到大范围；
         * - 粘贴完成后尝试把光标移到末尾。
         */
        fun selectAllAndPasteSilent(text: String): Boolean {
            val service = instance ?: return false
            return service.performSelectAllAndPasteSilent(text)
        }

        /**
         * 静默设置当前焦点输入框的选区（通常用于把光标移到指定位置）。
         * - start/end 以字符索引计（闭区间左端、开区间右端），若仅需设置光标请传相同值。
         * - 返回是否设置成功（目标节点存在且支持 ACTION_SET_SELECTION）。
         */
        fun setSelectionSilent(start: Int, end: Int = start): Boolean {
            val service = instance ?: return false
            return service.performSetSelectionSilent(start, end)
        }

        private fun copyToClipboard(context: Context, text: String) {
            try {
                val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ASR Result", text)
                clipboard.setPrimaryClip(clip)
            } catch (e: Throwable) {
                Log.e(TAG, "Error copying to clipboard", e)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
        // 刚连接时推送一次当前输入场景状态
        try {
            handler.post { tryDispatchImeVisibilityHint() }
        } catch (e: Throwable) {
            Log.e(TAG, "Error posting initial IME visibility hint", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingCheck = false
    private var lastImeSceneActive: Boolean? = null
    private var lastEditableFocusAt: Long = 0L
    private val holdAfterFocusMs: Long = 600L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 现用于辅助判断"仅在输入法面板显示时显示悬浮球"的场景
        // 为避免频繁遍历树，做轻量节流
        if (event == null) return

        if (!isRelevantEventType(event.eventType)) {
            return
        }

        if (!pendingCheck) {
            pendingCheck = true
            handler.postDelayed({
                pendingCheck = false
                tryDispatchImeVisibilityHint()
            }, 70)
        }
    }

    /**
     * 判断事件类型是否与输入法可见性检测相关。
     */
    private fun isRelevantEventType(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
               eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
               eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
               eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
               eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
               eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }

    private fun tryDispatchImeVisibilityHint() {
        val prefs = try {
            Prefs(this)
        } catch (e: Throwable) {
            Log.e(TAG, "Error getting preferences", e)
            null
        } ?: return

        if (!shouldCheckImeVisibility(prefs)) return

        val now = System.currentTimeMillis()
        val hasFocus = hasEditableFocusNow()
        if (hasFocus) lastEditableFocusAt = now

        val active = determineImeSceneActive(now, prefs)
        updateImeVisibilityState(active)
    }

    /**
     * 判断是否需要检测输入法可见性。
     */
    private fun shouldCheckImeVisibility(prefs: Prefs): Boolean {
        if (!prefs.floatingSwitcherOnlyWhenImeVisible) return false
        val anyFloatingEnabled = prefs.floatingSwitcherEnabled || prefs.floatingAsrEnabled
        return anyFloatingEnabled
    }

    /**
     * 根据当前状态判断输入法场景是否活跃。
     */
    private fun determineImeSceneActive(now: Long, prefs: Prefs): Boolean {
        // 兼容选项：仅当开关开启且当前前台包名命中规则时，采用"按 IME 包名检测"
        val prefsCompat = try {
            prefs.floatingImeVisibilityCompatEnabled
        } catch (e: Throwable) {
            Log.e(TAG, "Error getting compat preference", e)
            false
        }

        val compatPkgs = try {
            prefs.getFloatingImeVisibilityCompatPackageRules()
        } catch (e: Throwable) {
            Log.e(TAG, "Error getting compat package rules", e)
            emptyList()
        }

        // 默认检测：是否存在输入法窗口（更接近"键盘显示"）
        val mWindow = isImeWindowVisible()
        // 兼容检测：是否能检测到 IME 窗口的包名
        val mPkg = isImePackageDetected()
        val hold = (now - lastEditableFocusAt <= holdAfterFocusMs)

        val activePkg = getActiveWindowPackage()
        val isCompatTarget = isCompatibilityTargetApp(prefsCompat, activePkg, compatPkgs)
        val compatVisible = if (isCompatTarget) mPkg else mWindow

        return if (isCompatTarget) compatVisible else (compatVisible || hold)
    }

    /**
     * 判断当前应用是否为兼容模式目标应用。
     */
    private fun isCompatibilityTargetApp(
        compatEnabled: Boolean,
        activePkg: String?,
        compatPkgs: List<String>
    ): Boolean {
        return compatEnabled &&
               !activePkg.isNullOrEmpty() &&
               compatPkgs.any { it == activePkg }
    }

    /**
     * 更新输入法可见性状态，并在状态变化时通知相关服务。
     */
    private fun updateImeVisibilityState(active: Boolean) {
        val prev = lastImeSceneActive
        if (prev == null || prev != active) {
            lastImeSceneActive = active
            notifyFloatingServices(active)
        }
    }

    /**
     * 通知悬浮服务输入法可见性变化。
     */
    private fun notifyFloatingServices(visible: Boolean) {
        try {
            val action = if (visible) {
                FloatingImeSwitcherService.ACTION_HINT_IME_VISIBLE
            } else {
                FloatingImeSwitcherService.ACTION_HINT_IME_HIDDEN
            }

            // 通知输入法切换悬浮球
            try {
                val i1 = Intent(this, FloatingImeSwitcherService::class.java).apply {
                    this.action = action
                }
                startService(i1)
            } catch (e: Throwable) {
                Log.e(TAG, "Error notifying FloatingImeSwitcherService", e)
            }

            // 通知语音识别悬浮球
            try {
                val i2 = Intent(this, FloatingAsrService::class.java).apply {
                    this.action = action
                }
                startService(i2)
            } catch (e: Throwable) {
                Log.e(TAG, "Error notifying FloatingAsrService", e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error notifying floating services", e)
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

            val target = findFocusedEditableNode(rootNode)

            if (target != null) {
                Log.d(TAG, "Found editable/focusable node; trying ACTION_SET_TEXT")
                // 先尝试 ACTION_SET_TEXT 直接写入
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                // 保障焦点在目标上
                try {
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error focusing target node", e)
                }

                val setOk = try {
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error performing ACTION_SET_TEXT", e)
                    false
                }

                if (setOk) {
                    @Suppress("DEPRECATION")
                    target.recycle()
                    return true
                }

                Log.w(TAG, "ACTION_SET_TEXT failed; try clipboard paste fallback")
                val pasteOk = performPasteFallback(target, text)
                @Suppress("DEPRECATION")
                target.recycle()
                if (pasteOk) {
                    return true
                }
            } else {
                Log.w(TAG, "No focused editable-like node found")
            }

            // 兜底：复制剪贴板
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
        return withFocusedEditableNode { focusedNode ->
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } ?: false
    }

    // 静默粘贴：使用剪贴板 + ACTION_PASTE，尽量不干扰用户当前剪贴板内容
    private fun performPasteTextSilent(text: String): Boolean {
        return withFocusedEditableNode { focusedNode ->
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val previous = try {
                clipboard.primaryClip
            } catch (e: Throwable) {
                Log.e(TAG, "Error getting primary clip", e)
                null
            }

            val clip = ClipData.newPlainText("ASR Paste", text)
            clipboard.setPrimaryClip(clip)

            val ok = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)

            // 恢复之前的剪贴板（尽最大努力）；若没有之前内容则尝试清空
            restoreClipboard(clipboard, previous)

            ok
        } ?: false
    }

    /**
     * 恢复剪贴板内容或清空。
     */
    private fun restoreClipboard(clipboard: ClipboardManager, previous: ClipData?) {
        try {
            if (previous != null) {
                clipboard.setPrimaryClip(previous)
            } else {
                try {
                    clipboard.clearPrimaryClip()
                } catch (e: Throwable) {
                    Log.e(TAG, "Error clearing primary clip", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error restoring clipboard", e)
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            } catch (e2: Throwable) {
                Log.e(TAG, "Error setting empty clip", e2)
            }
        }
    }

    // 静默设置选区：不提示、不改剪贴板
    private fun performSetSelectionSilent(start: Int, end: Int): Boolean {
        return withFocusedEditableNode { target ->
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
            try {
                target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            } catch (e: Throwable) {
                Log.e(TAG, "Error setting selection", e)
                false
            }
        } ?: false
    }

    // 选中全部并以"粘贴"方式替换文本（静默）
    private fun performSelectAllAndPasteSilent(text: String): Boolean {
        return withFocusedEditableNode { target ->
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val previous = try {
                clipboard.primaryClip
            } catch (e: Throwable) {
                Log.e(TAG, "Error getting primary clip for select all", e)
                null
            }

            val len = try {
                val full = target.text?.toString() ?: ""
                full.length
            } catch (e: Throwable) {
                Log.e(TAG, "Error getting text length", e)
                0
            }

            // 先尽量聚焦
            try {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (e: Throwable) {
                Log.e(TAG, "Error focusing for select all", e)
            }

            // 选中全部（若失败，后续直接尝试粘贴也可能覆盖）
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, if (len > 0) len else Int.MAX_VALUE)
            }
            try {
                target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            } catch (e: Throwable) {
                Log.e(TAG, "Error selecting all text", e)
            }

            // 设置剪贴板并粘贴
            val clip = ClipData.newPlainText("ASR PasteAll", text)
            clipboard.setPrimaryClip(clip)
            var ok = try {
                target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (e: Throwable) {
                Log.e(TAG, "Error pasting text", e)
                false
            }

            if (!ok) {
                // 回退：长按后再粘贴一次
                try {
                    target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error long clicking", e)
                }
                handler.postDelayed({
                    try {
                        target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error pasting after long click", e)
                    }
                }, 100)
            }

            // 粘贴后把光标尽量移到末尾
            if (ok) {
                val endSel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                }
                try {
                    target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, endSel)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error setting cursor to end", e)
                }
            }

            // 恢复剪贴板（或清空）
            restoreClipboard(clipboard, previous)

            ok
        } ?: false
    }

    /**
     * 高阶函数：查找焦点可编辑节点、执行操作、回收节点并统一处理异常。
     * @param action 对找到的节点执行的操作，返回操作结果
     * @return 操作成功返回结果，失败或未找到节点返回 null
     */
    private fun <T> withFocusedEditableNode(action: (AccessibilityNodeInfo) -> T): T? {
        return try {
            val rootNode = rootInActiveWindow ?: return null
            val focusedNode = findFocusedEditableNode(rootNode)
            if (focusedNode != null) {
                try {
                    val result = action(focusedNode)
                    result
                } finally {
                    @Suppress("DEPRECATION")
                    focusedNode.recycle()
                }
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in withFocusedEditableNode", e)
            null
        }
    }

    private fun findFocusedEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1) 优先取焦点节点且满足"可编辑或类名含 EditText 或支持 setText/paste"
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { f ->
            if (isEditableLike(f)) return f
            @Suppress("DEPRECATION") f.recycle()
        }
        // 2) 递归寻找"已聚焦且可编辑样式"的节点
        return findEditableNodeRecursive(root)
    }

    private fun findEditableNodeRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && isEditableLike(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNodeRecursive(child)
            if (result != null) {
                @Suppress("DEPRECATION") child.recycle()
                return result
            }
            @Suppress("DEPRECATION") child.recycle()
        }
        return null
    }

    private fun isEditableLike(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: ""
        if (cls.contains("EditText", ignoreCase = true)) return true
        if (nodeHasAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) return true
        if (nodeHasAction(node, AccessibilityNodeInfo.ACTION_PASTE)) return true
        return false
    }

    private fun nodeHasAction(node: AccessibilityNodeInfo, action: Int): Boolean {
        return try {
            val list = node.actionList
            list?.any { it.id == action } == true
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking node actions", e)
            false
        }
    }

    private fun performPasteFallback(target: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            // 将文本放入剪贴板（带备份并恢复）
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val previous = try {
                clipboard.primaryClip
            } catch (e: Throwable) {
                Log.e(TAG, "Error getting clip for paste fallback", e)
                null
            }

            val clip = ClipData.newPlainText("ASR PasteFallback", text)
            try {
                clipboard.setPrimaryClip(clip)
            } catch (e: Throwable) {
                Log.e(TAG, "Error setting clip for paste fallback", e)
            }

            // 确保焦点在输入框
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            // 若可长按，尝试长按以唤出粘贴菜单（部分应用要求）
            target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            // 尝试直接执行粘贴动作（多数输入框支持）
            var ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!ok) {
                // 延迟再试一次，等待长按菜单弹出
                handler.postDelayed({
                    target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }, 120)
                ok = true // 假设延迟后会成功
            }

            // 恢复剪贴板
            try {
                if (previous != null) {
                    clipboard.setPrimaryClip(previous)
                } else {
                    clipboard.clearPrimaryClip()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error restoring clipboard in paste fallback", e)
            }

            ok
        } catch (e: Throwable) {
            Log.e(TAG, "Error in paste fallback", e)
            false
        }
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
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking editable focus", e)
            false
        }
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
                } catch (e: Throwable) {
                    Log.e(TAG, "Error checking window visibility", e)
                }
            }
            visible
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking IME window visibility", e)
            false
        }
    }

    private fun isImePackageDetected(): Boolean {
        return try {
            val ws = windows ?: return false
            for (w in ws) {
                try {
                    if (w?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        val pkg = w.root?.packageName?.toString()
                        if (!pkg.isNullOrEmpty()) return true
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Error checking IME package", e)
                }
            }
            false
        } catch (e: Throwable) {
            Log.e(TAG, "Error detecting IME package", e)
            false
        }
    }
}
