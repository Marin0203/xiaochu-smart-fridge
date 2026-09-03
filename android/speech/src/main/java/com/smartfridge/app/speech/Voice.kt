package com.smartfridge.app.speech

/**
 * 语音识别模块 —— 公开接口契约 (完整说明见 docs/speech-api.md)
 *
 * 分工约定:
 *  · 本文件定义契约 (integration 方 = :app 只依赖这里);
 *  · 语音引擎完整实现由独立开发线在本模块内完成 —— 只需:
 *      1) 实现 [VoiceEngine] (一个实现类即可, 方案不限: Vosk/sherpa-onnx/在线流式...),
 *      2) 在 [VoiceEngineLocator] 注册 (把 Noop 换成真实实现)。
 *  ;app 侧不感知实现细节, 切换/升级方案零改动。
 */

/** 引擎能力声明 (UI 层可据此决定交互形态与提示) */
data class VoiceCapabilities(
    /** 是否离线可用 (无网络时能否识别) */
    val offline: Boolean,
    /** 是否支持流式部分结果 (说话过程中实时出字) */
    val streamingPartial: Boolean,
    /** 是否支持长音频 (超过 60s) */
    val longSession: Boolean,
)

/** 引擎运行状态 (UI 层驱动动画) */
enum class VoiceState { IDLE, LISTENING, FINALIZING }

/** 单次识别会话的事件流 —— 回调均在主线程 */
sealed interface VoiceEvent {
    /** 流式中间结果 (capabilities.streamingPartial == true 时会连续触发) */
    data class Partial(val text: String) : VoiceEvent

    /** 最终结果 (stop() 成功识别) */
    data class Final(val text: String) : VoiceEvent

    /** 错误 (权限拒绝/网络失败/音频异常...); 会话随本事件终止 */
    data class Error(val message: String, val cause: Throwable? = null) : VoiceEvent

    /** 被主动取消 (cancel()); 丢弃本次内容, 不产生 Final */
    data object Cancelled : VoiceEvent
}

/**
 * 语音识别引擎。
 *
 * 生命周期约定 (与「按住说话」交互一一对应):
 *   prepare() —— 用户进入语音入口时预热: 加载模型/建立连接 (首次可能需要数秒~数十秒)
 *   start()   —— 用户按下: 申请/检查麦克风, 开始采集与识别 (若权限不可用 → 返回失败)
 *   stop()    —— 用户松开 (未上滑): 结束采集, 给出最终识别 → 回调 Final
 *   cancel()  —— 用户上滑取消: 丢弃采集, 不回调 → 回调 Cancelled
 *   连续 start 之前应确保上一次会话已结束 (Final/Error/Cancelled 均已回调)。
 */
interface VoiceEngine {
    val capabilities: VoiceCapabilities

    /**
     * 预热: 加载模型/初始化引擎 (幂等, 可重复调用; 回调在主线程)。
     * UI 层应在语音入口可见时调用一次; 未预热时 start() 可能以失败返回。
     * @param onReady (ok, message): ok=true 表示可以开始识别了。
     */
    fun prepare(onReady: (ok: Boolean, message: String) -> Unit) {}

    /**
     * 开始一次识别会话。
     * @return 失败 (如权限被拒且引擎拒绝兜底) 时返回失败, 且不触发任何回调;
     *         成功时引擎自行驱动 [VoiceEvent] 回调序列, 直至 Final/Error/Cancelled 之一终止。
     */
    fun start(listener: (VoiceEvent) -> Unit): Result<Unit>

    /** 结束当前会话 (用户松开手指) → 触发 [VoiceEvent.Final] (或 [VoiceEvent.Error]) */
    fun stop(): Result<Unit>

    /** 取消当前会话 (用户上滑取消) → 触发 [VoiceEvent.Cancelled] */
    fun cancel(): Result<Unit>

    /** 释放底层资源 (App 退出/关闭语音功能时); 之后引擎不可复用, 需重新获取 */
    fun release()
}

/**
 * 引擎定位器 —— 实现方在这里注册真实引擎。
 *
 * 默认注册 [NoopVoiceEngine] (什么都不做, 回调 Error)。
 * 真实实现方在模块初始化时调用 [register] 覆盖 (例如放一个
 * EngineBootstrap 类 + ContentProvider 静态初始化, 或由 :app 手动调用)。
 * 未注册/未实现时 App 仍可编译运行: 语音入口显示"识别不可用"。
 */
object VoiceEngineLocator {
    @Volatile
    private var factory: (() -> VoiceEngine)? = { NoopVoiceEngine() }

    /** 实现方注册入口 (线程安全, 幂等) */
    fun register(factory: () -> VoiceEngine) {
        this.factory = factory
    }

    /** 获取当前引擎 (每次返回同一实例; 实现方须保证线程安全) */
    fun get(): VoiceEngine = factory!!.invoke()
}

/** 空实现: 直接报错 "语音引擎未安装", 保证缺实现时 App 不崩溃 */
class NoopVoiceEngine : VoiceEngine {
    override val capabilities = VoiceCapabilities(
        offline = false, streamingPartial = false, longSession = false,
    )

    override fun start(listener: (VoiceEvent) -> Unit): Result<Unit> {
        listener(VoiceEvent.Error("语音引擎未安装 (speech 模块尚无实现)"))
        return Result.success(Unit)
    }

    override fun stop(): Result<Unit> = Result.success(Unit)
    override fun cancel(): Result<Unit> = Result.success(Unit)
    override fun release() {}
}
