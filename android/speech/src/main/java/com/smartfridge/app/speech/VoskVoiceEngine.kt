package com.smartfridge.app.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Vosk 实现 [VoiceEngine] 契约 —— 面向「按住说话」交互的适配器。
 *
 * 事件流映射 (VoskSpeechRecognizer → VoiceEngine):
 *   onPartialResult → VoiceEvent.Partial        (流式中间结果)
 *   onFinalResult   → VoiceEvent.Final / VoiceEvent.Cancelled (取消后丢弃)
 *   onError/onTimeout → VoiceEvent.Error
 */
class VoskVoiceEngine(context: Context) : VoiceEngine {

    private val asr = VoskSpeechRecognizer(context.applicationContext)

    @Volatile private var listener: ((VoiceEvent) -> Unit)? = null
    @Volatile private var cancelled = false
    @Volatile private var preparing = false
    @Volatile private var sessionActive = false

    /** 模型就绪前 start() 会返回失败 (预热见 [prepare]) */
    val isReady: Boolean get() = asr.isReady

    override val capabilities = VoiceCapabilities(
        offline = true,            // 完全本地, 断网可用
        streamingPartial = true,   // 说话过程实时出字
        longSession = false,       // 按住说话场景, 一次会话不宜过长
    )

    override fun prepare(onReady: (ok: Boolean, message: String) -> Unit) {
        if (asr.isReady) {
            onReady(true, "")
            return
        }
        if (preparing) return  // 已在加载中, 不得重复 unpack
        preparing = true
        asr.loadModel { ok, msg ->
            preparing = false
            onReady(ok, msg)
        }
    }

    override fun start(listener: (VoiceEvent) -> Unit): Result<Unit> {
        if (sessionActive) {
            return Result.failure(IllegalStateException("语音识别已在进行中"))
        }
        val ctx = asr.appContext
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure(IllegalStateException("没有麦克风权限"))
        }
        if (!asr.isReady) {
            return Result.failure(IllegalStateException("语音模型尚未就绪"))
        }
        this.listener = listener
        cancelled = false
        sessionActive = true

        asr.start(object : VoskSpeechRecognizer.Listener {
            override fun onPartial(text: String) {
                listener(VoiceEvent.Partial(text))
            }

            override fun onFinal(text: String) {
                sessionActive = false
                if (cancelled) {
                    listener(VoiceEvent.Cancelled)
                } else {
                    // 契约: 单次会话文本上限 200 字 (AI 解析输入足够)
                    listener(VoiceEvent.Final(text.take(MAX_TEXT_LEN)))
                }
                this@VoskVoiceEngine.listener = null
            }

            override fun onError(message: String) {
                sessionActive = false
                listener(VoiceEvent.Error(message))
                this@VoskVoiceEngine.listener = null
            }
        })
        // 防御: startListening 返回 false 时不报错也不回调 → 显式失败, 避免会话悬挂
        if (!asr.isListening) {
            sessionActive = false
            this.listener = null
            return Result.failure(IllegalStateException("麦克风启动失败"))
        }
        return Result.success(Unit)
    }

    override fun stop(): Result<Unit> {
        asr.stop()
        return Result.success(Unit)
    }

    override fun cancel(): Result<Unit> {
        cancelled = true
        asr.cancel()
        return Result.success(Unit)
    }

    override fun release() {
        listener = null
        sessionActive = false
        asr.release()
    }

    companion object {
        private const val MAX_TEXT_LEN = 200

        @Volatile private var instance: VoskVoiceEngine? = null

        /**
         * 注册单例工厂 (由 [VoskBootstrapProvider] 在进程启动时自动调用)。
         * 幂等: App 侧无需任何调用。
         */
        fun install(context: Context) {
            VoiceEngineLocator.register {
                instance ?: VoskVoiceEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}
