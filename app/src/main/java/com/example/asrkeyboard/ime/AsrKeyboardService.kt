package com.example.asrkeyboard.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.content.ContextCompat
import com.example.asrkeyboard.R
import com.example.asrkeyboard.asr.StreamingAsrEngine
import com.example.asrkeyboard.asr.FakeAsrEngine
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
    private lateinit var asrEngine: StreamingAsrEngine
    private lateinit var prefs: Prefs

    private var btnMic: FloatingActionButton? = null
    private var btnSettings: ImageButton? = null
    private var btnGrant: ImageButton? = null
    private var txtStatus: TextView? = null
    private var txtHint: TextView? = null
    private var committedStableLen: Int = 0

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        asrEngine = if (prefs.hasVolcKeys()) {
            VolcStreamAsrEngine(this, serviceScope, prefs, this)
        } else {
            FakeAsrEngine(this, serviceScope, this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        asrEngine.stop()
        serviceScope.cancel()
    }

    override fun onCreateInputView(): View {
        // IME context is forced to a framework DeviceDefault theme on many OS versions,
        // which breaks Material attribute resolution in our layout/drawables.
        // Inflate with our app's Material3 IME theme to ensure attrs like colorSurface resolve.
        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_ASRKeyboard_Ime)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.keyboard_view, null)
        btnMic = view.findViewById(R.id.btnMic)
        btnSettings = view.findViewById(R.id.btnSettings)
        btnGrant = view.findViewById(R.id.btnGrant)
        txtStatus = view.findViewById(R.id.txtStatus)
        txtHint = view.findViewById(R.id.txtHint)

        btnMic?.setOnClickListener { onMicClicked() }
        btnSettings?.setOnClickListener { openSettings() }
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
        btnGrant?.visibility = if (granted) View.GONE else View.VISIBLE
        btnMic?.isEnabled = granted
        txtHint?.text = if (granted) getString(R.string.hint_press_mic) else getString(R.string.hint_need_permission)
    }

    private fun onMicClicked() {
        if (!hasRecordAudioPermission()) {
            refreshPermissionUi()
            return
        }
        if (asrEngine.isRunning) {
            asrEngine.stop()
            updateUiIdle()
        } else {
            updateUiListening()
            committedStableLen = 0
            asrEngine.start()
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

    private fun vibrateTick() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
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
        val remainder = if (text.length > committedStableLen) text.substring(committedStableLen) else ""
        currentInputConnection?.finishComposingText()
        if (remainder.isNotEmpty()) {
            currentInputConnection?.commitText(remainder, 1)
        }
        vibrateTick()
        committedStableLen = 0
        if (prefs.continuousMode && asrEngine.isRunning) {
            // Stay in listening state for next utterance
            txtStatus?.text = getString(R.string.status_listening)
            btnMic?.isSelected = true
        } else {
            updateUiIdle()
        }
    }

    override fun onError(message: String) {
        txtStatus?.text = message
        vibrateTick()
    }
}
