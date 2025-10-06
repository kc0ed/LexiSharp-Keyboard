package com.example.asrkeyboard.ime

import android.Manifest
import android.content.Intent
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Vibrator
import android.view.LayoutInflater
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.example.asrkeyboard.R
import com.example.asrkeyboard.asr.StreamingAsrEngine
import com.example.asrkeyboard.asr.VolcFileAsrEngine
import com.example.asrkeyboard.asr.LlmPostProcessor
import com.example.asrkeyboard.store.Prefs
import com.example.asrkeyboard.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.android.material.color.MaterialColors

class AsrKeyboardService : InputMethodService(), StreamingAsrEngine.Listener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var asrEngine: StreamingAsrEngine? = null
    private lateinit var prefs: Prefs
    private var rootView: View? = null

    private var btnMic: FloatingActionButton? = null
    private var btnSettings: ImageButton? = null
    private var btnEnter: ImageButton? = null
    private var btnPostproc: ImageButton? = null
    private var btnBackspace: ImageButton? = null
    private var btnHide: ImageButton? = null
    private var btnImeSwitcher: ImageButton? = null
    private var txtStatus: TextView? = null
    private var committedStableLen: Int = 0
    private var postproc: LlmPostProcessor = LlmPostProcessor()
    private var micLongPressStarted: Boolean = false
    private var micLongPressPending: Boolean = false
    private var micLongPressRunnable: Runnable? = null

    // Backspace gesture state
    private var backspaceStartX: Float = 0f
    private var backspaceStartY: Float = 0f
    private var backspaceClearedInGesture: Boolean = false
    private var backspaceSnapshotBefore: CharSequence? = null
    private var backspaceSnapshotAfter: CharSequence? = null
    private var backspaceSnapshotValid: Boolean = false
    private var backspacePressed: Boolean = false
    private var backspaceLongPressStarted: Boolean = false
    private var backspaceLongPressStarter: Runnable? = null
    private var backspaceRepeatRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        asrEngine = buildEngineForCurrentMode()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Re-apply visibility in case user toggled setting while IME was backgrounded
        btnImeSwitcher?.visibility = if (prefs.showImeSwitcherButton) View.VISIBLE else View.GONE
        refreshPermissionUi()
        // Keep system toolbar/nav colors in sync with our panel background
        syncSystemBarsToKeyboardBackground(rootView)
    }

    override fun onDestroy() {
        super.onDestroy()
        asrEngine?.stop()
        serviceScope.cancel()
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        // IME context often uses a framework theme; wrap with our theme and Material dynamic colors.
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_ASRKeyboard_Ime)
        val dynamicContext = com.google.android.material.color.DynamicColors.wrapContextIfAvailable(themedContext)
        val view = LayoutInflater.from(dynamicContext).inflate(R.layout.keyboard_view, null, false)
        rootView = view
        btnMic = view.findViewById(R.id.btnMic)
        btnSettings = view.findViewById(R.id.btnSettings)
        btnEnter = view.findViewById(R.id.btnEnter)
        btnPostproc = view.findViewById(R.id.btnPostproc)
        btnBackspace = view.findViewById(R.id.btnBackspace)
        btnHide = view.findViewById(R.id.btnHide)
        btnImeSwitcher = view.findViewById(R.id.btnImeSwitcher)
        txtStatus = view.findViewById(R.id.txtStatus)

        btnMic?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!hasRecordAudioPermission()) {
                        refreshPermissionUi()
                        v.performClick()
                        return@setOnTouchListener true
                    }
                    // Require configured keys before starting ASR
                    if (!prefs.hasVolcKeys()) {
                        refreshPermissionUi()
                        v.performClick()
                        return@setOnTouchListener true
                    }
                    // Ensure engine type matches current post-processing mode
                    asrEngine = ensureEngineMatchesMode(asrEngine)
                    micLongPressStarted = false
                    micLongPressPending = true
                    val timeout = ViewConfiguration.getLongPressTimeout().toLong()
                    val r = Runnable {
                        if (micLongPressPending && asrEngine?.isRunning != true) {
                            micLongPressStarted = true
                            committedStableLen = 0
                            updateUiListening()
                            asrEngine?.start()
                        }
                    }
                    micLongPressRunnable = r
                    v.postDelayed(r, timeout)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    micLongPressPending = false
                    micLongPressRunnable?.let { v.removeCallbacks(it) }
                    micLongPressRunnable = null
                    if (micLongPressStarted && asrEngine?.isRunning == true) {
                        asrEngine?.stop()
                        // When post-processing is enabled, keep composing text visible
                        // and let onFinal() transition UI state after processing.
                        if (!prefs.postProcessEnabled) {
                            updateUiIdle()
                        } else {
                            // File-based recognition happens now
                            txtStatus?.text = getString(R.string.status_recognizing)
                        }
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }
        btnSettings?.setOnClickListener { openSettings() }
        btnEnter?.setOnClickListener { sendEnter() }
        // Backspace: tap to delete one; swipe up/left to clear all; swipe down to undo within gesture; long-press to repeat delete
        btnBackspace?.setOnClickListener { sendBackspace() }
        btnBackspace?.setOnTouchListener { v, event ->
            val ic = currentInputConnection
            if (ic == null) return@setOnTouchListener false
            val slop = ViewConfiguration.get(v.context).scaledTouchSlop
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    backspaceStartX = event.x
                    backspaceStartY = event.y
                    backspaceClearedInGesture = false
                    backspacePressed = true
                    backspaceLongPressStarted = false
                    // schedule long-press repeat starter
                    backspaceLongPressStarter?.let { v.removeCallbacks(it) }
                    backspaceRepeatRunnable?.let { v.removeCallbacks(it) }
                    val starter = Runnable {
                        if (!backspacePressed || backspaceClearedInGesture) return@Runnable
                        backspaceLongPressStarted = true
                        // initial delete then start repeating
                        sendBackspace()
                        val rep = object : Runnable {
                            override fun run() {
                                if (!backspacePressed || backspaceClearedInGesture) return
                                sendBackspace()
                                v.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
                            }
                        }
                        backspaceRepeatRunnable = rep
                        v.postDelayed(rep, ViewConfiguration.getKeyRepeatDelay().toLong())
                    }
                    backspaceLongPressStarter = starter
                    v.postDelayed(starter, ViewConfiguration.getLongPressTimeout().toLong())
                    // Take a snapshot so we can restore on downward swipe
                    try {
                        val before = ic.getTextBeforeCursor(10000, 0)
                        val after = ic.getTextAfterCursor(10000, 0)
                        backspaceSnapshotBefore = before
                        backspaceSnapshotAfter = after
                        backspaceSnapshotValid = before != null && after != null
                    } catch (_: Throwable) {
                        backspaceSnapshotBefore = null
                        backspaceSnapshotAfter = null
                        backspaceSnapshotValid = false
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - backspaceStartX
                    val dy = event.y - backspaceStartY
                    // Trigger clear when swiping up or left beyond slop
                    if (!backspaceClearedInGesture && (dy <= -slop || dx <= -slop)) {
                        // cancel any repeat
                        backspaceLongPressStarter?.let { v.removeCallbacks(it) }
                        backspaceRepeatRunnable?.let { v.removeCallbacks(it) }
                        clearAllTextWithSnapshot(ic)
                        backspaceClearedInGesture = true
                        vibrateTick()
                        return@setOnTouchListener true
                    }
                    // If already cleared in this gesture, allow swipe down to undo
                    if (backspaceClearedInGesture && dy >= slop) {
                        if (backspaceSnapshotValid) {
                            restoreSnapshot(ic)
                        }
                        backspaceClearedInGesture = false
                        vibrateTick()
                        return@setOnTouchListener true
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.x - backspaceStartX
                    val dy = event.y - backspaceStartY
                    val isTap = kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop && !backspaceClearedInGesture && !backspaceLongPressStarted
                    backspacePressed = false
                    // cancel long-press repeat tasks
                    backspaceLongPressStarter?.let { v.removeCallbacks(it) }
                    backspaceLongPressStarter = null
                    backspaceRepeatRunnable?.let { v.removeCallbacks(it) }
                    backspaceRepeatRunnable = null
                    if (isTap && event.actionMasked == MotionEvent.ACTION_UP) {
                        // Treat as a normal backspace tap
                        v.performClick()
                        return@setOnTouchListener true
                    }
                    // If finger lifted after an already-cleared gesture, keep result.
                    // If swiped down without having cleared, do nothing (cancel).
                    backspaceSnapshotBefore = null
                    backspaceSnapshotAfter = null
                    backspaceSnapshotValid = false
                    backspaceClearedInGesture = false
                    true
                }
                else -> false
            }
        }
        btnHide?.setOnClickListener { hideKeyboardPanel() }
        btnImeSwitcher?.setOnClickListener { showImePicker() }
        btnPostproc?.apply {
            isSelected = prefs.postProcessEnabled
            alpha = if (prefs.postProcessEnabled) 1f else 0.45f
            setOnClickListener {
                val enabled = !prefs.postProcessEnabled
                prefs.postProcessEnabled = enabled
                isSelected = enabled
                alpha = if (enabled) 1f else 0.45f
                // Provide quick feedback via status line
                txtStatus?.text = if (enabled) getString(R.string.cd_postproc_toggle) + ": ON" else getString(R.string.cd_postproc_toggle) + ": OFF"
                // Swap ASR engine implementation when toggled (only if not running)
                if (asrEngine?.isRunning != true) {
                    asrEngine = buildEngineForCurrentMode()
                }
            }
        }

        // Apply visibility based on settings
        btnImeSwitcher?.visibility = if (prefs.showImeSwitcherButton) View.VISIBLE else View.GONE

        updateUiIdle()
        refreshPermissionUi()
        // Align system toolbar/navigation bar color to our surface color so they match
        syncSystemBarsToKeyboardBackground(view)
        return view
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // Avoid fullscreen candidates for a compact mic-only keyboard
        return false
    }

    private fun refreshPermissionUi() {
        val granted = hasRecordAudioPermission()
        val hasKeys = prefs.hasVolcKeys()
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

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun resolveKeyboardSurfaceColor(from: View? = null): Int {
        // Use the same attribute the keyboard container uses for background
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
        // Adjust nav bar icon contrast depending on background brightness
        val isLight = try { ColorUtils.calculateLuminance(color) > 0.5 } catch (_: Throwable) { false }
        val controller = WindowInsetsControllerCompat(w, anchorView ?: w.decorView)
        controller.isAppearanceLightNavigationBars = isLight
    }

    private fun buildEngineForCurrentMode(): StreamingAsrEngine? {
        if (!prefs.hasVolcKeys()) return null
        // Always use non-streaming (file) engine regardless of post-processing setting
        return VolcFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService)
    }

    private fun ensureEngineMatchesMode(current: StreamingAsrEngine?): StreamingAsrEngine? {
        if (!prefs.hasVolcKeys()) return null
        return when (current) {
            null -> buildEngineForCurrentMode()
            is VolcFileAsrEngine -> current
            else -> VolcFileAsrEngine(this@AsrKeyboardService, serviceScope, prefs, this@AsrKeyboardService)
        }
    }

    private fun updateUiIdle() {
        txtStatus?.text = getString(R.string.status_idle)
        btnMic?.isSelected = false
        currentInputConnection?.finishComposingText()
    }

    private fun updateUiListening() {
        txtStatus?.text = getString(R.string.status_listening)
        btnMic?.isSelected = true
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun sendBackspace() {
        val ic = currentInputConnection ?: return
        // Delete one character before cursor
        ic.deleteSurroundingText(1, 0)
    }

    private fun clearAllTextWithSnapshot(ic: android.view.inputmethod.InputConnection) {
        // If snapshot is invalid (e.g., secure fields), fall back to max deletion
        if (!backspaceSnapshotValid) {
            try {
                ic.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
            } catch (_: Throwable) { }
            return
        }
        try {
            val beforeLen = backspaceSnapshotBefore?.length ?: 0
            val afterLen = backspaceSnapshotAfter?.length ?: 0
            ic.beginBatchEdit()
            ic.deleteSurroundingText(beforeLen, afterLen)
            ic.finishComposingText()
            ic.endBatchEdit()
        } catch (_: Throwable) {
            try {
                ic.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
            } catch (_: Throwable) { }
        }
    }

    private fun restoreSnapshot(ic: android.view.inputmethod.InputConnection) {
        if (!backspaceSnapshotValid) return
        val before = backspaceSnapshotBefore?.toString() ?: return
        val after = backspaceSnapshotAfter?.toString() ?: ""
        try {
            ic.beginBatchEdit()
            ic.commitText(before + after, 1)
            val sel = before.length
            try {
                ic.setSelection(sel, sel)
            } catch (_: Throwable) { }
            ic.finishComposingText()
            ic.endBatchEdit()
        } catch (_: Throwable) { }
    }

    private fun hideKeyboardPanel() {
        // Stop any ongoing ASR session and return to idle
        if (asrEngine?.isRunning == true) {
            asrEngine?.stop()
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
        } catch (_: Exception) {
            // no-op
        }
    }

    private fun vibrateTick() {
        try {
            val v = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(20, 50))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(20)
            }
        } catch (_: Exception) {
        }
    }

    override fun onFinal(text: String) {
        if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
            // Keep recognized text as composing while we post-process
            currentInputConnection?.setComposingText(text, 1)
            txtStatus?.text = getString(R.string.status_ai_processing)
            serviceScope.launch {
                val processed = try {
                    val raw = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(text) else text
                    postproc.process(raw, prefs).ifBlank { raw }
                } catch (_: Throwable) {
                    if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(text) else text
                }
                val ic = currentInputConnection
                ic?.setComposingText(processed, 1)
                ic?.finishComposingText()
                vibrateTick()
                committedStableLen = 0
                updateUiIdle()
            }
        } else {
            val ic = currentInputConnection
            val finalText = if (prefs.trimFinalTrailingPunct) trimTrailingPunctuation(text) else text
            val trimDelta = text.length - finalText.length
            // If some trailing punctuation was already committed as stable before final,
            // delete it from the editor so the final result matches the trimmed output.
            if (prefs.trimFinalTrailingPunct && trimDelta > 0) {
                val alreadyCommittedOverrun = (committedStableLen - finalText.length).coerceAtLeast(0)
                if (alreadyCommittedOverrun > 0) {
                    ic?.deleteSurroundingText(alreadyCommittedOverrun, 0)
                    committedStableLen -= alreadyCommittedOverrun
                }
            }
            val remainder = if (finalText.length > committedStableLen) finalText.substring(committedStableLen) else ""
            ic?.finishComposingText()
            if (remainder.isNotEmpty()) {
                ic?.commitText(remainder, 1)
            }
            vibrateTick()
            committedStableLen = 0
            // Always return to idle after finalizing one utterance
            updateUiIdle()
        }
    }

    override fun onError(message: String) {
        txtStatus?.text = message
        vibrateTick()
    }

    private fun trimTrailingPunctuation(s: String): String {
        if (s.isEmpty()) return s
        // Remove trailing ASCII and common CJK punctuation marks at end of utterance
        val regex = Regex("[\\p{Punct}，。！？；、：]+$")
        return s.replace(regex, "")
    }
}
