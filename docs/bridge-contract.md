# 小厨 · 页面↔原生桥契约 v2（唯一权威）

> 生效：2026-09-04 ｜ 页面唯一源：`D:\work2\小厨-WebView\小厨-重构版.html`
> **本文是"页面 ↔ 桥 ↔ 原生"三方契约的唯一权威**；下列旧文档与本文冲突的，以本文为准：
> `docs/webview-api.md`（已废弃）、`D:\work2\小厨-WebView\对接命令.md`（部分过时，仅历史参考）、`D:\work2\xiaochu-vision\交接文档.md`（UI 规格，不涉桥）。
> 页面内容一致性（5 节点 sha256 链）另行遵守 `docs\版本链.md`；版本号规则遵守 `docs\版本规范.md` 与 `VERSIONS.json`。
>
> 本文档由 2026-09-04 依**代码实测**整理（WebAppBridge.kt / MainActivity.kt / 小厨-重构版.html）；以后改契约必须同步更新本文（见 §7）。

---

## 1. 通道总览

```
[页面 HTML]  window.emit(type, json)
   │  桥适配层（页面内自实现，按优先级）
   │    1) window.Capacitor.Plugins.FridgeNative  → bridgeMode='capacitor'（首选，官方插件通道）
   │    2) window.AndroidBridge.onEvent           → bridgeMode='android'（addJavascriptInterface 兜底）
   │    3) 两者皆无                               → bridgeMode='demo'（浏览器预览，静默丢弃）
   ▼
[宿主 MainActivity]  onCapEvent(type, payload)   （Cap 插件回调；若 bridgeMode=android 则 WebAppBridge.onEvent 直接收）
   ├── trace / ui-update / ui-restart / page-ready → MainActivity 直接处理（热更/日志/初始化）
   └── 其余 22 类 → WebAppBridge.onEvent → 数据层（SQLite/Supabase/AI/通知，全部原生侧）
   ▼
[原生 → 页面]  evaluateJavascript：
   window.setData(json) / setInsets(top,bottom) / setAppTheme(dark)
   / setReminder({enabled,hours}) / setAppInfo(ver,code) / setUiUpdate({cur,target,state,ver})
```

**关键纪律**：UI 是纯视图 + 交互；数据永远以 `setData` 注入为准（D-22 原则延续）；Kotlin 只承载数据/系统能力（"UI 改动零装机"纪律，见《开发纪律与自查矩阵》）。

---

## 2. 页面 → 原生事件总表（22 类）

> 已按 2026-09-04 页面实测核对：**当前页面实际发射 14 类**（表格打 ✅）；其余为"旧页面兼容保留"（打 🔒，页面不再发射，桥不可删除——删了旧 APK 页面会静默失败）。

| type | 当前页面 | payload（关键字段） | Kotlin 行为 |
|---|---|---|---|
| `edit-save` | ✅ | `{id,qty,zone,unit}` | 更新库存（数量/分区/单位）→ SQLite→云 LWW→回灌 |
| `edit-delete` | ✅ | `{id,remove}` | remove=true 删行；false 扣 1 份 |
| `edit-fresh` | ✅ | `{id}` | 刷新保质期：购买时间重置为当前 + 保鲜表重算（落库上云） |
| `deduct` | ✅ | `{items:[{name,qty,unit}]}` | 菜谱扣料：单位换算(toStockQty)→批量扣减，≤0 删行→回灌 |
| `add` | ✅ | `{name,qty,unit,zone,tag?,shelfLifeDays?}` | 入库。**保质期优先级：权威表 PreservationTable > shelfLifeDays > 3**；tag 覆盖分类 |
| `ai-parse` | ✅ | `{text}` | DeepSeek 整段解析（一句话→多食材草稿，密钥服务端） |
| `ai-guess` | 🔒 | `{name,zone}` | DeepSeek 单名猜料（旧页面用；当前页面不再发） |
| `pong` | ✅ | 无 | 体检回路完成（ping→pong latch） |
| `health-check` | ✅ | 无 | 一键体检：云 + 登录 + 桥回路 + AI，结果各层可见 |
| `reminder-settings` | ✅ | `{enabled,hours}` | 通知权限 + 每日任务调度（WorkManager）+ 持久化 |
| `recipe-mode` | ✅ | `{mode:'normal'\|'expiring'}` | 临期模式开关：只筛选展示（池保留不重生成）+ 持久化 |
| `refresh` | ✅ | 无 | 同步 + 菜谱重生成（走池） |
| `refresh-pool` | ✅ | 无 | 食谱池操作：重生成/切批（随机出 5 道秒切） |
| `check-update` | ✅ | 无 | 手动查更新：服务器比对（有新版自动拉取+刷新；无则提示已最新） |
| `sync` | ✅ | 无 | 立即同步：云端拉取/推送 + 回灌 |
| `copy` | 🔒 | `{name}` | 剪贴板兜底（页面已先试 navigator.clipboard） |
| `theme` | 🔒 | `{dark}` | 夜间持久化（当前页面夜间走 setAppTheme 推送侧控制） |
| `icon-set` | 🔒 | `{id:'a'\|'b'\|'c'}` | 图标方案持久化 |
| `skin` | 🔒 | `{id}` | 皮肤持久化 |
| `zone` | 🔒 | `{zone}` | 记录上次分区 |
| `entry-add` | 🔒 | 无 | 埋点：录入浮窗打开 |
| `data` | 🔒 | — | 预留 |

**MainActivity 直接处理（4 类，不进 WebAppBridge）**：

| type | 行为 |
|---|---|
| `page-ready` | 页面就绪握手：推安全区/主题/提醒/UI 版本状态 + 数据（初始化统一入口） |
| `trace` | 写入 trace.log（`page-trace: …`，全链路审计） |
| `ui-update` | UI 更新按钮：下载→sha256 验签→落盘→自动 reload（"更新"一条龙） |
| `ui-restart` | 兼容旧页（同 ui-update 处理） |

---

## 3. 原生 → 页面推送（6 个 set*，均为 `window.xxx &&` 防御式调用）

| 函数 | 入参 | 时机 |
|---|---|---|
| `setData(json)` | setData JSON（§4） | 启动 / 每次同步成功 / 本地落库后回灌 |
| `setInsets(top,bottom)` | 安全区 px | 启动 / insets 变化（EdgeToEdge，px 与 CSS px 同单位） |
| `setAppTheme(dark)` | Boolean | 启动恢复 / 夜间切换（500ms 渐变在页面侧） |
| `setReminder({enabled,hours})` | 对象 | 启动 / 提醒设置变更 |
| `setAppInfo(ver,code)` | APK versionName/versionCode | **每次页面加载**（版本信息卡） |
| `setUiUpdate({cur,target,state,ver})` | `cur`=本机已生效 UI 版本（files/web/version.json）；`target`=服务器可更新版；`state`='new'\|'ok' | 每次页面加载（UI 更新卡；契约 v2） |

另：返回键 `window.handleBack()`（页面处理逐层关闭：浮窗→详情→返回 true 拦截，false 交还系统退出）。

---

## 4. setData JSON 结构 v2（D-30 现版）

```
inv[]: { id(1..N 稳定数字映射), name, emoji, sym, tag, catKey, zone('chill'|'freeze'|'ambient'),
         qty, unit, fresh{ c(三色 #5FA45C/#E9B960/#C4624A 纪律), w, pct, note, days } }
recipes{}: 键 "1".."n"，值 { name, time, emoji, badge(0/1 per-recipe，页面开关驱动显隐),
           tags[], steps[], ing[{ name, emoji, sym, catKey, unit, base }] }
iconSet: "a"(本色拟物) | "b"(线描) | "c"(贴纸)
```

- 菜谱语义：原生侧维护**食谱池**（`filesDir/recipe_pool.json`，一次生成 30 道）；页面"刷新"= 从池随机切 5 道（秒切不重生成）；**烘焙**（关东煮插位/徽章定死 once）随池持久化，回灌只重放，防闪动。
- 徽章：per-recipe 由原生计算（uses∩临期），页面只按开关显隐。
- 页面侧旧字段降级：缺 `fresh.days` 等旧页面字段照常显示（只多不少原则）。

---

## 5. 热更协议（S1-S4，契约 contract=1）

```
发布（人工）: node D:\work2\小厨-定稿\publish.mjs <key>
   → POST ui-publish  (X-Publish-Key; body {html})
   → 服务端 sha256 + 版本递增(1.0.x) → ui_releases 表 → VERSIONS.json server.* 自动记录
检查:   UiUpdater.checkForUpdate → GET ui-release?cv=1&lv=<本地ver>   (Bearer token)
下载:   GET ui-page?ver=<v>       (Bearer token) → 本地算 sha256 与快照比对（防竞态/防篡改）
落盘:   files/web/{index.html,version.json}（旧版备份 prev.{html,version.json}）
加载:   Capacitor server.url=http://127.0.0.1:8890/ → LocalWebServer 优先 files/web → 出厂 assets/public
        缓存纪律: LocalWebServer 响应 no-store（禁止陈旧缓存）；启动强制 reload
        熔断: 页面未就绪 page_fail≥3 → 自动回滚 prev 并 recreate
真相:   UI 版本 = files/web/version.json；屏幕显示必须与磁盘一致（09-04 教训）
安全:   sha256 验签 + 私钥发布门；页面调桥（可读写数据）→ 绝不公开桶直发
```

---

## 6. 环境基线（页面头部注释不可移除）

EdgeToEdge / insets 单点推送 / vh+dvh / tap-highlight 透明 / user-select:none / sticky 不透明顶层 / WebView 手势一律 touch 事件（真机 Pointer Events 不派发，实测）。

---

## 7. 契约变更流程（防再漂移）

1. **三处同步**：页面 emit 表 = 桥 when 分支 = 本文档；改一处必须同步另两处。
2. 数据结构变更 → `contract+1` + APK 发版（旧页面不再兼容则提示升级，不崩）。
3. 发布后跑 `sync-page.ps1 -VerifyOnly`（页面内容链）+ 真机 10 步矩阵（据《开发纪律与自查矩阵》）。
4. **自查命令**（每次变更后跑）：
   - 页面发射表：`Select-String '小厨-重构版.html' -Pattern "emit\('([a-z-]+)'"` 去重
   - 桥分支表：WebAppBridge.kt `when(type)` 分支清单
   - 两表与本文 §2 逐行比对。

---

## 8. 旧文档状态（导航表）

| 文档 | 状态 |
|---|---|
| `docs/webview-api.md` | ❌ 已废弃（Compose 时代 `FridgeBridge` 同步方法+`__FRIDGE_EVENT__`），**不要参考** |
| `D:\work2\小厨-WebView\对接命令.md` | 🟡 部分过时（08-29 版本：WebView 壳/assets/app/11 类事件）；事件表、注入序列以本文为准；D-* 编号仅作历史引用 |
| `docs/speech-api.md` | 🟡 冻结参考（`:speech` 模块未被 :app 依赖，不进 APK；模型已移出 git，见工作区 Vosk HANDOVER） |
| `docs/architecture.md` | 🟡 历史参考（Compose 时代分层；当前宿主架构见本文 §1 与 `docs/版本链.md`） |
| `README.md` | ✅ 已更新指向现状 |
