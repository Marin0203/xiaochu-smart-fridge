package com.smartfridge.app.domain

import android.content.Context

/**
 * 保质期表 V4（权威数据源）: assets/preservation.json —— 由
 * 《中国大陆北方家庭常见食材及加工食品保存期限表.xlsx》生成(835 条, 含变体名)。
 * 规则:
 *  - 名称"包含匹配", 命中时取最长匹配行(如 '西红柿' 不与 '圣女果(小番茄)' 冲突);
 *  - 该区域"不推荐"(表中无期限) → 用其它推荐区域的时间(优先 冷藏→冷冻→常温),
 *    见用户约定: "如果是不推荐的数据, 但是却放了该区域, 按照其他区域的时间来设置";
 *  - 都未命中返回 null, 由调用方走 FreshnessTable(正则表)兜底。
 */
object PreservationTable {

    /** 每行: [名称, 冷藏, 冷冻, 常温]; -1 = 该区不推荐 */
    private var rows: List<Pair<String, IntArray>> = emptyList()
    private var ready = false

    fun init(context: Context) {
        if (ready || rows.isNotEmpty()) return
        try {
            val text = context.assets.open("preservation.json").bufferedReader().readText()
            val ra = org.json.JSONObject(text).getJSONArray("rows")
            val list = mutableListOf<Pair<String, IntArray>>()
            for (i in 0 until ra.length()) {
                val a = ra.getJSONArray(i)
                val n = a.getString(0)
                if (n.isEmpty()) continue
                val v = IntArray(3) { k -> if (a.isNull(k + 1)) -1 else a.getInt(k + 1) }
                list.add(n to v)
            }
            rows = list.sortedByDescending { it.first.length }
            ready = true
        } catch (_: Exception) {
            rows = emptyList()
        }
    }

    /** 最长优先包含匹配(双向: 规则名含食材名 或 食材名含规则名, 如 "鸡蛋" ↔ "鲜鸡蛋");
     *  该区不推荐 → 其它推荐区(冷藏→冷冻→常温) */
    fun daysFor(name: String, zone: String): Int? {
        if (rows.isEmpty()) return null
        val z = when (zone) {
            "FRIDGE" -> 0
            "FREEZER" -> 1
            "PANTRY" -> 2
            else -> return null
        }
        var best: IntArray? = null
        for ((n, v) in rows) {
            if (name.contains(n) || n.contains(name)) { best = v; break }   // rows 按长度降序 → 首个命中即最长
        }
        val b = best ?: return null
        if (b[z] >= 0) return b[z]
        for (k in 0..2) if (b[k] >= 0) return b[k]
        return null
    }

    /** 校准入口: 新表优先, 旧正则表兜底 */
    fun daysForCalib(name: String, zone: String): Int? = daysFor(name, zone) ?: FreshnessTable.daysFor(name, zone)
}
