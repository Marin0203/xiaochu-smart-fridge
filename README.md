# 小厨 · 家庭共享智能冰箱 & AI 食谱

> **当前架构：Capacitor 6 宿主（Kotlin，数据层原生）+ 单 HTML UI（唯一源 `D:\work2\小厨-WebView\小厨-重构版.html`）+ Supabase 全家桶**
> 页面↔桥契约：`docs/bridge-contract.md`（唯一权威）｜ 页面内容版本链：`docs/版本链.md` ｜ 版本号：`VERSIONS.json`（唯一事实源）
> 功能：多端库存实时同步 / 保质期动态预警（权威表 835 条）/ AI 文字一键入库 / 临期食材 AI 菜谱（30 道池）
> （历史：08-25~08-27 曾为 Jetpack Compose 原生版主线，08-29 起转为 WebView 单 HTML；Flutter `app/` 仅为参考实现。）

```
┌────────────────────────── 客户端 (Kotlin + Jetpack Compose, MVVM) ──────────────────┐
│  UI层   [冷藏区|冷冻区|常温区]卡片 · 新鲜度色条 · 语音录入弹层 · 菜谱(Markdown轻渲染) │
│  状态层 AuthRepository / VoiceAddViewModel / SyncService(StateFlow)                  │
│  域层   Ingredient · Freshness算法(纯函数) · 容错JSON清洗 · 提示词(Kotlin版)          │
│  数据   SQLite(本地真相源+outbox) → SyncService(推送/LWW冲突/Realtime合并) → Supabase │
└──────────────────────────────────────────────────────────────────────────────────────┘
                 ▲ Realtime WebSocket                         ▲ HTTPS REST (JWT + RLS)
┌────────────────┴────────── Supabase ────────────────────────┴───────────────────────┐
│  Postgres: families / family_members / ingredients + RLS(家庭级隔离)               │
│  Realtime: Postgres Changes (ingredients 表变化 → 全家庭成员秒级收到)               │
│  Edge Functions: ai-parse(语音文本→JSON) · ai-recipe(临期→3道菜谱) ← DeepSeek Key    │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

## 目录结构

```
smart-fridge/
├── android/                        # ★ Android 原生工程 (Kotlin + Compose, 本仓库主方向)
│   ├── app/build.gradle.kts        # 密钥从 local.properties 注入 BuildConfig (勿提交)
│   └── app/src/main/java/com/smartfridge/app/
│       ├── core/Config.kt          # 配置 (Supabase URL/Key, AI 模式)
│       ├── domain/                 # 纯 Kotlin 领域层: Freshness算法/Ingredient/Recipe/分类器
│       ├── ai/                     # 提示词 · RestilientJson容错 · Edge/Direct双实现 · 本地降级菜谱
│       ├── data/                   # SQLite(本地) · AuthRepository · SupabaseApi(REST+Realtime WS)
│       │                           # · SyncService(LWW同步) · AppServices(DI容器)
│       ├── speech/                 # SpeechRecognizer 封装 (离线 whisper 升级接入点)
│       └── ui/                     # theme · components · viewmodel · screens(7屏)
├── backend/supabase/               # 后端 (两个版本共用, 无需改动)
│   ├── migrations/0001_init.sql    # 建表 + RLS + 邀请码RPC + Realtime publication
│   └── functions/                  # ai-parse / ai-recipe → DeepSeek (密钥在服务端)
├── app/                            # (参考) Flutter 版同构实现, 算法一致, 可留作对比/移植
└── docs/                           # architecture.md · supabase-setup.md
```

## 快速开始（Android 版）

> 前提：Android Studio（最新稳定版即可）。你的 Supabase 项目已创建，密钥已写入 `android/local.properties`（该文件是本机文件，已被 .gitignore）。

```bash
# 1. 后端 (只做一次, 已给你建好项目的话可直接跳到 2)
#    Supabase Dashboard → SQL Editor → 执行 backend/supabase/migrations/0001_init.sql
#    部署 Edge Functions + 设置 DeepSeek key → 见 docs/supabase-setup.md

# 2. 配置密钥
cd android
# 确认 local.properties 存在且内容正确 (模板见 local.properties.example):
#   SUPABASE_URL=https://juxlfhrgywurttzzxgvr.supabase.co
#   SUPABASE_KEY=sb_publishable_EIP1dKrKqeJEg9hRdGEgew_YyW5S2Li
#   AI_MODE=edge

# 3. 打开运行
#    Android Studio → Open → 选择 android/ 目录 → 等待 Gradle Sync → Run ▶
#    或命令行: ./gradlew assembleDebug   (第一次会下载依赖, 需几分钟)
```

> ⚠️ 未生成 `android/app/src/main/res/mipmap-*` 图标资源 —— Android Studio 新建后请用
> **File → New → Image Asset** 生成启动图标（Manifest 里已引用 `@mipmap/ic_launcher`）。
> 这是唯一需要 IDE 手动补的步骤。

## 核心功能怎么用

| 场景 | 路径 |
|---|---|
| 第1步 注册/创建家庭 → 家人输邀请码加入 | 登录页 / 加入家庭页 |
| 语音/文字入库 | 库存页右下角 **麦克风** → 说「今天买了半斤猪肉和三个番茄」→ AI 解析 → 确认 |
| 新鲜度视觉预警 | 卡片左侧色条 + 徽章 + 进度条: 🟢>50% Fresh / 🟡20~50% 尽快食用 / 🔴<20% 临期 |
| AI 菜谱 | 「AI 菜谱」页 → 自动列出黄/红临期食材 → 生成 3 道菜 → 详情渲染 |
| 离线可用 | 无网时照常录入, 数据进本地 + outbox, 联网 30s 内自动推送 |

## 重要设计决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 客户端 | **Jetpack Compose** (用户确认) | Android 原生、官方亲儿子; Flutter 版保留为参考实现 (`app/`), 算法同构 |
| 后端 | **Supabase** | SQL+RLS+Realtime+Edge Functions 一体化; 免费档够家庭用 |
| LLM 密钥 | **Edge Function 代理** (默认) | 客户端直连 = 泄漏; `AI_MODE=direct` 仅本地/自部署 (露 Ollama 等) |
| 同步冲突 | **行级 LWW (updated_at 时间戳)** | 家庭编辑频次低, 简单可靠; CRDT 无必要 (演进见 docs/architecture.md) |
| 后端对接 | **OkHttp 直连 PostgREST/GoTrue + Realtime WebSocket 协议** | 不依赖第三方 SDK 版本差异; 想换官方 supabase-kt 只需替换 `data/` 包 |
| 本地存储 | 系统 SQLiteOpenHelper | 避免 Room+KSP 的版本匹配链 (表只有 3 张, 手写 DAO 完全可控) |
| 语音引擎 | 系统 SpeechRecognizer | 无 Google 服务的国行机可能不可用 → 自动降级手动输入; 离线 whisper.cpp 升级接入点已留 (`speech/SpeechService.kt`) |
| 菜谱 Markdown | 内置轻量渲染 | 无第三方依赖; 需要完整渲染时可换 Markwon (AndroidView 嵌入), 接口已在 `MarkdownLiteText` |

### 本地离线 ASR 升级（Whisper.cpp / sherpa-onnx）

中文场景推荐 **sherpa-onnx + FunASR(paraformer 中文模型)**（免费、完全离线、带官方 Android AAR）。
替换点只有一个：`SpeechService.kt` 里 `start()` 的内部实现（录音 → 16k mono wav → 本地推理 → 文本），
上层逻辑零改动。或使用 flutter_whisper 版（见 Flutter 目录 README 段落）。

## 已知限制（MVP）

- 删除为**硬删除**（软删除+回收站是后续演进点）
- 拉取为**全量+Realtime 增量**，家庭级数据量（<5k 行）无压力
- 邮箱+密码登录；微信/Apple 登录需另配
- 食谱「消耗」是手动扣减，尚未打通「菜谱 → 一键扣减库存」联动

## 下一步建议（优先级排序）

1. **菜谱一键消耗**: 勾选已做菜谱 → 自动扣减对应食材
2. **过期回收站/历史**: 软删除 + 30天保留
3. **采购清单**: 菜谱「需补充: xxx」→ 自动进清单同步全家
4. **推送通知**: 临期食材到达阈值时发通知
