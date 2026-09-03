import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../domain/models/ingredient.dart';
import '../../domain/services/freshness.dart';
import 'freshness_badge.dart';

/// 库存条目卡片: 左侧状态色条 + 新鲜度进度条 + 消耗/删除操作
class IngredientCard extends StatelessWidget {
  final Ingredient item;
  final VoidCallback? onConsume; // 消耗一份
  final VoidCallback? onDelete;

  const IngredientCard({
    super.key,
    required this.item,
    this.onConsume,
    this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final pct = item.freshnessPercent(now);
    final status = Freshness.status(pct);
    final color = freshnessColor(status);
    final remaining = item.remainingDays(now);
    final qtyText = item.quantity == item.quantity.roundToDouble()
        ? item.quantity.toInt().toString()
        : item.quantity.toString();

    return IntrinsicHeight(
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // 左侧状态色条 (视觉核心: 绿/黄/红)
            Container(width: 6, color: color),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(14, 12, 8, 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            item.name,
                            style: const TextStyle(
                                fontSize: 16, fontWeight: FontWeight.w600),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        FreshnessBadge(status: status, percent: pct),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Text(
                      '${item.zone.label} · ${item.category} · '
                      '$qtyText${item.unit}',
                      style: TextStyle(
                          fontSize: 13, color: Colors.grey.shade600),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '${DateFormat('M月d日').format(item.expiresAt)} 到期'
                      '${remaining < 0 ? ' (已过期${-remaining}天)' : remaining == 0 ? ' (今天)' : ' · 剩 $remaining 天'}'
                      ' · ${item.addedByUserName} 录入',
                      style: TextStyle(
                          fontSize: 12, color: Colors.grey.shade500),
                    ),
                    const SizedBox(height: 10),
                    // 新鲜度进度条
                    ClipRRect(
                      borderRadius: BorderRadius.circular(4),
                      child: LinearProgressIndicator(
                        value: pct / 100,
                        minHeight: 6,
                        color: color,
                        backgroundColor: color.withOpacity(0.12),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            // 操作区
            Padding(
              padding: const EdgeInsets.only(right: 4),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  IconButton(
                    icon: const Icon(Icons.remove_circle_outline,
                        color: Colors.grey),
                    tooltip: '消耗一份',
                    onPressed: onConsume,
                  ),
                  IconButton(
                    icon: const Icon(Icons.delete_outline,
                        color: Colors.grey),
                    tooltip: '删除',
                    onPressed: onDelete,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
