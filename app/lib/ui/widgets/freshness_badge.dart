import 'package:flutter/material.dart';

import '../../domain/services/freshness.dart';

/// 新鲜度状态 → 颜色 (规格: 绿>50% / 黄20%~50% / 红<20%)
Color freshnessColor(FreshnessStatus status) => switch (status) {
      FreshnessStatus.fresh => const Color(0xFF4CAF50),
      FreshnessStatus.needConsume => const Color(0xFFFFB300),
      FreshnessStatus.expiringSoon => const Color(0xFFF44336),
      FreshnessStatus.expired => const Color(0xFFB71C1C),
    };

/// 新鲜度徽章: 状态标签 + 百分比
class FreshnessBadge extends StatelessWidget {
  final FreshnessStatus status;
  final double percent;

  const FreshnessBadge({super.key, required this.status, required this.percent});

  @override
  Widget build(BuildContext context) {
    final color = freshnessColor(status);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withOpacity(0.12),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withOpacity(0.4)),
      ),
      child: Text(
        '${status.label} ${percent.round()}%',
        style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w600),
      ),
    );
  }
}
