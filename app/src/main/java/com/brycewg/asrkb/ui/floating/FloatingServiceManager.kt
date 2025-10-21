package com.brycewg.asrkb.ui.floating

import android.content.Context
import android.content.Intent
import android.util.Log
import com.brycewg.asrkb.ui.floating.FloatingAsrService
import com.brycewg.asrkb.ui.floating.FloatingImeSwitcherService

/**
 * 悬浮服务管理器
 * 统一管理 FloatingAsrService 和 FloatingImeSwitcherService 的启停逻辑
 */
class FloatingServiceManager(private val context: Context) {

    companion object {
        private const val TAG = "FloatingServiceManager"
    }

    /**
     * 启动输入法切换悬浮球服务
     */
    fun showImeSwitcherService() {
        try {
            val intent = Intent(context, FloatingImeSwitcherService::class.java).apply {
                action = FloatingImeSwitcherService.ACTION_SHOW
            }
            context.startService(intent)
            Log.d(TAG, "Started FloatingImeSwitcherService")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start FloatingImeSwitcherService", e)
        }
    }

    /**
     * 隐藏并停止输入法切换悬浮球服务
     */
    fun hideImeSwitcherService() {
        try {
            val hideIntent = Intent(context, FloatingImeSwitcherService::class.java).apply {
                action = FloatingImeSwitcherService.ACTION_HIDE
            }
            context.startService(hideIntent)
            context.stopService(Intent(context, FloatingImeSwitcherService::class.java))
            Log.d(TAG, "Stopped FloatingImeSwitcherService")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop FloatingImeSwitcherService", e)
        }
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

    /**
     * 刷新悬浮球服务的显示状态（用于更新透明度、大小等）
     */
    fun refreshServices(imeSwitcherEnabled: Boolean, asrEnabled: Boolean) {
        try {
            if (imeSwitcherEnabled) {
                showImeSwitcherService()
            }
            if (asrEnabled) {
                showAsrService()
            }
            Log.d(TAG, "Refreshed services: imeSwitcher=$imeSwitcherEnabled, asr=$asrEnabled")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to refresh services", e)
        }
    }
}
