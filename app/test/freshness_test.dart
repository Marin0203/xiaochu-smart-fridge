import 'package:flutter_test/flutter_test.dart';
import 'package:smart_fridge/domain/services/freshness.dart';

/// 新鲜度算法的边界测试 —— 严格对应任务书规格
void main() {
  final now = DateTime(2026, 8, 24, 12);

  group('Freshness.percent 公式', () {
    test('公式: (保质期-已存)/保质期*100', () {
      final pct = Freshness.percent(
        purchasedAt: now.subtract(const Duration(days: 3)),
        shelfLifeDays: 10,
        now: now,
      );
      expect(pct, closeTo(70, 0.01));
    });

    test('精确到小时级 (不取整)', () {
      final pct = Freshness.percent(
        purchasedAt: now.subtract(const Duration(hours: 36)),
        shelfLifeDays: 4,
        now: now,
      );
      // 已存放 1.5 天, (4-1.5)/4 = 62.5
      expect(pct, closeTo(62.5, 0.01));
    });

    test('未来入库时间钳制为 100%', () {
      final pct = Freshness.percent(
        purchasedAt: now.add(const Duration(days: 1)),
        shelfLifeDays: 10,
        now: now,
      );
      expect(pct, 100);
    });

    test('超期钳制为 0% (不出现负数)', () {
      final pct = Freshness.percent(
        purchasedAt: now.subtract(const Duration(days: 12)),
        shelfLifeDays: 10,
        now: now,
      );
      expect(pct, 0);
    });

    test('容错: shelfLifeDays<=0 返回 0 且不抛错', () {
      expect(
        Freshness.percent(purchasedAt: now, shelfLifeDays: 0, now: now),
        0,
      );
      expect(
        Freshness.percent(purchasedAt: now, shelfLifeDays: -3, now: now),
        0,
      );
    });
  });

  group('状态边界 (>50 绿 / 20~50 黄 / <20 红 / 0 过期)', () {
    test('70% → fresh', () {
      expect(Freshness.status(70), FreshnessStatus.fresh);
    });

    test('边界 50% → needConsume (20%~50% 含边界)', () {
      expect(Freshness.status(50), FreshnessStatus.needConsume);
    });

    test('边界 20% → needConsume (20%~50% 含边界)', () {
      expect(Freshness.status(20), FreshnessStatus.needConsume);
    });

    test('19.9% → expiringSoon', () {
      expect(Freshness.status(19.9), FreshnessStatus.expiringSoon);
    });

    test('0% → expired', () {
      expect(Freshness.status(0), FreshnessStatus.expired);
    });

    test('预警集合 isAlert: 黄/红/过期为 true, 绿为 false', () {
      expect(FreshnessStatus.fresh.isAlert, false);
      expect(FreshnessStatus.needConsume.isAlert, true);
      expect(FreshnessStatus.expiringSoon.isAlert, true);
      expect(FreshnessStatus.expired.isAlert, true);
    });
  });
}
