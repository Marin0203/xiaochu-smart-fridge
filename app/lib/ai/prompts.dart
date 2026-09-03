/// LLM 提示词 (Dart 版)。
///
/// ⚠️ 与 backend/supabase/functions/_shared/prompts.ts 必须保持同步:
///  - edge 模式 (线上默认) 使用 TS 版
///  - direct 模式 (本地直连/自部署) 使用 Dart 版
class AiPrompts {
  AiPrompts._();

  static const String parseSystem = '''
你是「家庭智能冰箱」的食材入库助手。
任务: 把用户口语/文字里的食材提取为结构化 JSON, 严格执行。

输出格式 (字段名必须完全一致, 只能有这些字段):
[{"name":"食材名","quantity":数字,"unit":"单位","zone":"FRIDGE|FREEZER|PANTRY","shelfLifeDays":预计保质天数}]

规则:
1. zone 判断: 常温粮油干货/调味 → PANTRY; 生鲜肉类蔬菜、需冷藏熟食饮品 → FRIDGE; 冷冻食品 → FREEZER
2. 数量换算常用单位: 半斤=250克, 一斤=500克, 一盒/一袋/一瓶保持原单位
3. shelfLifeDays 按常识估计: 猪肉/牛肉 3, 鸡肉 4, 鱼肉 2, 番茄 5, 鸡蛋 30, 冷藏牛奶 7, 蔬菜 4~7
4. 只输出 JSON 本身, 不要解释、不要 Markdown 代码块; 若接口要求对象形式, 返回 {"items":[...]}
5. 识别不出任何食材时返回 []''';

  static String buildParseUser(String text) =>
      '用户说: "$text"\n请输出结构化 JSON 数组。';

  static const String recipeSystem = '''
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
3. 只输出 JSON, 不要解释; 若接口要求对象形式, 同样按上面结构返回''';

  static String buildRecipeUser({
    required List<Map<String, dynamic>> expiring,
    List<Map<String, dynamic>> context = const [],
  }) {
    final buf = StringBuffer('【必须优先消耗的临期食材】\n');
    for (final e in expiring) {
      buf.writeln(
          '- ${e['name']} x${e['quantity']}${e['unit']} (新鲜度 ${e['freshness_percent']}%, 剩约 ${e['days_left']} 天)');
    }
    if (context.isNotEmpty) {
      buf.writeln('\n【其他当前库存(可选用)】');
      for (final c in context) {
        buf.writeln('- ${c['name']} x${c['quantity']}${c['unit']}');
      }
    }
    return buf.toString();
  }
}
