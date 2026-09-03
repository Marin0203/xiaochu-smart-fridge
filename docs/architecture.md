# 架构文档

## 1. 分层与职责

```
┌ UI (screens/widgets)          纯展示 & 手势; 不碰数据源
├ ViewModel (ChangeNotifier)    状态机; 唯一触发业务动作的地方; 订阅仓库 Stream
├ Repository (门面)             UI 唯一入口; 屏蔽本地/云端细节
├ SyncService (核心!)           本地优先编排: 写放大到云端 / 云端变更回灌本地 / LWW 冲突仲裁
├ FridgeLocalDb (SQLite)        本地真相源 + outbox 待推送队列 + 同步元数据
├ SupabaseApi                   用户态 CRUD + Realtime 订阅 (RLS 全程生效)
└ AiService (接口)              Edge 代理(默认) / OpenAI 兼容直连(本地) 双实现
```

**关键原则：UI 永远读写本地 SQLite**。网络是「尽力而为的镜像」，不是依赖。

## 2. 权威数据流

### 2.1 写入路径 (语音入库为例)

```
说话 → NativeSpeechService(ASR) → 文本
     → AiService.parseNaturalLanguage(文本)        [LLM, 容错JSON清洗]
     → 草稿列表 [{name,quantity,unit,zone,shelfLifeDays}]
     → 确认 → SyncService.addDrafts
           → SQLite upsert (立即生效, UI 秒响应) 
           → outbox 追加 'upsert' 记录
           → 尽力 push() (失败→30s周期重试, 无需人工干预)
     → 其他人: Supabase Realtime 事件 → 各自本地合入 → UI 刷新
```

### 2.2 冲突仲裁 (行级 LWW)

| 场景 | 判定 | 结果 |
|---|---|---|
| 推送时, 云端行 `updated_at` 更新 | 云胜 | 回写本地, 丢弃本地改动 |
| 推送时, 本地 `updated_at` 更新 | 本地胜 | upsert 覆盖云端 |
| 拉取时, 本地有未推送变更且更新 | 本地胜 | 保留本地, 交给 push 仲裁 |
| Realtime 事件, 云端更新 | 云胜 | 覆盖本地 |
| 删除 vs 云更新 | 云更新 | 删除让位, 保留云行 |

`updated_at` 的**唯一权威是客户端写入**（服务端不加触发器覆盖），这是 LWW 正确性的前提。

**为什么不用 CRDT**：CRDT 适合「多人同时编辑同一文档/字段」。库存场景是「低频小行写入」，且用户需要的是「最后一次操作赢」的直觉规则。若将来做「多端同时修改数量」等强并发需求，可对 `quantity` 引入保留语义（如 G-Counter 累加），对其它字段维持 LWW —— 这是推荐的演进路径，不是现在的复杂度负担。

## 3. 新鲜度算法 (规格实现)

```
Freshness% = (shelfLifeDays - elapsedDays) / shelfLifeDays * 100
elapsedDays = (now - purchasedAt) / 86400000   // 浮点, 支持小时级平滑
clamp [0, 100]

> 50%        → Fresh        🟢
20% ~ 50%(含)→ Need Consume 🟡
< 20%        → Expiring Soon🔴
= 0% (超期)  → Expired      🔴(补充态)
```

- 纯函数、可单测 (`test/freshness_test.dart`)，VM 每分钟重算一次保持进度条时间感。
- 列表排序：新鲜度升序 → **最临期永远在最上面**。

## 4. AI 容错链路 (容错性设计)

LLM 输出不可信 → 三道防线：

```
① Edge Function 收货: sanitizeIngredientItems / sanitizeRecipePlan
   (剥离fence→截取子串→修复尾逗号/全角标点→白名单校验→同名合并)
② 客户端二次校验: ResilientJson.parseIngredientDrafts 重跑同一套收敛
   (任何一环坏了都不至于把脏数据写进库存)
③ 渲染兜底: 食谱结构失败 → rawMarkdown 直接渲染;
            AI 服务不可用 → 本地规则菜谱 (功能永不空白)
```

保留字段级校验缺省值：`quantity<=0→1`、非法 `zone→FRIDGE`、非法 `shelfLifeDays→3`、空 `name→丢弃`。

## 5. 密钥与权限分离 (安全清单)

| 秘密 | 存放处 | 客户端可见? |
|---|---|---|
| Supabase `anon key` | dart-define | 是（本应如此，RLS 保护） |
| Supabase `service_role key` | **永不使用** | 否 |
| DeepSeek API Key | Supabase Secrets (`DEEPSEEK_API_KEY`) | 否 |
| 用户 JWT | Supabase Auth 自动管理 | 客户端持有（短期） |

RLS 细粒度：`ingredients` 按 `family_id ∈ 我的家庭` 过滤；`families` 仅成员可读；邀请码查询走 `security definer` RPC（`join_family`），避免任何登录用户枚举他人邀请码。

## 6. 离线行为

| 动作 | 离线时 | 恢复后 |
|---|---|---|
| 录入/消耗/删除 | 本地立即生效, outbox 累积 | 30s 内自动推送 (LWW 仲裁) |
| 看库存 | 全量可用 (本地镜像) | Realtime 增量合入 |
| AI 解析/食谱 | 不可用 → 提示 | 手动重试; 食谱有本地降级 |

## 7. 演进路线图

- [ ] 软删除 + 30 天回收站（抵消误删）
- [ ] 菜谱 → 一键扣减库存（勾选已做 → 自动 consume 对应食材）
- [ ] 采购清单（菜谱「需补充」→ 清单同步全家）
- [ ] 增量拉取游标（`updated_at > last_pull` + 删除墓碑）
- [ ] 通知推送（临期阈值触发）
- [ ] 多家庭支持（当前一人一家庭，数据模型天然支持）
- [ ] Firebase 路径：重写 `SupabaseApi` 与 `SyncService` 适配层即可，域层/AI 层零改动
