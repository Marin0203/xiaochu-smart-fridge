package com.smartfridge.app.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** 存放区域 —— 三大页签 */
enum class StorageZone(val db: String, val label: String, val emoji: String) {
    FRIDGE("FRIDGE", "冷藏", "🥬"),
    FREEZER("FREEZER", "冷冻", "🧊"),
    PANTRY("PANTRY", "常温", "🌾");

    companion object {
        /** 容错: 未知值一律归常温区 */
        fun fromDb(v: String?): StorageZone = entries.firstOrNull { it.db == v } ?: PANTRY
    }
}

/**
 * 食材存货模型 —— 与后端 supabase.ingredients 表逐字段对应。
 * (任务书 TypeScript 接口的 Kotlin 版, 增加 updatedAt 用于 LWW 时间戳冲突合并)
 */
data class Ingredient(
    val id: String,
    val familyId: String,
    val name: String,
    val category: String,
    val quantity: Double,
    val unit: String,
    val zone: StorageZone,
    val purchasedAt: Instant,
    val shelfLifeDays: Int,
    val addedByUserName: String,
    val updatedAt: Instant,
) {
    fun freshnessPercent(nowEpochMs: Long = System.currentTimeMillis()): Double =
        Freshness.percent(purchasedAt.toEpochMilli(), shelfLifeDays, nowEpochMs)

    fun freshnessStatus(nowEpochMs: Long = System.currentTimeMillis()): FreshnessStatus =
        Freshness.status(freshnessPercent(nowEpochMs))

    fun expiresAt(): Instant = purchasedAt.plusSeconds(shelfLifeDays * 86_400L)

    fun remainingDays(now: Instant = Instant.now()): Int =
        Duration.between(now, expiresAt()).toDays().toInt()

    fun copyWith(
        name: String = this.name,
        category: String = this.category,
        quantity: Double = this.quantity,
        unit: String = this.unit,
        zone: StorageZone = this.zone,
        shelfLifeDays: Int = this.shelfLifeDays,
        purchasedAt: Instant = this.purchasedAt,
        addedByUserName: String = this.addedByUserName,
        updatedAt: Instant = this.updatedAt,
    ) = Ingredient(id, familyId, name, category, quantity, unit, zone, purchasedAt, shelfLifeDays, addedByUserName, updatedAt)

    // ---------- JSON (与后端 snake_case + ISO8601 UTC 对齐) ----------

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", id)
        put("family_id", familyId)
        put("name", name)
        put("category", category)
        put("quantity", quantity)
        put("unit", unit)
        put("zone", zone.db)
        put("purchased_at", purchasedAt.toString())
        put("shelf_life_days", shelfLifeDays)
        put("added_by_user_name", addedByUserName)
        put("updated_at", updatedAt.toString())
    }

    /** 容错解析: 任何字段缺失/非法都不抛错, 无法解析时返回 null */
    companion object {
        fun fromJson(obj: JsonObject): Ingredient? {
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
            val quantity = obj["quantity"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val shelf = obj["shelf_life_days"]?.jsonPrimitive?.intOrNull ?: 3
            return Ingredient(
                id = id,
                familyId = obj["family_id"]?.jsonPrimitive?.contentOrNull ?: "",
                name = name,
                category = obj["category"]?.jsonPrimitive?.contentOrNull?.ifBlank { "其他" } ?: "其他",
                quantity = quantity,
                unit = obj["unit"]?.jsonPrimitive?.contentOrNull?.ifBlank { "份" } ?: "份",
                zone = StorageZone.fromDb(obj["zone"]?.jsonPrimitive?.contentOrNull),
                purchasedAt = parseTs(obj["purchased_at"]?.jsonPrimitive?.contentOrNull),
                shelfLifeDays = if (shelf in 1..3650) shelf else 3,
                addedByUserName = obj["added_by_user_name"]?.jsonPrimitive?.contentOrNull?.ifBlank { "未知" } ?: "未知",
                updatedAt = parseTs(obj["updated_at"]?.jsonPrimitive?.contentOrNull),
            )
        }

        /** timestamptz "2026-08-24T12:00:00.123456+00:00" / "...Z" → Instant; 失败回退 epoch (容错) */
        fun parseTs(s: String?): Instant = try {
            if (s.isNullOrBlank()) Instant.EPOCH else OffsetDateTime.parse(s).toInstant()
        } catch (_: Exception) {
            Instant.EPOCH
        }

        fun parseList(element: JsonElement?): List<Ingredient> {
            if (element !is JsonArray) return emptyList()
            return element.mapNotNull { it as? JsonObject }.mapNotNull { fromJson(it) }
        }

        /** 行 → JSON 数组 (供批量 upsert / 容错序列化再入) */
        fun toJsonArray(list: Iterable<Ingredient>): JsonArray =
            JsonArray(list.map { it.toJsonObject() })
    }
}
