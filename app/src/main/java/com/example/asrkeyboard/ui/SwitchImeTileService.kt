package com.example.asrkeyboard.ui

import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.inputmethod.InputMethodManager

class SwitchImeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.label = getString(com.example.asrkeyboard.R.string.tile_switch_ime)
        // Reuse monochrome app icon for simplicity
        tile.icon = Icon.createWithResource(this, com.example.asrkeyboard.R.drawable.ic_launcher_monochrome)
        tile.state = if (isOurImeCurrent()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val imm = getSystemService(InputMethodManager::class.java)
            // If our IME is not enabled yet, open system settings to enable it first
            if (!isOurImeEnabled(imm)) {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivityAndCollapse(intent)
                return@unlockAndRun
            }
            try {
                // Show the system IME picker so user can quickly switch back to our keyboard
                imm?.showInputMethodPicker()
            } catch (_: Exception) {
                // Fallback: open IME settings
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun isOurImeEnabled(imm: InputMethodManager?): Boolean {
        val expectedId = "${packageName}/.ime.AsrKeyboardService"
        val list = try { imm?.enabledInputMethodList } catch (_: Throwable) { null }
        return list?.any { it.id == expectedId || it.packageName == packageName } == true
    }

    private fun isOurImeCurrent(): Boolean {
        return try {
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val expectedId = "${packageName}/.ime.AsrKeyboardService"
            current == expectedId
        } catch (_: Throwable) {
            false
        }
    }
}

