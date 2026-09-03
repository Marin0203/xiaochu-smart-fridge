import 'ingredient.dart';

/// AI 解析出的「待入库食材草稿」。
/// 字段严格对应任务书规格: name / quantity / unit / zone / shelfLifeDays
/// (category 由本地规则补全, 见 category_guesser.dart —— LLM 不负责分类, 更稳更省 token)
class IngredientDraft {
  final String name;
  final double quantity;
  final String unit;
  final StorageZone zone;
  final int shelfLifeDays;

  const IngredientDraft({
    required this.name,
    required this.quantity,
    required this.unit,
    required this.zone,
    required this.shelfLifeDays,
  });

  /// 从 AI 返回的 JSON Map 构造 (宽松解析 + 校验收敛)
  factory IngredientDraft.fromJson(Map<String, dynamic> m) {
    final name = (m['name'] ?? '').toString().trim();
    final qty = _toDouble(m['quantity']);
    final unit = (m['unit'] ?? '').toString().trim();
    return IngredientDraft(
      name: name,
      quantity: qty > 0 ? qty : 1, // 容错: 缺失/非法数量归 1
      unit: unit.isEmpty ? '份' : unit,
      zone: _zoneOf(m['zone']),
      shelfLifeDays: _shelfOf(m['shelfLifeDays']),
    );
  }

  IngredientDraft copyWith({double? quantity, StorageZone? zone, int? shelfLifeDays, String? unit, String? name}) =>
      IngredientDraft(
        name: name ?? this.name,
        quantity: quantity ?? this.quantity,
        unit: unit ?? this.unit,
        zone: zone ?? this.zone,
        shelfLifeDays: shelfLifeDays ?? this.shelfLifeDays,
      );

  IngredientDraft mergedWith(double qty) => copyWith(quantity: quantity + qty);

  static double _toDouble(dynamic v) {
    if (v is num) return v.toDouble();
    if (v is String) return double.tryParse(v.trim()) ?? 0;
    return 0;
  }

  static StorageZone _zoneOf(dynamic v) {
    final s = v?.toString().trim().toUpperCase() ?? '';
    for (final z in StorageZone.values) {
      if (z.dbValue == s) return z;
    }
    return StorageZone.fridge; // 规格缺省: 冷藏
  }

  static int _shelfOf(dynamic v) {
    if (v == null) return 3;
    final d = _toDouble(v);
    if (d.isNaN || d <= 0) return 3; // 容错: 异常值归 3 天
    return d.clamp(1, 3650).toInt();
  }
}
