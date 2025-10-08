package com.example.asrkeyboard.asr

/**
 * ASR（自动语音识别）引擎基础接口
 * 定义了语音识别引擎的基本功能
 */
interface AsrEngine {
    /** 引擎是否正在运行 */
    val isRunning: Boolean

    /** 开始语音识别 */
    fun start()

    /** 停止语音识别 */
    fun stop()
}

/**
 * 流式 ASR 引擎接口
 * 继承自 AsrEngine，增加了流式识别的功能
 */
interface StreamingAsrEngine : AsrEngine {
    /** 流式识别结果监听器 */
    interface Listener {
        /** 接收最终识别结果 */
        fun onFinal(text: String)

        /** 处理识别过程中的错误 */
        fun onError(message: String)
    }
}
