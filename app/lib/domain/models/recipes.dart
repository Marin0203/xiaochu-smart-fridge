import 'ingredient.dart';

/// 食谱相关模型 (AI 输出结构见 prompts.dart / backend/_shared/prompts.ts)

/// 传给 AI 的「临期/库存食材」精简视图 (只发必要字段, 不泄露整行内部数据)
class ExpiringItem {
  final String name;
  final double quantity;
  final String unit;
  final double freshnessPercent;
  final int daysLeft;

  const ExpiringItem({
    required this.name,
    required this.quantity,
    required this.unit,
    required this.freshnessPercent,
    required this.daysLeft,
  });

  factory ExpiringItem.fromIngredient(Ingredient i, [DateTime? now]) =>
      ExpiringItem(
        name: i.name,
        quantity: i.quantity,
        unit: i.unit,
        freshnessPercent: i.freshnessPercent(now),
        daysLeft: i.remainingDays(now),
      );

  Map<String, dynamic> toJson() => {
        'name': name,
        'quantity': quantity,
        'unit': unit,
        'freshness_percent': freshnessPercent.round(),
        'days_left': daysLeft,
      };
}

class IngredientLine {
  final String name;
  final String amount;

  const IngredientLine(this.name, this.amount);

  static IngredientLine? tryFromJson(Map<String, dynamic>? m) {
    if (m == null) return null;
    final name = (m['name'] ?? '').toString().trim();
    if (name.isEmpty) return null;
    return IngredientLine(name, (m['amount'] ?? '').toString());
  }
}

class Recipe {
  final String title;
  final int minutes;
  final List<String> uses; // 消耗了哪些临期食材
  final List<IngredientLine> ingredients;
  final List<String> steps; // markdown 段落
  final String tips;

  const Recipe({
    required this.title,
    required this.minutes,
    required this.uses,
    required this.ingredients,
    required this.steps,
    required this.tips,
  });

  /// 宽松构造: 任一字段缺失/非法都不会抛错, 返回 null 表示该条不可用
  static Recipe? tryFromJson(Map<String, dynamic> m) {
    final title = (m['title'] ?? '').toString().trim();
    if (title.isEmpty) return null;
    return Recipe(
      title: title,
      minutes: (m['minutes'] is num) ? (m['minutes'] as num).toInt() : 20,
      uses: _strList(m['uses']),
      ingredients: _strList(m['ingredients'])
          .map((s) => _splitLine(s))
          .whereType<IngredientLine>()
          .toList(),
      steps: _strList(m['steps']),
      tips: (m['tips'] ?? '').toString(),
    );
  }

  /// 合并渲染用的 Markdown 正文 (步骤已是 markdown 段)
  String get markdownBody {
    final buf = StringBuffer();
    if (ingredients.isNotEmpty) {
      buf.writeln('## 食材清单');
      for (final l in ingredients) {
        buf.writeln('- **${l.name}** — ${l.amount}');
      }
      buf.writeln();
    }
    if (steps.isNotEmpty) {
      buf.writeln('## 烹饪步骤');
      buf.writeln();
      for (var i = 0; i < steps.length; i++) {
        buf.writeln('$i. ${steps[i]}');
        buf.writeln();
      }
    }
    if (tips.isNotEmpty) {
      buf.writeln('> 💡 $tips');
    }
    return buf.toString();
  }

  static List<String> _strList(dynamic v) {
    if (v is List) return v.map((e) => e.toString().trim()).where((s) => s.isNotEmpty).toList();
    if (v is String && v.trim().isNotEmpty) {
      return v.split(RegExp(r'[,;，；\n]')).map((s) => s.trim()).where((s) => s.isNotEmpty).toList();
    }
    return const [];
  }

  /// "牛肉 300克" → IngredientLine
  static IngredientLine? _splitLine(String s) {
    final m = RegExp(r'^(.+?)\s*[:：\-—\s]+\s*(.+)$').firstMatch(s);
    if (m == null) return IngredientLine(s, '适量');
    return IngredientLine(m.group(1)!.trim(), m.group(2)!.trim());
  }
}

/// 食谱计划 —— ok=false 时 UI 直接渲染 rawMarkdown 兜底 (容错)
class RecipePlan {
  final bool ok;
  final bool fromFallback; // true = AI 不可用时本地降级生成
  final List<Recipe> recipes;
  final String? rawMarkdown;
  final String? error;

  RecipePlan.success(List<Recipe> r)
      : ok = true,
        fromFallback = false,
        recipes = r,
        rawMarkdown = null,
        error = null;

  RecipePlan.fallback(List<Recipe> r)
      : ok = true,
        fromFallback = true,
        recipes = r,
        rawMarkdown = null,
        error = null;

  RecipePlan.failure({this.rawMarkdown, this.error})
      : ok = false,
        fromFallback = false,
        recipes = const [];
}
