# :speech 模块 —— 语音识别契约 (v1.1)

> 本文件是「语音开发线」与「整合线(:app)」之间的接口约定。
> v1.0 定稿: 2026-08-29 · **v1.1 (2026-08-29): 接口新增 [prepare] 预热方法** (见第五节变更记录)。
> 实现请严格按此契约, 不要改动 `Voice.kt` 中的签名。
> 若要扩展契约 (加字段/加方法), 先在本文件追加变更说明并通知整合方, 双方同步。

## 0. 当前实现状态 (2026-08-29)

`Voice.kt` 契约 + **Vosk 参考实现已落地** (集成方已完成):

| 实现文件 | 说明 |
|---|---|
| `VoskSpeechRecognizer.kt` | 语音线交付的组件 (仅改包名) |
| `VoskVoiceEngine.kt` | 契约定制适配器 (权限检查/取消标志/200 字截断/单例) |
| `VoskBootstrapProvider.kt` | ContentProvider 自动注册, :app 零配置 |
| `assets/vosk-model-small-cn-0.22/` | 模型 (含 uuid); 首次加载复制到外部存储约 10~60s |

若更换引擎 (如 sherpa-onnx): `VoskBootstrapProvider.kt` 换成新的启动器, 其余不动。

## 一、模块定位

```
android/
├── :app         消费者 (UI 层; 只 import com.smartfridge.app.speech 包)
└── :speech      本模块 —— 只被 :app 依赖, 不依赖 :app
```

- namespace: `com.smartfridge.app.speech`
- minSdk 26 / compileSdk 34 / Kotlin 17
- 依赖: kotlinx-coroutines-android (可自行追加, 如 vosk / sherpa-onnx / okhttp)

## 二、对外类型 (全部在 `Voice.kt`)

| 类型 | 说明 |
|---|---|
| `VoiceEngine` | 引擎接口: start / stop / cancel / release + capabilities |
| `VoiceEvent` | Partial / Final / Error / Cancelled |
| `VoiceState` | IDLE / LISTENING / FINALIZING (UI 动画驱动用, 引擎可回调前自动同步) |
| `VoiceCapabilities` | offline / streamingPartial / longSession |
| `VoiceEngineLocator` | 注册与获取引擎的门面 |
| `NoopVoiceEngine` | 空实现 (默认), 未实现时 App 不崩溃 |

## 三、实现方要做的事 (只有两件)

1. **写一个 `VoiceEngine` 实现类** (方案不限: Vosk / sherpa-onnx / 火山引擎流式 / whisper...)。
   建议: 一个 `EngineBootstrap` 对象负责初始化 (下载/装载模型、创建识别器), 主实现类持识别器并可被多次 start/stop。
2. **在 `VoiceEngineLocator.register { EngineBootstrap.create(context) }` 注册**。
   推荐做法: 在 `:speech` 模块内放一个 `Initializer` (androidx.startup) 或
   `ContentProvider` 自动注册, 使 :app 零配置即可用。

## 四、行为约定 (必须遵守)

1. **回调线程**: 所有 `VoiceEvent` 回调在主线程 (Handler(Looper.getMainLooper()) 或 Dispatchers.Main)。
2. **权限**: `RECORD_AUDIO` 权限的申请时机由 :app 的 UI 层控制 (按压麦克风按钮时)。
   引擎的 start() 若发现权限被拒: 返回 `Result.failure`, 且**不要**回调 (UI 层负责提示)；
   或返回 success 并回调 `VoiceEvent.Error("需要麦克风权限")` —— 两者选一, 建议前者。
3. **会话终态**: 一次会话必须以 `Final` / `Error` / `Cancelled` 之一结束, 且只会有一个终态。
4. **start 幂等**: 连续调用 start 前, 若上一会话未终结, 引擎须先内部 cancel/兜底结束上会话。
5. **stop 之后**: 语音已停止, 引擎尽快 (通常 < 2s) 回调 Final。
6. **cancel 之后**: 回调 Cancelled, 且不回调 Final。
7. **release 之后**: 引擎不可再用; :app 不会对 released 实例再调 start。
8. **空文本**: 未识别到任何内容时, 回调 `Final("")` (UI 层会提示"没听清") 或 `Error` — 建议 Final("")。
9. **字数上限**: 单次会话文本 len 上限 200 (AI 解析输入足够), 超出即截断并 Final。
10. **无网降级**: 若引擎依赖在线服务且无网, 回调 `Error("网络不可用")`。

## 五、UI 层的交互 (整合方按此实现, 供参考确认交互一致)

- 按下麦克风 → `start(fn)` → 显示录音中 UI (声纹/波形 + 计时)
- 长按中上滑超过阈值 (60dp) → 显示「松开取消」→ `cancel()` → 收起
- 松开 (未上滑) → `stop()` → 显示"识别中…" (FINALIZING) → Final 回调 → 文本进入录入确认卡片
- Partial 回调 → 实时显示草稿文本 (仅当 streamingPartial=true)

## 六、验收清单 (实现方自检)

- [ ] :speech 可独立 `:speech:assembleDebug`
- [ ] :app 依赖后, 语音入口在引擎未注册时显示"识别不可用"且不崩溃
- [ ] 按住说话 → 松手出字 (离线设备可用)
- [ ] 上滑取消 → 无文字产出
- [ ] 权限拒绝 → 有友好提示
- [ ] 连续两次快速按/放 → 不崩溃、不串音

## 七、变更记录

| 版本 | 日期 | 变更 | 影响 |
|---|---|---|---|
| v1.0 | 2026-08-29 | 初版 (VoiceEngine 6 方法: start/stop/cancel/release + capabilities) | — |
| v1.1 | 2026-08-29 | 新增 `prepare(onReady)` 模型预热 (幂等); 契约补"先 prepare 后 start"约定 | 语音引擎实现方需要实现; UI 层在语音入口可见时调用一次 |
