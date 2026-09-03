// ============================================================================
// ui-page: 按版本直出页面 HTML（GET ?ver=1.0.1）
// 由 ui-release 清单 → 客户端用此函数拉取页面全文（service role 读表 → text/html）
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
  const ver = url.searchParams.get("ver") ?? "";

  try {
    const r = await fetch(
      `${supabaseUrl}/rest/v1/ui_releases?select=html&ver=eq.${encodeURIComponent(ver)}`,
      { headers: { apikey: serviceKey, Authorization: `Bearer ${serviceKey}` } },
    );
    if (r.status !== 200) return json({ ok: false, error: `查询失败 (HTTP ${r.status})` }, 502);
    const rows = await r.json();
    if (!Array.isArray(rows) || rows.length === 0) return json({ ok: false, error: "版本不存在" }, 404);
    return new Response(rows[0].html, {
      status: 200,
      headers: { "Content-Type": "text/html; charset=utf-8" },
    });
  } catch (e) {
    return json({ ok: false, error: `ui-page 异常: ${String(e)}` }, 502);
  }
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
