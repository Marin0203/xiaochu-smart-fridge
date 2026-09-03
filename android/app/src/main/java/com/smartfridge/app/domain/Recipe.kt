package com.smartfridge.app.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 食谱生成模式: 正常自由搭配(默认) / 临期优先 */
enum class RecipeMode(val db: String, val label: String, val desc: String) {
    NORMAL("normal", "正常食谱", "今天有什么就做什么"),
    EXPIRING("expiring", "临期食谱", "先吃掉快过期的食材"),
}

/** 传给 AI 的「临期/库存食材」精简视图 (只发必要字段, 不泄露整行内部数据) */
data class ExpiringItem(
    val name: String,
    val quantity: Double,
    val unit: String,
    val freshnessPercent: Double,
    val daysLeft: Int,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("name", name)
        put("quantity", quantity)
        put("unit", unit)
        put("freshness_percent", freshnessPercent.toInt())
        put("days_left", daysLeft)
    }

    companion object {
        fun fromIngredient(i: Ingredient): ExpiringItem = ExpiringItem(
            name = i.name,
            quantity = i.quantity,
            unit = i.unit,
            freshnessPercent = i.freshnessPercent(),
            daysLeft = i.remainingDays(),
        )
    }
}

data class IngredientLine(val name: String, val amount: String)

/** 一道菜谱 (AI 输出结构见 Prompts) */
data class Recipe(
    val title: String,
    val minutes: Int,
    val uses: List<String>,
    val ingredients: List<IngredientLine>,
    val steps: List<String>,
    val tips: String,
) {
    /** 合并渲染用的 Markdown 正文 (步骤已是 markdown 段) */
    fun markdownBody(): String = buildString {
        if (ingredients.isNotEmpty()) {
            appendLine("## 食材清单")
            for (l in ingredients) appendLine("- **${l.name}** — ${l.amount}")
            appendLine()
        }
        if (steps.isNotEmpty()) {
            appendLine("## 烹饪步骤")
            appendLine()
            steps.forEachIndexed { idx, s ->
                // AI 可能自带 "1." 序号 → 剥离后统一编号, 避免 "1. 1." 重复
                val clean = s.replace(Regex("^\\s*\\d+[.、）)]\\s*"), "").trim().ifBlank { s }
                appendLine("${idx + 1}. $clean")
                appendLine()
            }
        }
        if (tips.isNotBlank()) {
            appendLine("> 💡 $tips")
        }
    }

    companion object {
        /** 宽松构造: 任一字段缺失/非法不抛错; 无标题返回 null */
        fun fromJson(m: JsonObject): Recipe? {
            // 防崩: title 必须是文本 (AI 偶尔输出非 primitive 结构)
            val titleEl = m["title"]
            val title = if (titleEl is kotlinx.serialization.json.JsonPrimitive) titleEl.contentOrNull?.trim() ?: "" else ""
            if (title.isEmpty()) return null
            return Recipe(
                title = title,
                minutes = m["minutes"]?.jsonPrimitive?.intOrNull ?: 20,
                uses = strList(m["uses"]),
                ingredients = ingredientLines(m["ingredients"]),
                steps = strList(m["steps"]),
                tips = m["tips"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        }

        /** 兼容两种返回: [{"name":"牛肉","amount":"300克"}] 或 ["牛肉 300克"] 文本列表 */
        private fun ingredientLines(v: kotlinx.serialization.json.JsonElement?): List<IngredientLine> {
            if (v is kotlinx.serialization.json.JsonArray) {
                return v.mapNotNull { item ->
                    when (item) {
                        is JsonObject -> {
                            val name = item["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                            IngredientLine(name, item["amount"]?.jsonPrimitive?.contentOrNull ?: "适量")
                        }
                        is JsonPrimitive -> splitLine(item.contentOrNull ?: return@mapNotNull null)
                        else -> null
                    }
                }
            }
            val s = (v as? JsonPrimitive)?.contentOrNull ?: return emptyList()
            return s.split(Regex("[,;，；\\n]")).map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { splitLine(it) }
        }

        private fun strList(v: kotlinx.serialization.json.JsonElement?): List<String> {
            if (v is kotlinx.serialization.json.JsonArray) {
                return v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
            }
            val s = (v as? JsonPrimitive)?.contentOrNull ?: return emptyList()
            return s.split(Regex("[,;，；\\n]")).map { it.trim() }.filter { it.isNotEmpty() }
        }

        /** "牛肉 300克" → IngredientLine */
        private fun splitLine(s: String): IngredientLine? {
            val m = Regex("^(.+?)\\s*[:：\\-—\\s]+\\s*(.+)$").find(s) ?: return IngredientLine(s, "适量")
            return IngredientLine(m.groupValues[1].trim(), m.groupValues[2].trim())
        }
    }
}

/** 食谱计划 —— ok=false 时 UI 直接渲染 rawMarkdown 兜底 (容错) */
data class RecipePlan(
    val ok: Boolean,
    val fromFallback: Boolean,
    val recipes: List<Recipe>,
    val rawMarkdown: String?,
    val error: String?,
) {
    companion object {
        fun success(recipes: List<Recipe>) = RecipePlan(true, false, recipes, null, null)
        fun fallback(recipes: List<Recipe>) = RecipePlan(true, true, recipes, null, null)
        fun failure(rawMarkdown: String? = null, error: String? = null) = RecipePlan(false, false, emptyList(), rawMarkdown, error)
    }
}
