# Supabase 搭建指南 (零基础版)

免费额度：**2 个项目**，每个 500MB 数据库 / 5GB 出流量 / 500MB 存储 —— 三口之家用「小厨」绰绰有余。

## 一、创建项目

1. [supabase.com](https://supabase.com) → `Sign in`（可用 GitHub 账号）→ `New project`
2. 填名称（如 `fridge`）、**数据库密码（记下来，别丢）**、Region 选 **Singapore**（离国内较近）
3. 等 1~2 分钟初始化完成

## 二、执行建表 + 权限 SQL

1. 左侧 `SQL Editor` → `New query`
2. 复制 [`backend/supabase/migrations/0001_init.sql`](../backend/supabase/migrations/0001_init.sql) 全文粘贴 → `Run`
3. 检查 `Table Editor` 应出现 3 张表：`families` / `family_members` / `ingredients`
4. 该脚本已完成：建表、索引、**RLS 策略**、邀请码 RPC、**Realtime 发布**（`supabase_realtime` 已订阅 `ingredients` 表）—— 无需手动开关任何 Realtime 选项

## 三、拿到客户端配置

`Project Settings → API`：

| 值 | 位置 | 用途 |
|---|---|---|
| Project URL | `https://xxxx.supabase.co` | app 的 `SUPABASE_URL` |
| publishable key | `sb_publishable_...` (新版) 或 anon key `eyJ...` (旧版) | app 的 `SUPABASE_KEY` |

> 2025 起新项目默认显示 **publishable key**（`sb_publishable_` 开头），它直接替代旧 anon key，
> 本客户端按 `apikey` 头原样透传，两种格式都兼容。
> ⚠️ 永远不要用 `sb_secret_...`（secret key，原 service_role）做客户端配置。

本项目 `android/local.properties` 已填好（项目 ref `juxlfhrgywurttzzxgvr`）：

## 四、部署两个 AI Edge Function

自动鉴权默认开启：客户端调用时必须带登录用户的 JWT（`supabase_flutter` 自动处理）。

### 方式 A：Supabase CLI（推荐，可版本管理函数代码）

```bash
# 1. 安装 CLI（本机需 Node ≥ 18; 或 npx 方式免装）
npm install -g supabase
supabase login                      # 浏览器授权

# 2. 绑定项目
cd backend/supabase
supabase link --project-ref <PROJECT_REF>   # ref = URL 中 xxxx 那段 20 位短码

# 3. 放密钥（DEEPSEEK_API_KEY 必填; 两个可选）
supabase secrets set DEEPSEEK_API_KEY=sk-xxxxxxxx
# supabase secrets set DEEPSEEK_MODEL=deepseek-chat
# supabase secrets set DEEPSEEK_BASE_URL=https://api.deepseek.com

# 4. 部署（会自动带上 _shared/ 目录）
supabase functions deploy ai-parse
supabase functions deploy ai-recipe

# 5. 本地调试（可选）
supabase functions serve --env-file /path/to/.env   # .env 里写 DEEPSEEK_API_KEY=...
```

### 方式 B：Dashboard 手动粘贴

1. `Edge Functions` → `New function` → 名字 `ai-parse` → 粘贴 [`functions/ai-parse/index.ts`](../backend/supabase/functions/ai-parse/index.ts)；再建 `ai-recipe`
2. 两个函数中 `_shared/` 的三个文件也要进入同项目目录结构（`_shared/prompts.ts`、`_shared/llm.ts`、`_shared/json_extract.ts`）
3. `Edge Functions` → `Secrets` → 添加 `DEEPSEEK_API_KEY`
4. 每个函数 `Deploy` — 完成后 `Functions` 列表里 `&` 图标旁应显示 online

### 验证函数

```bash
curl -X POST https://<PROJECT_REF>.supabase.co/functions/v1/ai-parse \
  -H "Authorization: Bearer <登录用户的JWT>" \
  -H "Content-Type: application/json" \
  -d '{"text":"今天买了半斤猪肉和三个番茄"}'
# 期望: {"ok":true,"items":[{"name":"猪肉","quantity":250,...},{"name":"番茄",...}]}
```

## 五、常见问题

| 症状 | 解法 |
|---|---|
| `failed to refresh token` / 403 | anon key 抄错，或 App 用了 service_role key |
| RLS 返回空列表 | 没登录 / 未加入家庭（先走 onboarding 创建或加入） |
| 实时收不到更新 | 确认执行过 `alter publication supabase_realtime add table public.ingredients;`（已含在迁移 SQL 里） |
| `publication ... does not exist` | 项目已建表前初始化过 realtime 时偶发；在 SQL Editor 跑一次迁移文件末尾两行即可 |
| 函数 401 | 客户端未登录就调用（应用路由已保证，手工测 curl 需带 JWT） |
| 函数 502 / error | 看 `Edge Functions → 该函数 → Logs`；最常见是 `DEEPSEEK_API_KEY` 未设置或调用超时 |

## 六、启用邮件验证（可选）

默认注册即登录。若想要「邮箱验证后登录」，Auth → Providers → Email → 打开 Confirm email，此时客户端行为已兼容（提示用户验证后登录）。
