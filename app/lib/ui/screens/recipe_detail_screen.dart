import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';

import '../../domain/models/recipes.dart';

/// 菜谱详情: Markdown 渲染 (flutter_markdown_plus, 官方 fork 的社区维护版)
class RecipeDetailScreen extends StatelessWidget {
  final Recipe recipe;
  const RecipeDetailScreen({super.key, required this.recipe});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(recipe.title)),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 40),
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _MetaChip('⏱ 约 ${recipe.minutes} 分钟'),
              for (final u in recipe.uses)
                _MetaChip('优先消耗: $u', warn: true),
            ],
          ),
          const SizedBox(height: 16),
          // —— 核心: AI 生成的详细菜谱 Markdown ——
          MarkdownBody(
            data: recipe.markdownBody,
            selectable: true,
            styleSheet: MarkdownStyleSheet.fromTheme(Theme.of(context))
                .copyWith(
              p: const TextStyle(fontSize: 15, height: 1.7),
              listBullet: const TextStyle(fontSize: 15),
              code: const TextStyle(fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}

class _MetaChip extends StatelessWidget {
  final String text;
  final bool warn;
  const _MetaChip(this.text, {this.warn = false});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: warn ? const Color(0xFFFFF3E0) : Colors.grey.shade100,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(text,
          style: TextStyle(
              fontSize: 12,
              color: warn ? const Color(0xFFE65100) : Colors.grey.shade700)),
    );
  }
}
