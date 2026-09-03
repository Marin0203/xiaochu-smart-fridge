import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../data/app_services.dart';
import '../../domain/models/ingredient.dart';

/// 库存页 VM: 订阅本地优先仓库, 按区域分页展示, 分钟级刷新新鲜度
class InventoryViewModel extends ChangeNotifier {
  final IngredientRepository repo;
  final String userName;

  List<Ingredient> _items = const [];
  bool loading = true;
  String? error;
  String query = '';

  StreamSubscription<List<Ingredient>>? _sub;
  Timer? _tick;

  InventoryViewModel(this.repo, this.userName) {
    _init();
  }

  Future<void> _init() async {
    try {
      _items = await repo.listAll();
    } catch (e) {
      error = '加载库存失败: $e';
    }
    loading = false;
    _sub = repo.watchAll().listen(
      (list) {
        _items = list;
        notifyListeners();
      },
      onError: (Object e) {
        error = '实时同步异常: $e';
        notifyListeners();
      },
    );
    // 新鲜度随时间流逝变化 → 每分钟刷新一次 UI (动画/进度条)
    _tick = Timer.periodic(const Duration(minutes: 1), (_) => notifyListeners());
    notifyListeners();
  }

  /// 某区域的条目, 按最新鲜度升序排 → 最临期的永远在最上面
  List<Ingredient> itemsOf(StorageZone zone) {
    final q = query.trim();
    final list = _items
        .where((i) =>
            i.zone == zone && (q.isEmpty || i.name.contains(q)))
        .toList();
    list.sort((a, b) => a.freshnessPercent().compareTo(b.freshnessPercent()));
    return list;
  }

  int countOf(StorageZone zone) => _items.where((i) => i.zone == zone).length;

  /// 黄+红 预警总数 (库存页顶部横幅)
  int get alertCount => _items
      .where((i) => i.freshnessStatus().isAlert)
      .length;

  Future<void> consume(Ingredient item, {double amount = 1}) async {
    try {
      await repo.consume(item.id, amount: amount);
    } catch (e) {
      error = '操作失败: $e';
      notifyListeners();
    }
  }

  Future<void> remove(Ingredient item) async {
    try {
      await repo.remove(item.id);
    } catch (e) {
      error = '删除失败: $e';
      notifyListeners();
    }
  }

  Future<void> syncNow() async {
    error = null;
    notifyListeners();
    try {
      await repo.syncNow();
    } catch (e) {
      error = '同步失败: $e';
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _sub?.cancel();
    _tick?.cancel();
    super.dispose();
  }
}
