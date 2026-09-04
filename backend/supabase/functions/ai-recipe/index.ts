// ============================================================================
// ai-recipe: 临期食材约束 → 3 道菜谱 + 详细步骤 (Markdown)
// 【单文件自包含版】—— 专为 Supabase Dashboard 手动部署设计。
// 结构化成功返回 recipes; 失败返回 markdown 原文, 客户端照样渲染 (容错)。
// ============================================================================

// ---------- 提示词 (与客户端版保持同步) ----------
const SYSTEM_RECIPE_EXPIRING = `
你是资深家庭厨师, 任务是根据当前库存设计菜谱: 优先消耗临期食材, 输出家常菜(客户端每次从生成的池子里随机出 5 道)。
食材充足时力求 30 道; 食材有限时能生成多少算多少, 绝不硬凑含不存在的食材。

严格输出 JSON (字段名完全一致):
{"recipes":[
  {"title":"菜名","minutes":制作分钟数,"uses":["消耗的临期食材名"],
   "ingredients":[{"name":"食材","amount":"用量(需补充的标明 需补充:xxx)"}],
   "steps":["第1步(可以用 **加粗**/列表 等 Markdown)", ...],
   "tips":"小贴士(可选, 简短)"}
]}

规则:
1. 每道菜必须至少吃掉 1 种 uses 列出的临期食材; 尽量一次覆盖全部临期食材; 30 道尽量风格/食材组合多样化
2. 若库存存在临期食材: 至多 10 道以临期食材为主料(其余自由搭配); 若不存在临期食材: 全部正常自由搭配(不生成"必须消耗临期食材"的菜)
3. 步骤用中文、简洁、可按顺序执行
4. 只输出 JSON, 不要解释; 若接口要求对象形式, 同样按上面结构返回
5. 【求新·最高优先】本批基调=__MOOD__。全部菜围绕该基调；与【上次已做过的菜】相比: 最多允许 2 道同名且必须换做法/配菜, 其余必须为全新菜品(同名不同法也算新)；绝不允许整批与上次相同

约束 (最高优先级, 必须严格遵守):
1. 只能使用用户给出的食材 + 家常调料(油盐酱醋糖葱姜蒜等), 不得添加用户未列出的主料或特殊配料
2. 若这些食材搭配不出任何常规做法, 直接输出: 这些食材无法搭配出常规菜，建议补充 X 或改做 Y; 绝不硬编菜谱
3. 每道菜必须给出: 菜名、口味、主料用量、按时间顺序的步骤、关键火候提示
4. 禁止创意菜/融合菜/养生搭配; 只做中式家常菜真实做法
`;

const SYSTEM_RECIPE_NORMAL = `
你是资深家庭厨师, 根据用户当前有哪些食材, 自由搭配不重样的家常菜 (炒菜/汤/主食/凉拌都可以; 客户端每次从生成的池子里随机出 5 道)。
食材充足时力求 30 道; 食材有限时能生成多少算多少, 绝不硬凑含不存在的食材。

严格输出 JSON (字段名完全一致):
{"recipes":[
  {"title":"菜名","minutes":制作分钟数,"uses":["这道菜主要用到的食材名"],
   "ingredients":[{"name":"食材","amount":"用量(需补充的标明 需补充:xxx)"}],
   "steps":["第1步(可以用 **加粗**/列表 等 Markdown)", ...],
   "tips":"小贴士(可选, 简短)"}
]}

规则:
1. 尽量只用现有食材, 缺的少量用料标"需补充:xxx"; 30 道风格尽量不同(炒/汤/主食/凉拌/烧烩都安排)
2. 若库存存在临期食材: 至多 10 道以临期食材为主料(其余自由搭配); 若不存在临期食材: 全部正常自由搭配(不生成"必须消耗临期食材"的菜)
3. 步骤用中文、简洁、可按顺序执行
4. 只输出 JSON, 不要解释; 若接口要求对象形式, 同样按上面结构返回
5. 【求新·最高优先】本批基调=__MOOD__。全部菜围绕该基调；与【上次已做过的菜】相比: 最多允许 2 道同名且必须换做法/配菜, 其余必须为全新菜品(同名不同法也算新)；绝不允许整批与上次相同

约束 (最高优先级, 必须严格遵守):
1. 只能使用用户给出的食材 + 家常调料(油盐酱醋糖葱姜蒜等), 不得添加用户未列出的主料或特殊配料
2. 若这些食材搭配不出任何常规做法, 直接输出: 这些食材无法搭配出常规菜，建议补充 X 或改做 Y; 绝不硬编菜谱
3. 每道菜必须给出: 菜名、口味、主料用量、按时间顺序的步骤、关键火候提示
4. 禁止创意菜/融合菜/养生搭配; 只做中式家常菜真实做法
`;

type Item = { name: string; quantity: number; unit: string; freshness_percent: number; days_left: number };

/** 每请求随机基调(保底"每次刷新都不一样"): 服务端随机注入, 与客户端无关 */
const MOODS = ["家常快手", "下饭硬菜", "清淡汤菜", "烩煮一锅", "凉拌清爽", "面食主食", "辣味下饭", "酸甜开胃", "蒜香浓郁", "汤羹暖胃", "煎炸小食", "砂锅炖烧"];
function pickMood(): string {
  return MOODS[Math.floor(Math.random() * MOODS.length)];
}

function buildRecipeUser(
  expiring: Item[],
  context: Item[],
  avoid: string[] = [],
): string {
  let s = "【必须优先消耗的临期食材】\n";
  for (const e of expiring) {
    s += `- ${e.name} x${e.quantity}${e.unit} (新鲜度 ${e.freshness_percent}%, 剩约 ${e.days_left} 天)\n`;
  }
  if (context.length > 0) {
    s += "\n【其他当前库存(可选用)】\n";
    for (const c of context) s += `- ${c.name} x${c.quantity}${c.unit}\n`;
  }
  if (avoid.length > 0) {
    s += `\n【上次已做过的菜（请尽量避免完全相同，变化做法/配菜也可以）】\n${avoid.map((a) => `- ${a}`).join("\n")}\n`;
  }
  return s;
}

function buildNormalRecipeUser(context: Item[], avoid: string[]): string {
  let s = "【当前冰箱库存, 请从这些食材自由搭配菜谱: 食材充足力求 30 道, 不足则能出多少算多少, 不要硬凑】\n";
  for (const c of context) s += `- ${c.name} x${c.quantity}${c.unit}\n`;
  if (avoid.length > 0) {
    s += `\n【上次已做过的菜（请尽量避免完全相同，变化做法/配菜也可以）】\n${avoid.map((a) => `- ${a}`).join("\n")}\n`;
  }
  return s;
}

// ---------- LLM 调用 (OpenAI 兼容协议) ----------
async function chat(system: string, user: string): Promise<string> {
  const apiKey = Deno.env.get("DEEPSEEK_API_KEY") ?? "";
  const base = Deno.env.get("DEEPSEEK_BASE_URL") ?? "https://api.deepseek.com";
  const model = Deno.env.get("DEEPSEEK_MODEL") ?? "deepseek-chat";
  const resp = await fetch(`${base}/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
    body: JSON.stringify({
      model,
      temperature: 0.7,
      max_tokens: 12000,
      response_format: { type: "json_object" },
      messages: [
        { role: "system", content: system },
        { role: "user", content: user },
      ],
    }),
  });
  if (!resp.ok) throw new Error(`LLM upstream ${resp.status}: ${await resp.text()}`);
  const data = await resp.json();
  const content = data?.choices?.[0]?.message?.content;
  if (typeof content !== "string" || !content.trim()) throw new Error("LLM empty content");
  return content;
}

// ---------- 容错 JSON 提取 ----------
function robustParse(raw: string): unknown {
  if (!raw.trim()) return null;
  const candidates = [raw, stripCodeFence(raw), extractJsonSubstring(raw), extractJsonSubstring(stripCodeFence(raw))];
  for (const c of candidates) {
    if (!c.trim()) continue;
    try { return JSON.parse(repair(c)); } catch { /* 下一档 */ }
  }
  return null;
}
function stripCodeFence(raw: string): string {
  const m = raw.match(/```(?:json)?\s*([\s\S]*?)```/i);
  return m?.[1] ?? raw;
}
function extractJsonSubstring(raw: string): string {
  const si = raw.indexOf("["), ei = raw.lastIndexOf("]");
  const so = raw.indexOf("{"), eo = raw.lastIndexOf("}");
  if (ei < 0 && eo < 0) return raw;
  if (si !== -1 && (so === -1 || si < so)) return raw.slice(si, ei + 1);
  if (so !== -1 && eo > so) return raw.slice(so, eo + 1);
  return raw;
}
function repair(s: string): string {
  let t = s.replace(/[\uFEFF\u200B\u200C\u200E]/g, "");
  t = t.replace(/[“”„]/g, '"').replace(/[‘’]/g, '"');
  t = t.replace(/：/g, ":").replace(/，/g, ",");
  for (let i = 0; i < 3; i++) {
    const n = t.replace(/,(\s*[}\]])/g, "$1");
    if (n === t) break;
    t = n;
  }
  return t;
}
function asMapList(value: unknown): Record<string, unknown>[] {
  if (Array.isArray(value)) return value.filter(isRecord);
  if (isRecord(value)) {
    for (const k of ["recipes", "items", "ingredients", "list", "data"]) {
      if (Array.isArray(value[k])) return value[k].filter(isRecord);
    }
  }
  return [];
}
function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === "object" && !Array.isArray(v);
}

/** 食谱兜底: 解析失败时保留原文, 客户端直接渲染 Markdown */
function sanitizeRecipePlan(raw: string): { recipes?: unknown[]; markdown?: string } {
  const list = asMapList(robustParse(raw));
  if (list.length > 0) {
    // 字段兜底清洗: AI 缺字段/类型漂移时补默认值, 客户端解析不再拒收
    return {
      recipes: list.map((it, i) => ({
        title: String(it.title ?? `菜谱 ${i + 1}`),
        minutes: typeof it.minutes === "number" ? it.minutes : (typeof it.time === "number" ? it.time : 20),
        ingredients: Array.isArray(it.ingredients) ? it.ingredients : [],
        steps: Array.isArray(it.steps) ? it.steps : [],
        uses: Array.isArray(it.uses) ? it.uses : [],
        tips: typeof it.tips === "string" ? it.tips : "",
      })),
    };
  }
  return { markdown: raw };
}

// ---------- 入参清洗 ----------
function sanitizeIncoming(v: unknown): { name: string; quantity: number; unit: string; freshness_percent: number; days_left: number }[] {
  if (!Array.isArray(v)) return [];
  return v
    .filter((it): it is Record<string, unknown> => !!it && typeof it === "object" && !Array.isArray(it))
    .map((it) => ({
      name: String(it.name ?? "").trim(),
      quantity: typeof it.quantity === "number" ? it.quantity : 1,
      unit: String(it.unit ?? "份").trim() || "份",
      freshness_percent: typeof it.freshness_percent === "number" ? it.freshness_percent : 0,
      days_left: typeof it.days_left === "number" ? it.days_left : 0,
    }))
    .filter((it) => it.name !== "");
}

// ---------- 入口 ----------
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
  if (!Deno.env.get("DEEPSEEK_API_KEY")) {
    return json({ error: "server missing DEEPSEEK_API_KEY (Edge Functions → Secrets 添加)" }, 500);
  }

  const resp = await fetch(`${(Deno.env.get("SUPABASE_URL") ?? "").replace(/\/$/, "")}/auth/v1/user`, {
    headers: {
      apikey: Deno.env.get("SUPABASE_ANON_KEY") ?? "",
      Authorization: req.headers.get("Authorization") ?? "",
    },
  });
  if (resp.status !== 200) return json({ error: "unauthorized" }, 401);

  let body: Record<string, unknown>;
  try { body = await req.json(); } catch { return json({ error: "invalid body" }, 400); }

  // 两种模式: expiring=临期优先(默认) / normal=正常食谱(自由搭配库存)
  const mode = typeof body.mode === "string" ? body.mode : "expiring";
  const expiring = sanitizeIncoming(body.expiring);
  const context = sanitizeIncoming(body.context);
  const avoid = Array.isArray(body.avoid)
      ? body.avoid.filter((a): a is string => typeof a === "string" && a.trim() !== "")
      : [];

  if (mode === "normal") {
    if (context.length === 0 && expiring.length === 0) {
      return json({ ok: false, error: "冰箱里还没东西, 先录入一些食材吧" }, 400);
    }
  } else if (expiring.length === 0) {
    return json({ ok: false, error: "没有临期食材可推荐" }, 400);
  }

  try {
    const system = (mode === "normal" ? SYSTEM_RECIPE_NORMAL : SYSTEM_RECIPE_EXPIRING).replace("__MOOD__", pickMood());
    const user = mode === "normal"
      ? buildNormalRecipeUser(context.length > 0 ? context : expiring, avoid)
      : buildRecipeUser(expiring, context, avoid);
    const content = await chat(system, user);
    const plan = sanitizeRecipePlan(content);
    if (plan.recipes && plan.recipes.length > 0) return json({ ok: true, recipes: plan.recipes });
    return json({ ok: false, markdown: plan.markdown ?? content }); // 容错: 原文给客户端兜底渲染
  } catch (e) {
    return json({ ok: false, error: String(e) }, 502);
  }
});
