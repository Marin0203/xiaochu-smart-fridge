// LLM 提示词 —— 单一权威来源。
// ⚠️ 与 app/lib/ai/prompts.dart 保持同步 (direct 模式用 Dart 版)。

export const SYSTEM_PARSE = `
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

export function buildParseUser(text: string): string {
  return `用户说: "${text}"\n请输出结构化 JSON 数组。`;
}

export const SYSTEM_RECIPE = `
你是资深家庭厨师, 任务是根据当前库存设计菜谱: 优先消耗临期食材, 输出 3 道能立即制作的家常菜。

严格输出 JSON (字段名完全一致):
{"recipes":[
  {"title":"菜名","minutes":制作分钟数,"uses":["消耗的临期食材名"],
   "ingredients":[{"name":"食材","amount":"用量(需补充的标明 需补充:xxx)"}],
   "steps":["第1步(可以用 **加粗**/列表 等 Markdown)", ...],
   "tips":"小贴士(可选, 简短)"}
]}

规则:
1. 每道菜必须至少吃掉 1 种 uses 列出的临期食材; 3 道菜合计尽量覆盖全部临期食材
2. 步骤用中文、简洁、可按顺序执行
3. 只输出 JSON, 不要解释; 若接口要求对象形式, 同样按上面结构返回
`;

export function buildRecipeUser(
  expiring: { name: string; quantity: number; unit: string; freshness_percent: number; days_left: number }[],
  context: { name: string; quantity: number; unit: string; freshness_percent: number; days_left: number }[],
): string {
  let s = "【必须优先消耗的临期食材】\n";
  for (const e of expiring) {
    s += `- ${e.name} x${e.quantity}${e.unit} (新鲜度 ${e.freshness_percent}%, 剩约 ${e.days_left} 天)\n`;
  }
  if (context.length > 0) {
    s += "\n【其他当前库存(可选用)】\n";
    for (const c of context) {
      s += `- ${c.name} x${c.quantity}${c.unit}\n`;
    }
  }
  return s;
}
