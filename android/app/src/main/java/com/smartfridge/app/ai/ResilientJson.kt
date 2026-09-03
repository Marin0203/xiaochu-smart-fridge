package com.smartfridge.app.ai

import com.smartfridge.app.domain.IngredientDraft
import com.smartfridge.app.domain.Recipe
import com.smartfridge.app.domain.RecipePlan
import com.smartfridge.app.domain.StorageZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 容错 JSON 提取器 —— LLM 输出不可信, 这里是整条 AI 链路的「安全网」。
 *
 * 处理阶梯:
 *  1. 剥离 Markdown 代码块 (```json ... ```)
 *  2. 截取首个 [..] 或 {..} 子串 (去掉前后废话)
 *  3. 修复常见脏数据: 全角标点/弯引号/零宽字符/尾逗号
 *  4. Json.parseToJsonElement 按档重试
 *  5. 结构校验 + 字段清洗 + 去重合并
 */
object ResilientJson {
    private val json = Json

    // ---------- 提取与修复 ----------

    /** 核心入口: 任意 LLM 输出 → JsonElement 或 null */
    fun robustParse(raw: String): JsonElement? {
        if (raw.isBlank()) return null
        val candidates = buildList {
            add(raw)
            add(stripCodeFence(raw))
            // 截取逻辑对截断输出(有 [ 无 ])可能有边界风险 → 包住, 失败则跳过该档
            try {
                add(extractJsonSubstring(raw))
                add(extractJsonSubstring(stripCodeFence(raw)))
            } catch (_: Exception) {
                // 忽略: 走前面的候选
            }
        }
        for (c in candidates) {
            if (c.isBlank()) continue
            try {
                return json.parseToJsonElement(repair(c))
            } catch (_: Exception) {
                // 继续下一档修复
            }
        }
        return null
    }

    /** 剥离 ```json ... ``` 围栏 */
    private fun stripCodeFence(raw: String): String {
        val m = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(raw)
        return m?.groupValues?.get(1) ?: raw
    }

    /** 截取首个 JSON 数组或对象 (修掉 LLM 前后缀废话); 截断输出缺右括号时安全返回原文 */
    private fun extractJsonSubstring(raw: String): String {
        val sList = raw.indexOf('[')
        val eList = raw.lastIndexOf(']')
        val sObj = raw.indexOf('{')
        val eObj = raw.lastIndexOf('}')
        return when {
            sList != -1 && eList > sList && (sObj == -1 || sList < sObj) -> raw.substring(sList, eList + 1)
            sObj != -1 && eObj > sObj -> raw.substring(sObj, eObj + 1)
            else -> raw
        }
    }

    /** 常见脏数据修复 (字符串内替换是近似处理, 保解析成功优先) */
    private fun repair(s: String): String {
        var t = s
        for (bad in listOf("\uFEFF", "\u200B", "\u200C", "\u200E", "\u00AD")) {
            t = t.replace(bad, "")
        }
        t = t.replace("“", "\"").replace("”", "\"").replace("„", "\"")
        t = t.replace("‘", "\"").replace("’", "\"")
        t = t.replace("：", ":").replace("，", ",")
        // 尾逗号: [1,2,] / {"a":1,} —— 循环处理深层嵌套
        val trailing = Regex(",([\\s]*[}\\]])")
        repeat(3) {
            val next = t.replace(trailing, "$1")
            if (next == t) return t
            t = next
        }
        return t
    }

    // ---------- 结构归一 ----------

    /** 兼容多种包装: 数组本身 / {"items":[...]} / {"recipes":[...]} / {"data":[...]} */
    fun asMapList(value: JsonElement?): List<JsonObject> {
        if (value is JsonArray) return value.mapNotNull { it as? JsonObject }
        if (value is JsonObject) {
            for (key in listOf("items", "recipes", "ingredients", "list", "data")) {
                val v = value[key] as? JsonArray ?: continue
                return v.mapNotNull { it as? JsonObject }
            }
        }
        return emptyList()
    }

    // ---------- 领域级解析 (带校验清洗) ----------

    /** 自然语言 → 食材草稿列表 (规格: 结构化 JSON 数组) */
    fun parseIngredientDrafts(raw: String): List<IngredientDraft> {
        val value = robustParse(raw) ?: return emptyList()
        val out = mutableListOf<IngredientDraft>()
        val seen = mutableMapOf<String, Int>() // 去重: name|zone → 下标 (同名同区合并数量)
        for (m in asMapList(value)) {
            val name = (m["name"]?.jsonPrimitive?.contentOrNull ?: "").trim()
            if (name.isEmpty()) continue // 丢弃垃圾条目
            val draft = IngredientDraft.fromJson(m)
            val key = "${draft.name}|${draft.zone.name}"
            val idx = seen[key]
            if (idx != null) {
                out[idx] = out[idx].mergedWith(draft.quantity)
            } else {
                seen[key] = out.size
                out += draft
            }
        }
        return out
    }

    /** AI 食谱输出 → RecipePlan; 全失败时返回 failure 并保留原文给 UI 兜底渲染 */
    fun parseRecipePlan(raw: String): RecipePlan {
        val value = robustParse(raw) ?: return RecipePlan.failure(rawMarkdown = raw)
        val recipes = asMapList(value).mapNotNull { Recipe.fromJson(it) }
        return if (recipes.isEmpty()) RecipePlan.failure(rawMarkdown = raw) else RecipePlan.success(recipes)
    }
}
