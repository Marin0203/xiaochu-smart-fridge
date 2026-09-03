import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../../domain/models/recipes.dart';
import '../scope.dart';
import '../viewmodels/recipe_viewmodel.dart';

/// AI 菜谱页: 临期食材约束 → 生成 3 道菜谱
class RecipeScreen extends StatelessWidget {
  const RecipeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final services = ServicesScope.of(context);
    final repo = services.repository;
    if (repo == null) {
      return const Scaffold(
          body: Center(child: CircularProgressIndicator()));
    }
    return ChangeNotifierProvider(
      create: (_) => RecipeViewModel(repo, services.ai),
      child: const _RecipeBody(),
    );
  }
}

class _RecipeBody extends StatelessWidget {
  const _RecipeBody();

  @override
  Widget build(BuildContext context) {
    final vm = context.watch<RecipeViewModel>();
    return Scaffold(
      appBar: AppBar(title: const Text('AI 菜谱')),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (vm.notice.isNotEmpty) _NoticeBanner(text: vm.notice),
          _ExpiringHeader(vm: vm),
          Expanded(child: _PlanArea(vm: vm)),
        ],
      ),
    );
  }
}

/// 顶部: 临期食材约束清单 + 生成按钮
class _ExpiringHeader extends StatelessWidget {
  final RecipeViewModel vm;
  const _ExpiringHeader({required this.vm});

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('⚠ 临期食材 ${vm.expiring.length} 项 (作为 AI 的核心约束)',
                style: const TextStyle(
                    fontSize: 15, fontWeight: FontWeight.w600)),
            const SizedBox(height: 10),
            if (vm.expiring.isEmpty)
              Text('当前没有临期预警 — 库存很健康',
                  style: TextStyle(color: Colors.grey.shade600))
            else
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final e in vm.expiring)
                    Chip(
                      label: Text('${e.name} ${e.daysLeft}天',
                          style: const TextStyle(fontSize: 12)),
                      visualDensity: VisualDensity.compact,
                      backgroundColor: const Color(0xFFFFF3E0),
                      side: const BorderSide(color: Color(0xFFFFB300)),
                      labelStyle: const TextStyle(color: Color(0xFFE65100)),
                    ),
                ],
              ),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                icon: vm.loading
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                            strokeWidth: 2, color: Colors.white))
                    : const Icon(Icons.auto_awesome),
                label: Text(vm.loading ? '大模型思考中…' : '生成 3 道菜谱 (优先消耗临期)'),
                onPressed: vm.loading ? null : vm.generate,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _NoticeBanner extends StatelessWidget {
  final String text;
  const _NoticeBanner({required this.text});

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: Colors.orange.shade50,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(text,
          style: TextStyle(fontSize: 13, color: Colors.orange.shade900)),
    );
  }
}

class _PlanArea extends StatelessWidget {
  final RecipeViewModel vm;
  const _PlanArea({required this.vm});

  @override
  Widget build(BuildContext context) {
    final plan = vm.plan;
    if (plan == null) {
      return Center(
        child: Text(
          '点上方「生成菜谱」\nAI 会优先消耗黄色/红色预警的临期食材\n并输出 3 道菜谱与详细烹饪步骤',
          textAlign: TextAlign.center,
          style: TextStyle(color: Colors.grey.shade500, height: 1.8),
        ),
      );
    }
    if (!plan.ok) {
      // 兜底: AI 结构解析失败 → 直接渲染原始 Markdown (容错)
      return ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                plan.rawMarkdown ?? plan.error ?? 'AI 未能生成菜谱',
                style: const TextStyle(height: 1.6),
              ),
            ),
          ),
        ],
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 32),
      itemCount: plan.recipes.length,
      itemBuilder: (context, i) => _RecipeCard(
        recipe: plan.recipes[i],
        onTap: () => context.push('/recipes/detail', extra: plan.recipes[i]),
      ),
    );
  }
}

class _RecipeCard extends StatelessWidget {
  final Recipe recipe;
  final VoidCallback onTap;
  const _RecipeCard({required this.recipe, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text('${recipe.title}',
                        style: const TextStyle(
                            fontSize: 16, fontWeight: FontWeight.w700)),
                  ),
                  Text('⏱ ${recipe.minutes} 分钟',
                      style: TextStyle(
                          fontSize: 13, color: Colors.grey.shade600)),
                ],
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 6,
                children: [
                  for (final u in recipe.uses)
                    Chip(
                      label: Text('消耗: $u', style: const TextStyle(fontSize: 11)),
                      visualDensity: VisualDensity.compact,
                      backgroundColor: Colors.red.shade50,
                      side: BorderSide(color: Colors.red.shade200),
                      labelStyle: TextStyle(color: Colors.red.shade700),
                    ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                '${recipe.ingredients.length} 种食材 · ${recipe.steps.length} 个步骤 · 查看详情 →',
                style:
                    TextStyle(fontSize: 12, color: Colors.grey.shade500),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
