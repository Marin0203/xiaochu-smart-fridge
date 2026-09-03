// ============================================================================
// ai-parse: 自然语言(语音转写文本) → 结构化食材 JSON
// 【单文件自包含版】—— 专为 Supabase Dashboard 手动部署设计:
// 无需 _shared 目录, 一个文件搞定。DeepSeek Key 只存本函数(服务端 Secrets)。
// ============================================================================

// ---------- 提示词 (与客户端版保持同步) ----------
const SYSTEM_PARSE = `
你是「家庭智能冰箱」的食材入库助手。
任务: 把用户口语/文字里的食材提取为结构化 JSON, 严格执行。

输出格式 (字段名必须完全一致, 只能有这些字段):
[{"name":"食材名","quantity":数字,"unit":"单位","zone":"FRIDGE|FREEZER|PANTRY","shelfLifeDays":预计保质天数}]

规则:
1. zone 判断: 常温粮油干货/调味 → PANTRY; 生鲜肉类蔬菜、需冷藏熟食饮品 → FRIDGE; 冷冻食品 → FREEZER
2. 数量换算常用单位: 半斤=250克, 一斤=500克, 一盒/一袋/一瓶保持原单位
3. shelfLifeDays 按常识估计: 猪肉/牛肉 3, 鸡肉 4, 鱼肉 2, 番茄 5, 鸡蛋 30, 冷藏牛奶 7, 蔬菜 4~7
4. 只输出 JSON 本身, 不要解释、不要 Markdown 代码块; 若接口要求对象形式, 返回 {"items":[...]}
5. 识别不出任何食材时返回 []
`;

function buildParseUser(text: string): string {
  return `用户说: "${text}"\n请输出结构化 JSON 数组。`;
}

// ---------- LLM 调用 (OpenAI 兼容协议) ----------
async function chat(system: string, user: string): Promise<string> {
  const apiKey = Deno.env.get("DEEPSEEK_API_KEY") ?? "";
  const base = Deno.env.get("DEEPSEEK_BASE_URL") ?? "https://api.deepseek.com";
  const model = Deno.env.get("DEEPSEEK_MODEL") ?? "deepseek-chat";
  const resp = await fetch(`${base}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify({
      model,
      temperature: 0.1,
      max_tokens: 2500,
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

// ---------- 容错 JSON 提取 (与客户端 ResilientJson 同逻辑) ----------
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
  if (ei < 0 && eo < 0) return raw; // 截断输出: 交给原样尝试
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
    for (const k of ["items", "recipes", "ingredients", "list", "data"]) {
      if (Array.isArray(value[k])) return value[k].filter(isRecord);
    }
  }
  return [];
}
function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === "object" && !Array.isArray(v);
}
function toNum(v: unknown): number {
  if (typeof v === "number") return v;
  if (typeof v === "string" && v.trim() !== "") return parseFloat(v);
  return NaN;
}
const VALID_ZONES = ["FRIDGE", "FREEZER", "PANTRY"];
function sanitizeItems(value: unknown): Record<string, unknown>[] {
  const out: Record<string, unknown>[] = [];
  for (const m of asMapList(value)) {
    const name = String(m.name ?? "").trim();
    if (!name) continue;
    const qty = toNum(m.quantity);
    const unit = String(m.unit ?? "份").trim() || "份";
    const zoneRaw = String(m.zone ?? "").trim().toUpperCase();
    const zone = VALID_ZONES.includes(zoneRaw) ? zoneRaw : "FRIDGE";
    let shelf = 3;
    const sd = toNum(m.shelfLifeDays);
    if (Number.isFinite(sd) && sd > 0) shelf = Math.min(3650, Math.max(1, Math.round(sd)));
    const prev = out.find((o: any) => o.name === name && o.zone === zone);
    if (prev) prev.quantity = Math.round((prev.quantity + (qty > 0 ? qty : 1)) * 10) / 10;
    else out.push({ name, quantity: Math.round((qty > 0 ? qty : 1) * 10) / 10, unit, zone, shelfLifeDays: shelf });
  }
  return out;
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

  // 需要登录用户的 JWT (第二道闸门; 用 GoTrue 的 /user 接口校验, 不额外引依赖)
  const resp = await fetch(`${(Deno.env.get("SUPABASE_URL") ?? "").replace(/\/$/, "")}/auth/v1/user`, {
    headers: {
      apikey: Deno.env.get("SUPABASE_ANON_KEY") ?? "",
      Authorization: req.headers.get("Authorization") ?? "",
    },
  });
  if (resp.status !== 200) return json({ error: "unauthorized" }, 401);

  let text = "";
  try { ({ text } = await req.json()); } catch { return json({ error: "invalid body" }, 400); }
  if (typeof text !== "string" || !text.trim()) return json({ error: "empty text" }, 400);

  try {
    const content = await chat(SYSTEM_PARSE, buildParseUser(text.trim()));
    const items = sanitizeItems(robustParse(content)); // 容错清洗: 脏输出收敛为合法数组
    return json({ ok: true, items });
  } catch (e) {
    return json({ ok: false, error: String(e) }, 502);
  }
});
