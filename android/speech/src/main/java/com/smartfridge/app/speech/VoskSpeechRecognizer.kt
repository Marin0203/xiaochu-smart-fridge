package com.smartfridge.app.speech

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.IOException

/**
 * 本地（离线）中文语音识别封装 —— 基于 Vosk。
 *
 * 来自语音开发线交付 (D:\work2\android-local-speech-recognition\VoskSpeechRecognizer.kt),
 * 仅改包名; API 详见 docs/speech-api.md 与 HANDOVER.md。
 *
 * 前置条件：
 *  1. :speech build.gradle 添加依赖：implementation("com.alphacephei:vosk-android:0.3.47")
 *  2. assets 下有 vosk-model-small-cn-0.22 目录（含 uuid 文件 —— 官方包不含, 已补齐）
 *
 * 使用流程：
 *      recognizer.loadModel { ok, msg -> ... }   // 异步，回调在主线程
 *      recognizer.start(listener)                // 按住说话
 *      recognizer.stop()                         // 松手，最终结果经 onFinal 回调
 *      recognizer.release()                      // 引擎退出时
 */
class VoskSpeechRecognizer(
    context: Context,
    private val modelAssetName: String = DEFAULT_MODEL_ASSET,
) {

    interface Listener {
        /** 说话过程中持续更新的临时结果（可能是空字符串） */
        fun onPartial(text: String)

        /** stop() 之后的一段最终结果 */
        fun onFinal(text: String)

        /** 出错（权限、麦克风被占用、模型未加载等） */
        fun onError(message: String)
    }

    /** applicationContext (引擎适配层做权限检查用) */
    internal val appContext: Context = context.applicationContext

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null

    /** 模型是否加载完成 */
    val isReady: Boolean get() = model != null

    /** 是否正在识别中 */
    var isListening = false
        private set

    /**
     * 异步加载 models 里的模型（回调在主线程）。
     * 首次调用会把 assets 中的模型目录复制到应用外部存储，
     * 之后通过 uuid 文件校验，内容不变则直接复用，不再重复复制。
     */
    fun loadModel(onReady: (Boolean, String) -> Unit) {
        if (isReady) {
            onReady(true, "")
            return
        }
        StorageService.unpack(
            appContext,
            modelAssetName,
            "model",
            StorageService.Callback<Model> { loaded ->
                model = loaded
                onReady(true, "")
            },
            StorageService.Callback<IOException> { error ->
                onReady(false, error.message ?: "模型加载失败")
            },
        )
    }

    /**
     * 从任意目录直接加载模型。
     * 适合"模型不打包进 APK、首次启动时联网下载解压"的场景。
     */
    fun loadModelFromDirectory(modelDir: File, onReady: (Boolean, String) -> Unit) {
        if (isReady) {
            onReady(true, "")
            return
        }
        try {
            model = Model(modelDir.absolutePath)
            onReady(true, "")
        } catch (e: Exception) {
            onReady(false, e.message ?: "模型加载失败")
        }
    }

    /**
     * 开始识别（按住说话时调用）。
     * 需要先 loadModel 成功，且当前不在识别中。
     */
    fun start(listener: Listener) {
        if (speechService != null) {
            listener.onError("识别已在进行中")
            return
        }
        val currentModel = model ?: run {
            listener.onError("模型尚未加载完成")
            return
        }
        try {
            // 16kHz 单声道是 Vosk 要求的采样格式，SpeechService 会自动从麦克风采集
            val rec = Recognizer(currentModel, SAMPLE_RATE)
            val service = SpeechService(rec, SAMPLE_RATE)
            recognizer = rec
            speechService = service

            val texts = mutableListOf<String>()
            val started = service.startListening(object : RecognitionListener {

                override fun onPartialResult(hypothesis: String) {
                    listener.onPartial(parseText(hypothesis))
                }

                // 一句话说完（检测到静音）时触发，识别器会继续听下一句
                override fun onResult(hypothesis: String) {
                    val text = parseText(hypothesis)
                    if (text.isNotEmpty()) texts.add(text)
                }

                // stop() 之后触发，这是一段录音的最终结果
                override fun onFinalResult(hypothesis: String) {
                    val text = parseText(hypothesis)
                    if (text.isNotEmpty()) texts.add(text)
                    listener.onFinal(texts.joinToString("\n"))
                }

                override fun onError(exception: Exception) {
                    listener.onError(exception.message ?: exception.toString())
                }

                override fun onTimeout() {
                    listener.onError("等待说话超时")
                }
            })
            isListening = started
        } catch (e: Exception) {
            releaseService()
            listener.onError(e.message ?: e.toString())
        }
    }

    /** 结束识别（松手时调用），最终结果通过 onFinal 回调给出 */
    fun stop() {
        isListening = false
        val service = speechService ?: return
        service.stop()
        releaseService()
    }

    /** 立即取消识别，不产生最终结果 */
    fun cancel() {
        isListening = false
        val service = speechService ?: return
        service.cancel()
        releaseService()
    }

    /** 释放模型与录音资源，务必在引擎退出时调用 */
    fun release() {
        cancel()
        try {
            model?.close()
        } catch (_: Exception) {
            // 忽略重复释放
        }
        model = null
    }

    private fun releaseService() {
        try {
            speechService?.shutdown()
        } catch (_: Exception) {
        }
        speechService = null
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    /** Vosk 返回的 JSON，例如 {"partial" : "你好"} 或 {"text" : "你好"} */
    private fun parseText(json: String): String = try {
        JSONObject(json).optString("text").trim()
    } catch (_: Exception) {
        ""
    }

    companion object {
        private const val SAMPLE_RATE = 16000.0f

        /** assets 下的模型目录名，换成其它模型时改这里即可 */
        const val DEFAULT_MODEL_ASSET = "vosk-model-small-cn-0.22"
    }
}
