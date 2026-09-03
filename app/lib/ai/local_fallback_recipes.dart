import '../domain/models/recipes.dart';

/// 本地降级菜谱: AI 服务不可用/断网时的备用方案, 保证「食谱推荐」功能永远可用。
/// 用第一个临期食材生成两道家常菜, 附「这是 AI 不可用时的本地推荐」标记。
RecipePlan localFallbackRecipes(List<ExpiringItem> expiring) {
  if (expiring.isEmpty) {
    return RecipePlan.failure(error: '没有可用的临期食材');
  }
  final e = expiring.first;

  Recipe cook(String title, String verb) => Recipe(
        title: title,
        minutes: 15,
        uses: [e.name],
        ingredients: [
          IngredientLine(e.name, '${_fmt(e.quantity)}${e.unit}'),
          const IngredientLine('葱姜蒜', '适量'),
          const IngredientLine('盐/生抽', '适量'),
        ],
        steps: [
          '1. **${e.name}** 洗净切好备用, 葱姜蒜切末',
          '2. 热锅冷油, 下葱姜蒜爆香',
          '3. $verb **${e.name}**, 加盐和少量生抽调味',
          '4. 大火炒至熟透即可出锅',
        ],
        tips: '本菜谱由本地规则生成 (AI 服务暂时不可用), 请尽快消耗临期食材',
      );

  final recipes = <Recipe>[
    cook('清炒${e.name}', '下锅翻炒'),
    Recipe(
      title: '${e.name}汤',
      minutes: 20,
      uses: [e.name],
      ingredients: [
        IngredientLine(e.name, '${_fmt(e.quantity)}${e.unit}'),
        const IngredientLine('清水', '2碗'),
        const IngredientLine('盐/鸡精', '适量'),
      ],
      steps: [
        '1. **${e.name}** 洗净处理',
        '2. 水开后下锅, 中火煮 10 分钟',
        '3. 加盐调味出锅',
      ],
      tips: '本菜谱由本地规则生成 (AI 服务暂时不可用)',
    ),
  ];
  return RecipePlan.fallback(recipes);
}

String _fmt(double v) =>
    v == v.roundToDouble() ? v.toInt().toString() : v.toString();
