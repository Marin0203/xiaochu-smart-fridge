package com.smartfridge.app.web

import com.smartfridge.app.core.SkinManager
import com.smartfridge.app.core.IconSet
import com.smartfridge.app.domain.FreshnessStatus
import com.smartfridge.app.domain.Ingredient
import com.smartfridge.app.domain.Recipe
import com.smartfridge.app.domain.StorageZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 数据映射: 领域模型 → WebView setData 格式 (对接命令 D-30)。
 * 字段名不可改: inv[]{id,name,emoji,sym,tag,catKey,zone,qty,unit,fresh{c,w,pct,note}}
 *               recipes{1..n}{name,time,emoji,badge,tags[],steps[],ing[{name,emoji,sym,catKey,unit,base}]}
 *               iconSet "a"|"b"|"c"
 */
object WebData {

    // ---------- 分类 → catKey (页面贴纸色板/兜底; 与 seed 一致) ----------
    private val CAT_KEY: Map<String, String> = mapOf(
        "蔬菜" to "veg", "水果" to "fru", "乳品" to "dairy", "肉蛋" to "meat",
        "海鲜" to "froz", "速冻" to "froz", "主食" to "base", "调味" to "cond",
        "其他" to "veg",
    )

    private val CAT_SYM: Map<String, String> = mapOf(
        "veg" to "cat-veggie", "fru" to "cat-fruit", "dairy" to "cat-dairy",
        "meat" to "cat-meat", "froz" to "cat-veggie", // 占位: 页面未收录时页面自行兜底
        "base" to "cat-staple", "cond" to "cat-condiment",
    )

    // ---------- 食材名 → 图标 symbol id (与 index.html <symbol> 一一对应) ----------
    private val FOOD_SYM: Map<String, String> = mapOf(
        "番茄" to "food-tomato", "黄瓜" to "food-cucumber", "土豆" to "food-potato",
        "胡萝卜" to "food-carrot", "洋葱" to "food-onion", "香菇" to "food-mushroom",
        "辣椒" to "food-chili", "玉米" to "food-corn", "苹果" to "food-apple",
        "橙子" to "food-orange", "香蕉" to "food-banana", "葡萄" to "food-grape",
        "西瓜" to "food-watermelon", "草莓" to "food-strawberry", "鸡蛋" to "food-egg",
        "牛排" to "food-steak", "猪排" to "food-pork", "鸡腿" to "food-chicken",
        "鱼" to "food-fish", "三文鱼" to "food-salmon", "牛奶" to "food-milk",
        "酸奶" to "food-yogurt", "奶酪" to "food-cheese", "豆腐" to "food-tofu",
        "大米" to "food-rice", "面条" to "food-noodle", "面包" to "food-bread",
        "酱油" to "food-soy", "可乐" to "food-cola", "咖啡" to "food-coffee",
    )

    // ---------- 食材名 → emoji (A 套) ----------
    private val FOOD_EMOJI: Map<String, String> = mapOf(
        "番茄" to "🍅", "黄瓜" to "🥒", "土豆" to "🥔", "胡萝卜" to "🥕", "洋葱" to "🧅",
        "香菇" to "🍄", "辣椒" to "🌶️", "玉米" to "🌽", "苹果" to "🍎", "橙子" to "🍊",
        "香蕉" to "🍌", "葡萄" to "🍇", "西瓜" to "🍉", "草莓" to "🍓", "鸡蛋" to "🥚",
        "牛排" to "🥩", "猪排" to "🥩", "鸡腿" to "🍗", "鱼" to "🐟", "三文鱼" to "🐟",
        "牛奶" to "🥛", "酸奶" to "🥛", "奶酪" to "🧀", "豆腐" to "🍲", "大米" to "🍚",
        "面条" to "🍜", "面包" to "🍞", "酱油" to "🧂", "可乐" to "🥤", "咖啡" to "☕",
        "生菜" to "🥬", "虾仁" to "🦐", "玉米粒" to "🌽", "食盐" to "🧂", "蜂蜜" to "🍯",
        "蒜" to "🧄", "葱" to "🧅", "姜" to "🫚",
    )

    private val CAT_EMOJI: Map<String, String> = mapOf(
        "蔬菜" to "🥬", "水果" to "🍊", "乳品" to "🥛", "肉蛋" to "🥩",
        "海鲜" to "🦐", "速冻" to "🧊", "主食" to "🍚", "调味" to "🧂", "其他" to "🥬",
    )

    // ---------- 新鲜度 (C/D-31: 三色纪律, 灯下由页面切换) ----------
    private val FRESH_COLOR: Map<FreshnessStatus, String> = mapOf(
        FreshnessStatus.FRESH to "#5FA45C",
        FreshnessStatus.NEED_CONSUME to "#E9B960",
        FreshnessStatus.EXPIRING_SOON to "#C4624A",
        FreshnessStatus.EXPIRED to "#C4624A",
    )

    private val FRESH_NOTE: Map<FreshnessStatus, String> = mapOf(
        FreshnessStatus.FRESH to "很新鲜",
        FreshnessStatus.NEED_CONSUME to "注意了",
        FreshnessStatus.EXPIRING_SOON to "快到期",
        FreshnessStatus.EXPIRED to "已过期",
    )

    fun zoneKey(zone: StorageZone): String = when (zone) {
        StorageZone.FRIDGE -> "chill"
        StorageZone.FREEZER -> "freeze"
        StorageZone.PANTRY -> "ambient"
    }

    fun zoneFromKey(key: String): StorageZone = when (key) {
        "chill" -> StorageZone.FRIDGE
        "freeze" -> StorageZone.FREEZER
        else -> StorageZone.PANTRY
    }

    fun catKeyOf(category: String?): String = CAT_KEY[category] ?: "veg"

    @Suppress("UNUSED_PARAMETER")
    private fun symOf(name: String, category: String?): String =
        FOOD_SYM[name] ?: CAT_SYM[catKeyOf(category)] ?: ""

    private fun emojiOf(name: String, category: String?): String =
        FOOD_EMOJI[name] ?: CAT_EMOJI[category] ?: "🥬"

    /** 剩余保质天数（向上取整，最低 0） */
    private fun daysLeftOf(it: Ingredient): Int {
        val end = it.purchasedAt.toEpochMilli() + it.shelfLifeDays.toLong() * 86_400_000L
        val left = end - System.currentTimeMillis()
        return ((left + 86_400_000L - 1) / 86_400_000L).toInt().coerceAtLeast(0)
    }

    /** 菜名 emoji：取菜名里最长命中的食材（主料优先），无则 fallback */
    private fun titleEmoji(title: String, fallback: String): String =
        FOOD_EMOJI.keys
            .filter { title.contains(it) }
            .maxByOrNull { it.length }
            ?.let { FOOD_EMOJI[it] } ?: fallback

    fun iconSetId(): String = when (SkinManager.iconSet.value) {
        IconSet.EMOJI -> "a"
        IconSet.LINE -> "b"
        IconSet.STICKER -> "c"
    }

    /** inv 数组 (id 为 1..N 稳定数字映射, 由桥维护 idMap) */
    fun invArray(items: List<Ingredient>, idOf: (String) -> Int): JsonArray = buildJsonArray {
        items.forEach { it ->
            val pct = it.freshnessPercent().toInt().coerceIn(0, 100)
            val status = it.freshnessStatus()
            add(buildJsonObject {
                put("id", idOf(it.id))
                put("name", it.name)
                put("emoji", emojiOf(it.name, it.category))
                put("sym", symOf(it.name, it.category))
                put("tag", it.category)
                put("catKey", catKeyOf(it.category))
                put("zone", zoneKey(it.zone))
                put("qty", it.quantity)
                put("unit", it.unit)
                put("fresh", buildJsonObject {
                    put("c", FRESH_COLOR[status] ?: "#5FA45C")
                    put("w", "$pct%")
                    put("pct", "$pct%")
                    put("note", FRESH_NOTE[status] ?: "很新鲜")
                    put("days", daysLeftOf(it))
                })
            })
        }
    }

    /** recipes 对象 (键 "1".."n"; badge=1 表示该菜含临期食材, 徽章显示由页面开关驱动) */
    fun recipesObject(
        recipes: List<Recipe>,
        withBadge: Boolean,
        badges: List<Int>? = null,   /* per-recipe 徽章（每道 0/1）；null=全局 withBadge */
        emojiFallback: String = "🍲",
    ): JsonObject = buildJsonObject {
        recipes.forEachIndexed { i, r ->
            put((i + 1).toString(), buildJsonObject {
                put("name", r.title)
                put("time", "${r.minutes} 分钟")
                put("emoji", titleEmoji(r.title, emojiFallback))
                put("badge", badges?.getOrElse(i) { 0 } ?: if (withBadge) 1 else 0)
                put("tags", JsonArray(r.uses.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("steps", JsonArray(r.steps.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("ing", buildJsonArray {
                    r.ingredients.forEach { line ->
                        add(buildJsonObject {
                            put("name", line.name)
                            put("emoji", emojiOf(line.name, null))
                            put("sym", symOf(line.name, null))
                            put("catKey", catKeyOf(null))
                            put("unit", unitOf(line.amount))
                            put("base", baseOf(line.amount))
                        })
                    }
                })
            })
        }
    }

    private val AMOUNT_RE = Regex("""([\d.]+)\s*([克千克公斤斤两毫升升mMlLgGkKgKGML]*)""")

    private fun baseOf(amount: String): Double =
        AMOUNT_RE.find(amount.replace(" ", ""))?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun unitOf(amount: String): String =
        AMOUNT_RE.find(amount.replace(" ", ""))?.groupValues?.get(2)?.ifBlank { "份" } ?: "份"

    /** 质量/体积换算基准 (deduct 用: 页面 unit → 库存单位) */
    fun unitFactor(unit: String): Double? = when (unit.trim().lowercase()) {
        "", "克", "g" -> 1.0
        "千克", "公斤", "kg" -> 1000.0
        "斤" -> 500.0
        "两" -> 50.0
        "毫升", "ml" -> 1.0
        "升", "l" -> 1000.0
        else -> null
    }
}
