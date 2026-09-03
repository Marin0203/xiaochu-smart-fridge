import '../services/freshness.dart';

/// 存放区域 —— 三大页签
enum StorageZone {
  fridge('FRIDGE', '冷藏区'),
  freezer('FREEZER', '冷冻区'),
  pantry('PANTRY', '常温区');

  final String dbValue;
  final String label;

  const StorageZone(this.dbValue, this.label);

  /// 容错: 未知值一律归入常温区 (不抛错)
  static StorageZone fromDb(String? v) {
    for (final z in values) {
      if (z.dbValue == v) return z;
    }
    return StorageZone.pantry;
  }
}

/// 食材存货模型 —— 与后端 supabase.ingredients 表 / 本地 SQLite 逐字段对应。
/// (与任务书中的 TypeScript 接口一致, 增加 updatedAt 用于 LWW 时间戳冲突合并)
class Ingredient {
  final String id; // UUID
  final String familyId; // 家庭空间 ID
  final String name; // 食材名称 (如 "牛肉")
  final String category; // 分类 (肉类/蔬菜/乳制品等)
  final double quantity; // 数量
  final String unit; // 单位 (个/克/盒)
  final StorageZone zone; // 存放区域
  final DateTime purchasedAt; // 入库时间戳
  final int shelfLifeDays; // 保质期 (天)
  final String addedByUserName; // 录入人
  final DateTime updatedAt; // 最后修改时间 (同步冲突裁决依据)

  const Ingredient({
    required this.id,
    required this.familyId,
    required this.name,
    required this.category,
    required this.quantity,
    required this.unit,
    required this.zone,
    required this.purchasedAt,
    required this.shelfLifeDays,
    required this.addedByUserName,
    required this.updatedAt,
  });

  /// 剩余新鲜度百分比 0~100 (见 freshness.dart 公式)
  double freshnessPercent([DateTime? now]) => Freshness.percent(
        purchasedAt: purchasedAt,
        shelfLifeDays: shelfLifeDays,
        now: now,
      );

  FreshnessStatus freshnessStatus([DateTime? now]) =>
      Freshness.status(freshnessPercent(now));

  /// 到期时刻
  DateTime get expiresAt => purchasedAt.add(Duration(days: shelfLifeDays));

  /// 剩余天数 (不足一天按 0 天, 负数表示已过期天数)
  int remainingDays([DateTime? now]) =>
      expiresAt.difference(now ?? DateTime.now()).inDays;

  Ingredient copyWith({
    String? name,
    String? category,
    double? quantity,
    String? unit,
    StorageZone? zone,
    int? shelfLifeDays,
    String? addedByUserName,
    DateTime? updatedAt,
  }) {
    return Ingredient(
      id: id,
      familyId: familyId,
      name: name ?? this.name,
      category: category ?? this.category,
      quantity: quantity ?? this.quantity,
      unit: unit ?? this.unit,
      zone: zone ?? this.zone,
      purchasedAt: purchasedAt,
      shelfLifeDays: shelfLifeDays ?? this.shelfLifeDays,
      addedByUserName: addedByUserName ?? this.addedByUserName,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  // ---------- Supabase JSON (snake_case, ISO8601 UTC) ----------
  factory Ingredient.fromJson(Map<String, dynamic> json) => Ingredient(
        id: (json['id'] ?? '').toString(),
        familyId: (json['family_id'] ?? '').toString(),
        name: (json['name'] ?? '未命名').toString(),
        category: (json['category'] ?? '其他').toString(),
        quantity: (json['quantity'] as num?)?.toDouble() ?? 0,
        unit: (json['unit'] ?? '份').toString(),
        zone: StorageZone.fromDb(json['zone'] as String?),
        purchasedAt: DateTime.tryParse((json['purchased_at'] ?? '').toString()) ??
            DateTime.now(),
        shelfLifeDays: (json['shelf_life_days'] as num?)?.toInt() ?? 3,
        addedByUserName: (json['added_by_user_name'] ?? '未知').toString(),
        updatedAt: DateTime.tryParse((json['updated_at'] ?? '').toString()) ??
            DateTime.now(),
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'family_id': familyId,
        'name': name,
        'category': category,
        'quantity': quantity,
        'unit': unit,
        'zone': zone.dbValue,
        'purchased_at': purchasedAt.toUtc().toIso8601String(),
        'shelf_life_days': shelfLifeDays,
        'added_by_user_name': addedByUserName,
        'updated_at': updatedAt.toUtc().toIso8601String(),
      };

  // ---------- 本地 SQLite (epoch 毫秒) ----------
  Map<String, dynamic> toRow() => {
        'id': id,
        'family_id': familyId,
        'name': name,
        'category': category,
        'quantity': quantity,
        'unit': unit,
        'zone': zone.dbValue,
        'purchased_at': purchasedAt.millisecondsSinceEpoch,
        'shelf_life_days': shelfLifeDays,
        'added_by_user_name': addedByUserName,
        'updated_at': updatedAt.millisecondsSinceEpoch,
      };

  factory Ingredient.fromRow(Map<String, dynamic> row) => Ingredient(
        id: (row['id'] ?? '').toString(),
        familyId: (row['family_id'] ?? '').toString(),
        name: (row['name'] ?? '未命名').toString(),
        category: (row['category'] ?? '其他').toString(),
        quantity: (row['quantity'] as num?)?.toDouble() ?? 0,
        unit: (row['unit'] ?? '份').toString(),
        zone: StorageZone.fromDb(row['zone'] as String?),
        purchasedAt:
            DateTime.fromMillisecondsSinceEpoch((row['purchased_at'] as num?)?.toInt() ?? 0),
        shelfLifeDays: (row['shelf_life_days'] as num?)?.toInt() ?? 3,
        addedByUserName: (row['added_by_user_name'] ?? '未知').toString(),
        updatedAt:
            DateTime.fromMillisecondsSinceEpoch((row['updated_at'] as num?)?.toInt() ?? 0),
      );
}
