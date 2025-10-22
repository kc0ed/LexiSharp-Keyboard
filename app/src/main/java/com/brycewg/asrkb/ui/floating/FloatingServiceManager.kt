package com.brycewg.asrkb.ui.floating

import android.content.Context
import android.content.Intent
import android.util.Log
import com.brycewg.asrkb.ui.floating.FloatingAsrService

/**
 * 悬浮服务管理器
 * 统一管理 FloatingAsrService 的启停逻辑
 */
class FloatingServiceManager(private val context: Context) {

    companion object {
        private const val TAG = "FloatingServiceManager"
    }

    /**
     * 启动语音识别悬浮球服务
     */
    fun showAsrService() {
        try {
            val intent = Intent(context, FloatingAsrService::class.java).apply {
                action = FloatingAsrService.ACTION_SHOW
            }
            context.startService(intent)
            Log.d(TAG, "Started FloatingAsrService")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start FloatingAsrService", e)
        }
    }

    /**
     * 隐藏并停止语音识别悬浮球服务
     */
    fun hideAsrService() {
        try {
            val hideIntent = Intent(context, FloatingAsrService::class.java).apply {
                action = FloatingAsrService.ACTION_HIDE
            }
            context.startService(hideIntent)
            context.stopService(Intent(context, FloatingAsrService::class.java))
            Log.d(TAG, "Stopped FloatingAsrService")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop FloatingAsrService", e)
        }
    }

    /** 刷新语音识别悬浮球（用于应用透明度、大小等变更） */
    fun refreshAsrService(asrEnabled: Boolean) {
        try {
            if (asrEnabled) showAsrService()
            Log.d(TAG, "Refreshed ASR service: enabled=$asrEnabled")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to refresh ASR service", e)
        }
    }
}
