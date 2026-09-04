# WebView 数据桥协议 v0.1（WebUI 对接文件的对齐基准）

> ⚠️ **已废弃（2026-09-04）**：本文是 Compose 时代旧契约（`window.FridgeBridge` 同步方法 + `__FRIDGE_EVENT__`），与现行事件桥 **完全不符**。
> 现行契约唯一权威 = **`docs/bridge-contract.md`**。本文仅作历史存档，勿参考、勿按此对接。

> WebUI 方案: Android WebView 加载 assets/webapp/ 下的 HTML（UI 线产出, 去壳三页版），
> 通过本桥与原生数据层(AppServices)双向通信。**数据层不变, 只有 UI 层换为 HTML。**
> 接驳约定: HTML 以 `window.FridgeBridge` 为桥对象（Android 侧注入名）。
> 所有回调均在主线程; 异步结果通过「事件推送」返回。

## 一、JS → Android（`@JavascriptInterface` 方法）

全部同步返回 JSON 字符串; 耗时操作返回立即, 结果走事件。

| 方法 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `ping()` | — | `"pong"` | 桥连通性自检 |
| `getState()` | — | JSON (见 §三) | 全量快照: 库存/设置/同步状态 — 页面启动时调用一次 |
| `getSyncStatus()` | — | `{"lastSyncAt": ms, "lastError": "..."}` | 同步状态刷新 |
| `syncNow()` | — | `{}` | 触发同步; 完成后推事件 `sync-state` |
| `addText(text)` | text | `{}` | AI 解析自然语言持仓 → 完成推事件 `parse-result` |
| `confirmDrafts(jsonText)` | 草稿 JSON 数组 | `{}` | 确认入库 (addDrafts) → 推事件 `items-updated` |
| `saveItem(id, jsonText)` | id + 补丁(quantity/unit/zone/category/name) | `{}` | 编辑保存 → 推 `items-updated` |
| `consumeItem(id, qty)` | id + Double | `{}` | 扣除 → 推 `items-updated` |
| `removeItem(id)` | id | `{}` | 删除 → 推 `items-updated` |
| `changeZone(id, zone)` | id + "FRIDGE/FREEZER/PANTRY" | `{}` | 移动分区 → 推 `items-updated` |
| `getRecipes(mode)` | "normal"/"expiring" | `{}` | 生成菜谱 → 完成后推事件 `recipes-updated` |
| `getLocalRecipes()` | — | 本地 5 道菜谱 JSON | 离线兜底菜谱 (AI 不可用时的展示) |
| `setSetting(key, value)` | key: darkMode/skin/iconSet/aheadHours/reminderOn | `{}` | 设置变更 → 推 `settings-changed` |
| `healthCheck()` | — | 文本报告 | 系统体检 |
| `copyText(text)` | text | `{}` | 写剪贴板 |
| `requestNotifyPermission()` | — | `{}` | 通知权限 (Android 13+) |

## 二、Android → JS 事件（`window.__FRIDGE_EVENT__(name, payloadJson)`）

| name | payload | 时机 |
|---|---|---|
| `items-updated` | 全量 items JSON | 任意库存变更/同步合并后 |
| `recipes-updated` | RecipePlan JSON (含 recipes/fromFallback/error) | getRecipes 完成 |
| `parse-result` | `{"ok":bool,"drafts":[...],"error":""}` | addText 完成 |
| `sync-state` | `{"lastSyncAt":ms,"lastError":""}` | 同步开始/完成/失败 |
| `settings-changed` | `{"darkMode":bool,"skin":"glass","iconSet":"line","aheadHours":24,"reminderOn":true}` | 设置变更 (含原生侧) |
| `toast` | 文本 | 原生提示 (HTML 有自己的 toast 时可不订阅) |

## 三、数据模型（JSON）

### Ingredient
```json
{"id":"..","family_id":"..","name":"番茄","category":"蔬菜","quantity":3,"unit":"个",
 "zone":"FRIDGE","purchased_at":"2026-08-29T..Z","shelf_life_days":3,
 "added_by_user_name":"小厨","updated_at":"2026-08-29T..Z",
 "freshness_percent":92,"freshness_status":"FRESH","remaining_days":2}
```
> fresh 字段由原生附算; 其余与后端 snake_case 一致。

### Settings
```json
{"darkMode":false,"skin":"glass","iconSet":"line","aheadHours":24,"reminderOn":true}
```
（skin: glass/classic; iconSet: emoji/line/sticker）

### Recipe / RecipePlan
与现有 `Recipe`(title/minutes/uses/ingredients[{name,amount}]/steps/tips) 一致;
RecipePlan: `{"ok":true,"fromFallback":false,"recipes":[...],"rawMarkdown":null,"error":null}`

### Draft
`{"name":"猪肉","quantity":250,"unit":"克","zone":"FRIDGE","shelfLifeDays":3}`

## 四、对接约定

1. HTML 必须在 `window.onFridgeReady` 或 DOMContentLoaded 时调用 `getState()` 首次渲染
2. 订阅事件用 `window.__FRIDGE_EVENT__` 覆盖（页面上只挂一次, 路由到自己回调）
3. 夜间: 设置事件里 `darkMode` 变化 → HTML 切 `data-theme="dark"` (mockup 已支持)
4. 图标风格: `iconSet` → HTML 切三套图标渲染 (icons.html 已有 A/B/C 逻辑)
5. 未定义的方法/字段以本文件为准; 需要扩展先在本文件追加并同步 UI 线
