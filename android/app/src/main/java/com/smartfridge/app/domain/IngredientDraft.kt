package com.smartfridge.app.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * AI 解析出的「待入库食材草稿」。
 * 字段严格对应任务书规格: name / quantity / unit / zone / shelfLifeDays
 * (category 由本地规则补全, 见 CategoryGuesser —— LLM 不负责分类, 更稳更省 token)
 */
data class IngredientDraft(
    val name: String,
    val quantity: Double,
    val unit: String,
    val zone: StorageZone,
    val shelfLifeDays: Int,
) {
    fun copyWith(
        name: String = this.name,
        quantity: Double = this.quantity,
        unit: String = this.unit,
        zone: StorageZone = this.zone,
        shelfLifeDays: Int = this.shelfLifeDays,
    ) = IngredientDraft(name, quantity, unit, zone, shelfLifeDays)

    fun mergedWith(qty: Double) = copyWith(quantity = quantity + qty)

    companion object {
        /** 宽松解析 + 校验收敛 (容错: 非法值归缺省, 绝不抛错) */
        fun fromJson(m: JsonObject): IngredientDraft {
            val name = (m["name"]?.jsonPrimitive?.contentOrNull ?: "").trim()
            val qty = m["quantity"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val unit = (m["unit"]?.jsonPrimitive?.contentOrNull ?: "").trim()
            val zoneRaw = (m["zone"]?.jsonPrimitive?.contentOrNull ?: "").trim().uppercase()
            val shelf = m["shelfLifeDays"]?.jsonPrimitive?.intOrNull ?: 0
            return IngredientDraft(
                name = name,
                quantity = if (qty > 0) qty else 1.0, // 容错: 缺失/非法数量归 1
                unit = unit.ifEmpty { "份" },
                zone = storageZoneOf(zoneRaw),
                shelfLifeDays = if (shelf in 1..3650) shelf else 3, // 容错: 异常值归 3 天
            )
        }

        private fun storageZoneOf(raw: String): StorageZone =
            StorageZone.entries.firstOrNull { it.db == raw } ?: StorageZone.FRIDGE // 规格缺省: 冷藏
    }
}
