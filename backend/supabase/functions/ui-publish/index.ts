// ============================================================================
// ui-publish: 发布新版本（POST {html, ver?}，header X-Publish-Key 校验）
//   verify_jwt 关闭（部署用 --no-verify-jwt）；安全靠 PUBLISH_SECRET（Edge Secrets）
//   service role 写 ui_releases：sha256 自动计算、ver 自动递增
// ============================================================================

Deno.serve(async (req) => {
  try {
    if (req.method !== "POST") return json({ ok: false, error: "method not allowed" }, 405);

    const secret = Deno.env.get("PUBLISH_SECRET") ?? "";
    const provided = req.headers.get("X-Publish-Key") ?? "";
    if (!secret || provided !== secret) return json({ ok: false, error: "publish key invalid" }, 403);

    let body: { html?: string; ver?: string };
    try { body = await req.json(); } catch {
      return json({ ok: false, error: "invalid body" }, 400);
    }
    const html = (body?.html ?? "").trim();
    if (!html) return json({ ok: false, error: "html empty" }, 400);

    const encoder = new TextEncoder();
    const digest = await crypto.subtle.digest("SHA-256", encoder.encode(html));
    const sha256 = Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, "0")).join("");

    const supabaseUrl = (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/$/, "");
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    if (!serviceKey) return json({ ok: false, error: "missing SUPABASE_SERVICE_ROLE_KEY" }, 500);

    let ver = body.ver ?? "";
    if (!ver) {
      const r = await fetch(
        `${supabaseUrl}/rest/v1/ui_releases?select=ver&order=id.desc&limit=1`,
        { headers: { apikey: serviceKey, Authorization: `Bearer ${serviceKey}` } },
      );
      if (r.status !== 200) {
        return json({ ok: false, error: `ui_releases 查询失败 ${r.status}: ${(await r.text()).slice(0, 200)}` }, 502);
      }
      const rows = await r.json();
      const latest = (Array.isArray(rows) && rows[0]) ? rows[0].ver : "1.0.0";
      const parts = latest.split(".").map(Number);
      parts[2] = parts[2] + 1;
      ver = parts.join(".");
    }

    const insert = await fetch(`${supabaseUrl}/rest/v1/ui_releases`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        apikey: serviceKey,
        Authorization: `Bearer ${serviceKey}`,
        Prefer: "return=minimal",
      },
      body: JSON.stringify({ ver, contract: 1, sha256, size: encoder.encode(html).length, html }),
    });
    if (insert.status < 200 || insert.status >= 300) {
      return json({ ok: false, error: `insert failed ${insert.status}: ${(await insert.text()).slice(0, 300)}` }, 502);
    }
    return json({ ok: true, ver, sha256 });
  } catch (e) {
    return json({ ok: false, error: `unexpected: ${String(e)}` }, 500);
  }
});

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
