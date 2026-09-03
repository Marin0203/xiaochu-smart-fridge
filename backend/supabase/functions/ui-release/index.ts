// ============================================================================
// ui-release v2: UI 热更新发布门（元数据）
// 读 DB ui_releases 最新行 → 契约校验 → 返回 {ver, contract, sha256, size}
// 页面内容由 ui-page?ver= 函数单独直出（避免一次拉 80KB 到清单请求）
// 私有性: ui_releases 表 RLS 全禁（仅 service role 直读），客户端只能经本函数
// ============================================================================

Deno.serve(async (req) => {
  if (req.method !== "GET") return json({ ok: false, error: "method not allowed" }, 405);

  const supabaseUrl = (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/$/, "");
  const anon = Deno.env.get("SUPABASE_ANON_KEY") ?? "";
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  if (!serviceKey) return json({ ok: false, error: "server missing SUPABASE_SERVICE_ROLE_KEY" }, 500);

  // 鉴权（用户 token）
  const authResp = await fetch(`${supabaseUrl}/auth/v1/user`, {
    headers: { apikey: anon, Authorization: req.headers.get("Authorization") ?? "" },
  });
  if (authResp.status !== 200) return json({ ok: false, error: "unauthorized" }, 401);

  const url = new URL(req.url);
  const cv = Number(url.searchParams.get("cv") ?? "0");
  const lv = url.searchParams.get("lv") ?? "";

  // 读最新版本行（service role 直读）
  let row: { ver: string; contract: number; sha256: string; size: number };
  try {
    const r = await fetch(
      `${supabaseUrl}/rest/v1/ui_releases?select=ver,contract,sha256,size&order=id.desc&limit=1`,
      { headers: { apikey: serviceKey, Authorization: `Bearer ${serviceKey}` } },
    );
    if (r.status !== 200) return json({ ok: false, error: `ui_releases 不可读 (HTTP ${r.status})` }, 502);
    const rows = await r.json();
    if (!Array.isArray(rows) || rows.length === 0) return json({ ok: false, error: "还没有发布过任何版本" }, 404);
    row = rows[0];
  } catch (e) {
    return json({ ok: false, error: `ui_releases 查询异常: ${String(e)}` }, 502);
  }

  // 契约校验：客户端桥太旧 -> 拒绝热更（防契约错配爆炸）
  if (cv < row.contract) return json({ ok: false, error: "app too old", contract: row.contract, ver: row.ver });

  // 已是最新
  if (lv === row.ver) return json({ ok: true, upToDate: true, ver: row.ver });

  return json({ ok: true, ver: row.ver, contract: row.contract, sha256: row.sha256, size: row.size });
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
