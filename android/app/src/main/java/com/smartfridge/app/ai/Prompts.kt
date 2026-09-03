package com.smartfridge.app.ai

/**
 * LLM 提示词 (Kotlin 版)。
 * ⚠️ 与 backend/supabase/functions/_shared/prompts.ts 必须保持同步:
 *  - edge 模式 (线上默认) 使用 TS 版
 *  - direct 模式 (本地直连/自部署) 使用 Kotlin 版
 */
object Prompts {

    val parseSystem = """
你是「家庭智能冰箱」的食材入库助手。
任务: 把用户口语/文字里的食材提取为结构化 JSON, 严格执行。

输出格式 (字段名必须完全一致, 只能有这些字段):
[{"name":"食材名","quantity":数字,"unit":"单位","zone":"FRIDGE|FREEZER|PANTRY","shelfLifeDays":预计保质天数}]

规则:
1. zone 判断（按该食材"最优保藏"选一区，只能 FRIDGE/FREEZER/PANTRY）:
   - 叶菜/浆果/鲜红肉/鲜禽肉/鲜鱼/虾蟹贝/鲜蛋/奶/豆腐/加工肉(香肠培根) → FRIDGE
   - 根茎类(胡萝卜/白萝卜/山药/南瓜等) → FRIDGE（冷藏 3-4 周优于常温）
   - 葱蒜薯芋(土豆/洋葱/大蒜/姜/红薯/芋头) → PANTRY（常温避光 1-2 个月，冷藏易发芽变质）
   - 粮油干货调味(米/面/油/盐/糖/酱油/醋/料酒/香料/茶/咖啡) → PANTRY
   - 明确冷冻食品(冻肉/冻虾/冰淇淋/冻饺) → FREEZER
2. 数量换算常用单位: 半斤=250克, 一斤=500克, 一盒/一袋/一瓶保持原单位
3. shelfLifeDays 按以下保鲜值: 鲜红肉4, 鲜禽肉2, 加工肉品10, 鲜鱼2, 虾蟹贝2, 叶菜4, 根茎21, 葱蒜薯芋60, 浆果6, 热带水果4, 核果14, 鸡蛋28, 巴氏鲜奶6, 豆腐4, 冷冻食品365; 无法判断给 4
4. 只输出 JSON 本身, 不要解释、不要 Markdown 代码块; 若接口要求对象形式, 返回 {"items":[...]}
5. 识别不出任何食材时返回 []
""".trimIndent()

    fun buildParseUser(text: String): String =
        """用户说: "$text"
请输出结构化 JSON 数组。"""

    val recipeSystem = """
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

约束 (最高优先级):
1. 只能使用用户给出的食材 + 家常调料(油盐酱醋糖葱姜蒜等), 不得添加用户未列出的主料或特殊配料
2. 若这些食材搭配不出任何常规做法, 直接输出: 这些食材无法搭配出常规菜，建议补充 X 或改做 Y; 绝不硬编菜谱
3. 每道菜必须给出: 菜名、口味、主料用量、按时间顺序的步骤、关键火候提示
4. 禁止创意菜/融合菜/养生搭配; 只做中式家常菜真实做法
""".trimIndent()

    fun buildRecipeUser(
        expiring: List<com.smartfridge.app.domain.ExpiringItem>,
        context: List<com.smartfridge.app.domain.ExpiringItem> = emptyList(),
    ): String = buildString {
        appendLine("【必须优先消耗的临期食材】")
        for (e in expiring) {
            appendLine("- ${e.name} x${e.quantity}${e.unit} (新鲜度 ${e.freshnessPercent.toInt()}%, 剩约 ${e.daysLeft} 天)")
        }
        if (context.isNotEmpty()) {
            appendLine()
            appendLine("【其他当前库存(可选用)】")
            for (c in context) {
                appendLine("- ${c.name} x${c.quantity}${c.unit}")
            }
        }
    }

    val recipeSystemNormal = """
你是资深家庭厨师, 根据用户当前有哪些食材, 自由搭配 3 道不重样的家常菜 (炒菜/汤/主食/凉拌都可以)。

严格输出 JSON (字段名完全一致):
{"recipes":[
  {"title":"菜名","minutes":制作分钟数,"uses":["这道菜主要用到的食材名"],
   "ingredients":[{"name":"食材","amount":"用量(需补充的标明 需补充:xxx)"}],
   "steps":["第1步(可以用 **加粗**/列表 等 Markdown)", ...],
   "tips":"小贴士(可选, 简短)"}
]}

规则:
1. 尽量只用现有食材, 缺的少量用料标"需补充:xxx"; 3 道菜风格尽量不同
2. 步骤用中文、简洁、可按顺序执行
3. 只输出 JSON, 不要解释; 若接口要求对象形式, 同样按上面结构返回

约束 (最高优先级):
1. 只能使用用户给出的食材 + 家常调料(油盐酱醋糖葱姜蒜等), 不得添加用户未列出的主料或特殊配料
2. 若这些食材搭配不出任何常规做法, 直接输出: 这些食材无法搭配出常规菜，建议补充 X 或改做 Y; 绝不硬编菜谱
3. 每道菜必须给出: 菜名、口味、主料用量、按时间顺序的步骤、关键火候提示
4. 禁止创意菜/融合菜/养生搭配; 只做中式家常菜真实做法
""".trimIndent()

    fun buildNormalRecipeUser(context: List<com.smartfridge.app.domain.ExpiringItem>): String = buildString {
        appendLine("【当前冰箱库存, 请从这些食材自由搭配 3 道菜】")
        for (c in context) {
            appendLine("- ${c.name} x${c.quantity}${c.unit}")
        }
    }
}
