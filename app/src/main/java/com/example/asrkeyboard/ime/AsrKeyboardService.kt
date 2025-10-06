package com.example.asrkeyboard.ime

import android.Manifest
import android.content.Intent
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.view.KeyEvent
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.content.ContextCompat
import com.example.asrkeyboard.R
import com.example.asrkeyboard.asr.StreamingAsrEngine
import com.example.asrkeyboard.asr.VolcStreamAsrEngine
import com.example.asrkeyboard.store.Prefs
import com.example.asrkeyboard.ui.PermissionActivity
import com.example.asrkeyboard.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AsrKeyboardService : InputMethodService(), StreamingAsrEngine.Listener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var asrEngine: StreamingAsrEngine? = null
    private lateinit var prefs: Prefs

    private var btnMic: FloatingActionButton? = null
    private var btnSettings: ImageButton? = null
    private var btnEnter: ImageButton? = null
    private var btnBackspace: ImageButton? = null
    private var btnGrant: ImageButton? = null
    private var txtStatus: TextView? = null
    private var txtHint: TextView? = null
    private var committedStableLen: Int = 0
    private var micLongPressStarted: Boolean = false
    private var micLongPressPending: Boolean = false
    private var micLongPressRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        asrEngine = if (prefs.hasVolcKeys()) {
            VolcStreamAsrEngine(this, serviceScope, prefs, this)
        } else {
            null
        }
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
        btnMic = view.findViewById(R.id.btnMic)
        btnSettings = view.findViewById(R.id.btnSettings)
        btnEnter = view.findViewById(R.id.btnEnter)
        btnBackspace = view.findViewById(R.id.btnBackspace)
        btnGrant = view.findViewById(R.id.btnGrant)
        txtStatus = view.findViewById(R.id.txtStatus)
        txtHint = view.findViewById(R.id.txtHint)

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
                    if (asrEngine == null) {
                        asrEngine = VolcStreamAsrEngine(this, serviceScope, prefs, this)
                    }
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
                        updateUiIdle()
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }
        btnSettings?.setOnClickListener { openSettings() }
        btnEnter?.setOnClickListener { sendEnter() }
        btnBackspace?.setOnClickListener { sendBackspace() }
        btnGrant?.setOnClickListener { requestAudioPermission() }

        refreshPermissionUi()
        updateUiIdle()
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
            btnGrant?.visibility = View.VISIBLE
            btnMic?.isEnabled = false
            txtHint?.text = getString(R.string.hint_need_permission)
        } else if (!hasKeys) {
            btnGrant?.visibility = View.GONE
            btnMic?.isEnabled = false
            txtHint?.text = getString(R.string.hint_need_keys)
        } else {
            btnGrant?.visibility = View.GONE
            btnMic?.isEnabled = true
            txtHint?.text = getString(R.string.hint_press_mic)
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun requestAudioPermission() {
        val intent = Intent(this, PermissionActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
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
    // StreamingAsrEngine.Listener
    override fun onPartial(stableText: String, unstableText: String) {
        // Commit newly stabilized prefix
        if (stableText.length > committedStableLen) {
            val newPart = stableText.substring(committedStableLen)
            currentInputConnection?.commitText(newPart, 1)
            committedStableLen = stableText.length
        }
        // Show remaining unstable as composing
        currentInputConnection?.setComposingText(unstableText, 1)
    }

    override fun onFinal(text: String) {
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
