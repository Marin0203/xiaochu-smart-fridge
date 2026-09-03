import 'package:flutter_test/flutter_test.dart';
import 'package:smart_fridge/ai/resilient_json.dart';
import 'package:smart_fridge/domain/models/ingredient.dart';

/// 容错 JSON 解析器测试 —— AI 输出不可信, 任何脏格式都必须被收敛
void main() {
  group('resilient_json 提取修复', () {
    test('strip: markdown 代码块包裹', () {
      final drafts = ResilientJson.parseIngredientDrafts('''
以下是结果:
```json
[{"name":"猪肉","quantity":250,"unit":"克","zone":"FRIDGE","shelfLifeDays":3}]
```
完毕
''');
      expect(drafts.length, 1);
      expect(drafts.first.name, '猪肉');
      expect(drafts.first.quantity, 250);
    });

    test('strip: 前后废话 + 尾逗号', () {
      final drafts = ResilientJson.parseIngredientDrafts(
          '好的，我看看：[{"name":"番茄","quantity":3,"unit":"个","zone":"FRIDGE","shelfLifeDays":5,}] 请查收');
      expect(drafts.length, 1);
      expect(drafts.first.name, '番茄');
    });

    test('兼容 {"items":[...]} 包装', () {
      final drafts = ResilientJson.parseIngredientDrafts(
          '{"items":[{"name":"鸡蛋","quantity":6,"unit":"个","zone":"FRIDGE","shelfLifeDays":30}]}');
      expect(drafts.single.name, '鸡蛋');
    });

    test('全角标点/弯引号修复', () {
      final drafts = ResilientJson.parseIngredientDrafts(
          '{“items”：[{“name”：“猪肉”，“quantity”：250，“unit”：“克”，“zone”：“FRIDGE”，“shelfLifeDays”：3}]}');
      expect(drafts.single.name, '猪肉');
    });

    test('完全无法解析 → 抛出 FormatException (调用方降级)', () {
      expect(
        () => ResilientJson.parseIngredientDrafts('今天天气不错，没有食材'),
        throwsFormatException,
      );
    });
  });

  group('字段校验与清洗', () {
    test('空 name 跳过、非法数量归 1、非法 zone 归 FRIDGE、非法保质期归 3', () {
      final drafts = ResilientJson.parseIngredientDrafts('''
[
  {"name":"","quantity":5,"unit":"个","zone":"FRIDGE","shelfLifeDays":100},
  {"name":"苹果","quantity":-3,"unit":"个","zone":"地下室","shelfLifeDays":-1}
]
''');
      expect(drafts.length, 1);
      expect(drafts.first.name, '苹果');
      expect(drafts.first.quantity, 1);
      expect(drafts.first.zone, StorageZone.fridge);
      expect(drafts.first.shelfLifeDays, 3);
    });

    test('同名同区合并数量', () {
      final drafts = ResilientJson.parseIngredientDrafts('''
[
  {"name":"猪肉","quantity":250,"unit":"克","zone":"FRIDGE","shelfLifeDays":3},
  {"name":"猪肉","quantity":100,"unit":"克","zone":"FRIDGE","shelfLifeDays":3}
]
''');
      expect(drafts.length, 1);
      expect(drafts.single.quantity, 350);
    });
  });

  group('食谱计划兜底', () {
    test('结构解析失败时返回 failure 且保留原文', () {
      final plan = ResilientJson.parseRecipePlan(
          '抱歉，我无法生成：\n\n# 建议\n随便炒点');
      expect(plan.ok, false);
      expect(plan.rawMarkdown, contains('随便炒点'));
    });
  });
}
