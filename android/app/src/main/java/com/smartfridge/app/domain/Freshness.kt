package com.smartfridge.app.domain

/**
 * 新鲜度状态机 —— 纯函数、无 UI 依赖、可单测。
 *
 * 规格公式: Freshness% = (保质期天数 - 已存放天数) / 保质期天数 * 100%
 * 边界 (严格按规格):
 *   > 50%            → 绿色 Fresh         (新鲜)
 *   20% ~ 50% (含)   → 黄色 Need Consume  (尽快食用)
 *   < 20%            → 红色 Expiring Soon (临期)
 *   已超期           → 红色 Expired       (百分比为 0 的补充态)
 */
enum class FreshnessStatus(val label: String) {
    FRESH("新鲜"),
    NEED_CONSUME("尽快食用"),
    EXPIRING_SOON("临期"),
    EXPIRED("已过期");

    /** 是否属于「黄色+红色」预警集合 —— 食谱引擎按此筛选 */
    val isAlert: Boolean get() = this != FRESH
}

object Freshness {
    private const val MS_PER_DAY = 86_400_000.0

    /** 剩余新鲜度百分比 0~100; 已存放天数浮点精确计算; 容错: shelfLifeDays<=0 返回 0 */
    fun percent(purchasedAtEpochMs: Long, shelfLifeDays: Int, nowEpochMs: Long = System.currentTimeMillis()): Double {
        if (shelfLifeDays <= 0) return 0.0
        val elapsedDays = (nowEpochMs - purchasedAtEpochMs) / MS_PER_DAY
        val raw = (shelfLifeDays - elapsedDays) / shelfLifeDays * 100.0
        return raw.coerceIn(0.0, 100.0)
    }

    /** 百分比 → 状态 (边界规则见文件头注释) */
    fun status(percent: Double): FreshnessStatus = when {
        percent <= 0.0 -> FreshnessStatus.EXPIRED
        percent > 50.0 -> FreshnessStatus.FRESH
        percent >= 20.0 -> FreshnessStatus.NEED_CONSUME
        else -> FreshnessStatus.EXPIRING_SOON
    }
}
