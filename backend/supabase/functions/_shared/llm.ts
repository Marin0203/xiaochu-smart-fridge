// OpenAI 兼容协议的统一调用 (DeepSeek / Qwen / Ollama / vLLM 均可用)

export interface ChatOptions {
  temperature?: number;
  maxTokens?: number;
}

export async function chat(system: string, user: string, opts: ChatOptions = {}): Promise<string> {
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
      temperature: opts.temperature ?? 0.1,
      max_tokens: opts.maxTokens ?? 2500,
      response_format: { type: "json_object" },
      messages: [
        { role: "system", content: system },
        { role: "user", content: user },
      ],
    }),
  });

  if (!resp.ok) {
    throw new Error(`LLM upstream ${resp.status}: ${await resp.text()}`);
  }
  const data = await resp.json();
  const content = data?.choices?.[0]?.message?.content;
  if (typeof content !== "string" || !content.trim()) {
    throw new Error("LLM empty content");
  }
  return content;
}
