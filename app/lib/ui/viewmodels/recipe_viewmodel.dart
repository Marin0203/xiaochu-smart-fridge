import 'package:flutter/foundation.dart';

import '../../ai/llm_client.dart';
import '../../ai/local_fallback_recipes.dart';
import '../../data/app_services.dart';
import '../../domain/models/recipes.dart';

/// AI 菜谱页 VM: 筛选黄+红临期食材 → 交给 LLM (失败自动本地降级)
class RecipeViewModel extends ChangeNotifier {
  final IngredientRepository repo;
  final AiService ai;

  List<ExpiringItem> expiring = const [];
  List<ExpiringItem> context = const [];
  RecipePlan? plan;
  bool loading = false;
  String notice = ''; // 非致命提示 (如: 已降级为本地推荐)

  RecipeViewModel(this.repo, this.ai) {
    _refreshExpiring();
  }

  Future<void> _refreshExpiring() async {
    try {
      final items = await repo.listAll();
      final now = DateTime.now();
      expiring = items
          .where((i) => i.freshnessStatus(now).isAlert) // 黄+红 预警集合
          .map((i) => ExpiringItem.fromIngredient(i, now))
          .toList();
      context = items
          .where((i) => !i.freshnessStatus(now).isAlert)
          .take(15)
          .map((i) => ExpiringItem.fromIngredient(i, now))
          .toList();
      notifyListeners();
    } catch (e) {
      notice = '加载库存失败: $e';
      notifyListeners();
    }
  }

  /// 生成菜谱: LLM 优先消耗临期食材; 任何 AI 故障 → 本地菜谱兜底
  Future<void> generate() async {
    if (expiring.isEmpty) {
      notice = '当前没有黄色/红色预警的临期食材 — 库存很健康, 无需紧急消耗';
      notifyListeners();
      return;
    }
    loading = true;
    notice = '';
    notifyListeners();
    try {
      plan = await ai.recommendRecipes(expiring: expiring, context: context);
    } on AiException catch (e) {
      plan = localFallbackRecipes(expiring);
      notice = 'AI 服务不可用，已切换到本地推荐: ${e.message}';
    } catch (e) {
      plan = localFallbackRecipes(expiring);
      notice = 'AI 服务异常，已切换到本地推荐: $e';
    }
    loading = false;
    notifyListeners();
  }
}
