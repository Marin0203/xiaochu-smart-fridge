/// 新鲜度状态机 —— 纯函数、无 UI 依赖、可单测。
///
/// 规格公式: Freshness% = (保质期天数 - 已存放天数) / 保质期天数 * 100%
/// 边界 (严格按规格):
///   > 50%            → 绿色 Fresh         (新鲜)
///   20% ~ 50% (含)   → 黄色 Need Consume  (尽快食用)
///   < 20%            → 红色 Expiring Soon (临期)
///   已超期           → 红色 Expired       (临期状态的补充态, 百分比为 0)
enum FreshnessStatus { fresh, needConsume, expiringSoon, expired }

extension FreshnessStatusX on FreshnessStatus {
  /// 是否属于「黄色 + 红色」预警集合 —— 食谱推荐引擎按此筛选
  bool get isAlert => this != FreshnessStatus.fresh;

  String get label => switch (this) {
        FreshnessStatus.fresh => '新鲜',
        FreshnessStatus.needConsume => '尽快食用',
        FreshnessStatus.expiringSoon => '临期',
        FreshnessStatus.expired => '已过期',
      };
}

class Freshness {
  Freshness._();

  /// 剩余新鲜度百分比 0~100。
  /// - 已存放天数按浮点精确计算 (支持 小时/分钟 级), 不取整, 保证进度条平滑
  /// - 结果 clamp 到 [0, 100]
  /// - 容错: shelfLifeDays <= 0 视为异常数据直接返回 0 (不抛错)
  static double percent({
    required DateTime purchasedAt,
    required int shelfLifeDays,
    DateTime? now,
  }) {
    if (shelfLifeDays <= 0) return 0;
    final elapsedDays =
        (now ?? DateTime.now()).difference(purchasedAt).inMilliseconds /
            Duration.millisecondsPerDay;
    final raw = (shelfLifeDays - elapsedDays) / shelfLifeDays * 100;
    if (raw <= 0) return 0;
    if (raw >= 100) return 100;
    return raw;
  }

  /// 百分比 → 状态 (边界规则见文件头注释)
  static FreshnessStatus status(double percent) {
    if (percent <= 0) return FreshnessStatus.expired;
    if (percent > 50) return FreshnessStatus.fresh;
    if (percent >= 20) return FreshnessStatus.needConsume;
    return FreshnessStatus.expiringSoon;
  }
}
