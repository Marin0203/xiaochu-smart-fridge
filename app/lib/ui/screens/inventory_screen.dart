import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:go_router/go_router.dart';

import '../../domain/models/ingredient.dart';
import '../auth/auth_viewmodel.dart';
import '../scope.dart';
import '../viewmodels/inventory_viewmodel.dart';
import '../widgets/ingredient_card.dart';
import 'voice_add_sheet.dart';

/// 库存页: 冷藏/冷冻/常温三大页签 + 语音入库 FAB
class InventoryScreen extends StatelessWidget {
  const InventoryScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final services = ServicesScope.of(context);
    final repo = services.repository;
    final userName = context.watch<AuthViewModel>().displayName ?? '家庭成员';

    if (repo == null) {
      // ready 之前不可见, 防御性占位
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }
    return ChangeNotifierProvider(
      create: (_) => InventoryViewModel(repo, userName),
      child: const _InventoryBody(),
    );
  }
}

class _InventoryBody extends StatelessWidget {
  const _InventoryBody();

  @override
  Widget build(BuildContext context) {
    final vm = context.watch<InventoryViewModel>();
    return DefaultTabController(
      length: StorageZone.values.length,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('冰箱库存'),
          actions: [
            IconButton(
              icon: const Icon(Icons.sync),
              tooltip: '立即同步',
              onPressed: vm.syncNow,
            ),
          ],
        ),
        body: Column(
          children: [
            // 临期预警横幅
            if (vm.alertCount > 0)
              InkWell(
                onTap: () => context.go('/recipes'),
                child: Container(
                  margin: const EdgeInsets.fromLTRB(16, 4, 16, 4),
                  padding: const EdgeInsets.symmetric(
                      horizontal: 12, vertical: 8),
                  decoration: BoxDecoration(
                    color: const Color(0xFFFFF3E0),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.warning_amber,
                          size: 18, color: Color(0xFFE65100)),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          '${vm.alertCount} 项食材进入临期/过期，交给 AI 菜谱优先消耗 →',
                          style: const TextStyle(
                              fontSize: 13, color: Color(0xFFE65100)),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            // 搜索
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
              child: TextField(
                decoration: const InputDecoration(
                  hintText: '搜索食材…',
                  prefixIcon: Icon(Icons.search),
                  isDense: true,
                ),
                onChanged: (v) => vm.query = v,
              ),
            ),
            // 三大区域页签
            const TabBar(
              tabs: [
                Tab(text: '🥬 冷藏区'),
                Tab(text: '🧊 冷冻区'),
                Tab(text: '🏺 常温区'),
              ],
            ),
            Expanded(
              child: TabBarView(
                children: [
                  for (final zone in StorageZone.values) _ZoneTab(zone: zone),
                ],
              ),
            ),
          ],
        ),
        floatingActionButton: FloatingActionButton.extended(
          heroTag: 'voice-add',
          onPressed: () => VoiceAddSheet.open(context),
          icon: const Icon(Icons.mic),
          label: const Text('语音录入'),
        ),
      ),
    );
  }
}

class _ZoneTab extends StatelessWidget {
  final StorageZone zone;
  const _ZoneTab({required this.zone});

  @override
  Widget build(BuildContext context) {
    final vm = context.watch<InventoryViewModel>();
    final items = vm.itemsOf(zone);

    if (items.isEmpty) {
      return Center(
        child: Text(
          '${zone.label} 空空如也\n点右下角「语音录入」，或说"今天买了半斤猪肉和三个番茄"',
          textAlign: TextAlign.center,
          style: TextStyle(color: Colors.grey.shade500, height: 1.6),
        ),
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.only(top: 4, bottom: 100),
      itemCount: items.length,
      itemBuilder: (context, i) {
        final item = items[i];
        return IngredientCard(
          item: item,
          onConsume: () => vm.consume(item),
          onDelete: () => _confirmDelete(context, vm, item),
        );
      },
    );
  }

  Future<void> _confirmDelete(
      BuildContext context, InventoryViewModel vm, Ingredient item) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除食材'),
        content: Text('确定删除「${item.name}」吗？家庭成员都将看到变更。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('删除')),
        ],
      ),
    );
    if (ok == true) await vm.remove(item);
  }
}
