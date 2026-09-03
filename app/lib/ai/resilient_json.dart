import 'dart:convert';

import '../domain/models/ingredient_draft.dart';
import '../domain/models/recipes.dart';

/// 容错 JSON 提取器 —— LLM 输出不可信, 这里是整条 AI 链路的「安全网」。
///
/// 处理阶梯:
///  1. 剥离 Markdown 代码块 (```json ... ```)
///  2. 截取首个 [..] 或 {..} 子串 (去掉前后废话)
///  3. 修复常见脏数据: 全角标点/弯引号/零宽字符/尾逗号
///  4. jsonDecode 重试
///  5. 结构校验 + 字段清洗 + 去重合并
class ResilientJson {
  ResilientJson._();

  // ---------- 提取与修复 ----------

  /// 核心入口: 任意 LLM 输出 → Map/List 或 null
  static Object? robustParse(String raw) {
    if (raw.trim().isEmpty) return null;
    final candidates = <String>[
      raw,
      _stripCodeFence(raw),
      _extractJsonSubstring(raw),
      _extractJsonSubstring(_stripCodeFence(raw)),
    ];
    for (final c in candidates) {
      if (c.trim().isEmpty) continue;
      try {
        return jsonDecode(_repair(c));
      } on FormatException {
        // 继续下一档修复
      }
    }
    throw const FormatException('LLM 输出中未能提取到合法 JSON');
  }

  /// 剥离 ```json ... ``` 围栏
  static String _stripCodeFence(String raw) {
    final m =
        RegExp(r'```(?:json|JSON)?\s*([\s\S]*?)```').firstMatch(raw);
    return m?.group(1) ?? raw;
  }

  /// 截取首个 JSON 数组或对象 (修掉 LLM 前后缀废话)
  static String _extractJsonSubstring(String raw) {
    final sList = raw.indexOf('[');
    final eList = raw.lastIndexOf(']');
    final sObj = raw.indexOf('{');
    final eObj = raw.lastIndexOf('}');
    if (sList != -1 && (sObj == -1 || sList < sObj)) {
      return raw.substring(sList, eList + 1);
    }
    if (sObj != -1 && eObj > sObj) {
      return raw.substring(sObj, eObj + 1);
    }
    return raw;
  }

  /// 常见脏数据修复 (注意: 字符串内替换是近似处理, 能保解析成功优先)
  static String _repair(String s) {
    var t = s;
    for (final bad in ['\uFEFF', '\u200B', '\u200C', '\u200E', '\u00AD']) {
      t = t.replaceAll(bad, '');
    }
    t = t.replaceAll('“', '"');
    t = t.replaceAll('”', '"');
    t = t.replaceAll('„', '"');
    t = t.replaceAll('‘', '"');
    t = t.replaceAll('’', '"');
    t = t.replaceAll('：', ':');
    // 尾逗号: [1,2,] / {"a":1,} —— 循环处理深层嵌套
    for (var i = 0; i < 3; i++) {
      final next = t.replaceAll(RegExp(r',(\s*[}\]])'), r'$1');
      if (next == t) break;
      t = next;
    }
    return t;
  }

  // ---------- 结构归一 ----------

  /// 兼容多种包装: 数组本身 / {"items":[...]} / {"recipes":[...]} / {"data":[...]}
  static List<Map<String, dynamic>> asMapList(Object? value) {
    if (value is List) {
      return value
          .whereType<Map>()
          .map((e) => e.map((k, v) => MapEntry(k.toString(), v)))
          .toList();
    }
    if (value is Map) {
      for (final key in ['items', 'recipes', 'ingredients', 'list', 'data']) {
        final v = value[key];
        if (v is List) {
          return v
              .whereType<Map>()
              .map((e) => e.map((k, v2) => MapEntry(k.toString(), v2)))
              .toList();
        }
      }
    }
    return const [];
  }

  // ---------- 领域级解析 (带校验清洗) ----------

  /// 自然语言 → 食材草稿列表 (规格: 结构化 JSON 数组)
  static List<IngredientDraft> parseIngredientDrafts(String raw) {
    final value = robustParse(raw);
    final list = asMapList(value);
    final out = <IngredientDraft>[];
    final seen = <String, int>{}; // 去重: name|zone → 下标 (同名同区合并数量)
    for (final m in list) {
      final name = (m['name'] ?? '').toString().trim();
      if (name.isEmpty) continue; // 丢弃垃圾条目
      final draft = IngredientDraft.fromJson(m);
      final key = '${draft.name}|${draft.zone.name}';
      final idx = seen[key];
      if (idx != null) {
        out[idx] = out[idx].mergedWith(draft.quantity);
      } else {
        seen[key] = out.length;
        out.add(draft);
      }
    }
    return out;
  }

  /// AI 食谱输出 → RecipePlan; 全失败时返回 failure 并保留原文给 UI 兜底渲染
  static RecipePlan parseRecipePlan(String raw) {
    Object? value;
    try {
      value = robustParse(raw);
    } on FormatException {
      return RecipePlan.failure(rawMarkdown: raw);
    }
    final list = asMapList(value);
    final recipes =
        list.map(Recipe.tryFromJson).whereType<Recipe>().toList();
    if (recipes.isEmpty) {
      return RecipePlan.failure(rawMarkdown: raw);
    }
    return RecipePlan.success(recipes);
  }
}
