// 容错 JSON 提取器 (TS 版) —— 与 app/lib/ai/resilient_json.dart 同逻辑:
// 剥离代码块 → 提取首个子串 → 修复尾逗号/全角标点/弯引号 → 校验清洗

export function robustParse(raw: string): unknown {
  if (!raw.trim()) return null;
  const candidates = [
    raw,
    stripCodeFence(raw),
    extractJsonSubstring(raw),
    extractJsonSubstring(stripCodeFence(raw)),
  ];
  for (const c of candidates) {
    if (!c.trim()) continue;
    try {
      return JSON.parse(repair(c));
    } catch {
      // 尝试下一档
    }
  }
  return null;
}

function stripCodeFence(raw: string): string {
  const m = raw.match(/```(?:json)?\s*([\s\S]*?)```/i);
  return m?.[1] ?? raw;
}

function extractJsonSubstring(raw: string): string {
  const si = raw.indexOf("[");
  const ei = raw.lastIndexOf("]");
  const so = raw.indexOf("{");
  const eo = raw.lastIndexOf("}");
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

function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === "object" && !Array.isArray(v);
}

/** 兼容 [..] / {"items":[...]} / {"recipes":[...]} 包装 */
export function asMapList(value: unknown): Record<string, unknown>[] {
  if (Array.isArray(value)) return value.filter(isRecord);
  if (isRecord(value)) {
    for (const k of ["items", "recipes", "ingredients", "list", "data"]) {
      if (Array.isArray(value[k])) return value[k].filter(isRecord);
    }
  }
  return [];
}

// ---------- 食材条目清洗 ----------

export interface IngredientItem {
  name: string;
  quantity: number;
  unit: string;
  zone: string;
  shelfLifeDays: number;
}

const VALID_ZONES = ["FRIDGE", "FREEZER", "PANTRY"];

export function sanitizeIngredientItems(value: unknown): IngredientItem[] {
  const out: IngredientItem[] = [];
  const seen = new Set<string>();
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

    const key = `${name}|${zone}`;
    const prev = out.find((o) => o.name === name && o.zone === zone);
    if (prev) {
      prev.quantity = round1(prev.quantity + (qty > 0 ? qty : 1)); // 同名同区合并
    } else {
      seen.add(key);
      out.push({ name, quantity: round1(qty > 0 ? qty : 1), unit, zone, shelfLifeDays: shelf });
    }
  }
  return out;
}

function toNum(v: unknown): number {
  if (typeof v === "number") return v;
  if (typeof v === "string" && v.trim() !== "") return parseFloat(v);
  return NaN;
}

function round1(v: number): number {
  return Math.round(v * 10) / 10;
}

/** 食谱结构兜底: 解析失败时保留原文, 让客户端直接渲染 Markdown */
export function sanitizeRecipePlan(raw: string): { recipes?: unknown[]; markdown?: string } {
  const list = asMapList(robustParse(raw));
  if (list.length > 0) return { recipes: list };
  return { markdown: raw };
}
