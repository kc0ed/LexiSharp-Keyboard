package com.brycewg.asrkb.ui

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
        tile.label = getString(com.brycewg.asrkb.R.string.tile_switch_ime)
        // 使用应用启动器图标作为快速设置瓦片
        tile.icon = Icon.createWithResource(this, com.brycewg.asrkb.R.mipmap.ic_launcher)
        tile.state = if (isOurImeCurrent()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val imm = getSystemService(InputMethodManager::class.java)
            // 如果我们的输入法尚未启用，先打开系统设置启用它
            if (!isOurImeEnabled(imm)) {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivityAndCollapse(intent)
                return@unlockAndRun
            }
            try {
                // 显示系统输入法选择器，让用户可以快速切换回我们的键盘
                imm?.showInputMethodPicker()
            } catch (_: Exception) {
                // 备用方案：打开输入法设置
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
