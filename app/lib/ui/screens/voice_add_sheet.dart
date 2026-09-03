import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../domain/models/ingredient.dart';
import '../../domain/models/ingredient_draft.dart';
import '../../speech/speech_service.dart';
import '../auth/auth_viewmodel.dart';
import '../scope.dart';
import '../viewmodels/voice_add_viewmodel.dart';

/// 语音/文本一键入库底部弹层。
/// 流程: 录音(或手动输入) → LLM 结构化解析 → 草稿预览(可调整/排除) → 确认入库
class VoiceAddSheet extends StatefulWidget {
  const VoiceAddSheet({super.key});

  static void open(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (_) => const VoiceAddSheet(),
    );
  }

  @override
  State<VoiceAddSheet> createState() => _VoiceAddSheetState();
}

class _VoiceAddSheetState extends State<VoiceAddSheet> {
  late final VoiceAddViewModel _vm;
  late final TextEditingController _textCtrl;

  @override
  void initState() {
    super.initState();
    final services = ServicesScope.of(context).services;
    final auth = context.read<AuthViewModel>();
    _vm = VoiceAddViewModel(
      repo: services.repository!,
      ai: services.ai,
      speech: NativeSpeechService(),
      userName: auth.displayName ?? '家庭成员',
    );
    _vm.addListener(_onVmChanged);
    _textCtrl = TextEditingController();
  }

  void _onVmChanged() {
    if (_textCtrl.text != _vm.rawText) {
      _textCtrl.text = _vm.rawText;
    }
  }

  @override
  void dispose() {
    _vm.removeListener(_onVmChanged);
    _textCtrl.dispose();
    _vm.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding:
          EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: ConstrainedBox(
        constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * 0.85),
        child: AnimatedBuilder(
          animation: _vm,
          builder: (context, _) {
            return SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 32),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      const Icon(Icons.mic, size: 20),
                      const SizedBox(width: 8),
                      const Text('语音录入',
                          style: TextStyle(
                              fontSize: 17, fontWeight: FontWeight.w600)),
                      const Spacer(),
                      IconButton(
                        icon: const Icon(Icons.close),
                        onPressed: () => Navigator.of(context).pop(),
                      ),
                    ],
                  ),
                  if (_vm.error.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    _ErrorBanner(
                        text: _vm.error,
                        onRetry: _vm.phase == VoiceAddPhase.preview
                            ? _vm.parse
                            : _vm.toggleListening),
                  ],
                  const SizedBox(height: 8),
                  ..._phaseWidgets(),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  List<Widget> _phaseWidgets() {
    switch (_vm.phase) {
      case VoiceAddPhase.idle:
        return [
          const SizedBox(height: 16),
          Center(
            child: CircleAvatar(
              radius: 44,
              backgroundColor:
                  Theme.of(context).colorScheme.primaryContainer,
              child: IconButton(
                iconSize: 44,
                icon: const Icon(Icons.mic),
                onPressed: _vm.toggleListening,
              ),
            ),
          ),
          const SizedBox(height: 16),
          const Center(
            child: Text(
              '点击麦克风开始说话\n例如："今天买了半斤猪肉和三个番茄"',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.grey),
            ),
          ),
          const SizedBox(height: 12),
          Center(
            child: TextButton.icon(
              icon: const Icon(Icons.keyboard_outlined),
              label: const Text('改为手动输入'),
              onPressed: _vm.startManualInput,
            ),
          ),
        ];

      case VoiceAddPhase.transcribing:
        return [
          const Center(
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                SizedBox(width: 12),
                Text('正在听…'),
              ],
            ),
          ),
          const SizedBox(height: 16),
          Text(
            _vm.rawText.isEmpty ? '请在安静环境说话' : _vm.rawText,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 16, height: 1.5),
          ),
          const SizedBox(height: 12),
          Center(
            child: TextButton(
                onPressed: _vm.toggleListening, child: const Text('停止')),
          ),
        ];

      case VoiceAddPhase.transcribed:
        return [
          TextField(
            controller: _textCtrl,
            maxLines: 4,
            minLines: 2,
            decoration: const InputDecoration(
              hintText: '说出或输入购买内容，例如: 今天买了半斤猪肉和三个番茄',
            ),
            onChanged: _vm.setRawText,
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              TextButton.icon(
                icon: const Icon(Icons.mic),
                label: const Text('重新语音'),
                onPressed: _vm.toggleListening,
              ),
              const Spacer(),
              FilledButton.icon(
                icon: const Icon(Icons.auto_awesome),
                label: const Text('AI 解析入库'),
                onPressed: _vm.parse,
              ),
            ],
          ),
        ];

      case VoiceAddPhase.parsing:
        return [
          const SizedBox(height: 24),
          const Center(
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                SizedBox(width: 12),
                Text('大模型正在整理食材…'),
              ],
            ),
          ),
          const SizedBox(height: 24),
        ];

      case VoiceAddPhase.preview:
        return [
          Text(
            '识别到 ${_vm.includedCount}/${_vm.drafts.length} 项食材，可取消勾选/调整:',
            style: const TextStyle(fontSize: 14, color: Colors.grey),
          ),
          const SizedBox(height: 8),
          for (var i = 0; i < _vm.drafts.length; i++)
            _DraftCard(
              index: i,
              draft: _vm.drafts[i],
              excluded: _vm.isExcluded(i),
              onToggleExclude: () => _vm.toggleExclude(i),
              onQty: (d) => _vm.adjustQuantity(i, d),
              onZone: (z) => _vm.setZone(i, z),
            ),
          const SizedBox(height: 12),
          Row(
            children: [
              TextButton(
                  onPressed: _vm.reset, child: const Text('取消')),
              const Spacer(),
              FilledButton.icon(
                icon: const Icon(Icons.check),
                label: Text('确认入库 ${_vm.includedCount} 项'),
                onPressed: () async {
                  final ok = await _vm.confirm();
                  if (ok && mounted) Navigator.of(context).pop(true);
                },
              ),
            ],
          ),
        ];

      case VoiceAddPhase.done:
        return [
          const SizedBox(height: 24),
          const Icon(Icons.check_circle,
              size: 56, color: Color(0xFF4CAF50)),
          const SizedBox(height: 8),
          const Center(
              child: Text('已入库，家庭成员将实时同步看到',
                  style: TextStyle(color: Colors.grey))),
          const SizedBox(height: 16),
          FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: const Text('完成')),
        ];
    }
  }
}

/// 草稿条目卡片: 勾选 + 数量步进 + 区域切换
class _DraftCard extends StatelessWidget {
  final int index;
  final IngredientDraft draft;
  final bool excluded;
  final VoidCallback onToggleExclude;
  final void Function(double delta) onQty;
  final void Function(StorageZone zone) onZone;

  const _DraftCard({
    required this.index,
    required this.draft,
    required this.excluded,
    required this.onToggleExclude,
    required this.onQty,
    required this.onZone,
  });

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: excluded ? 0.45 : 1,
      child: Card(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(4, 8, 12, 8),
          child: Row(
            children: [
              Checkbox(value: !excluded, onChanged: (_) => onToggleExclude()),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(draft.name,
                        style: const TextStyle(
                            fontSize: 15, fontWeight: FontWeight.w600)),
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: 6,
                      children: [
                        for (final z in StorageZone.values)
                          ChoiceChip(
                            label: Text(z.label,
                                style: const TextStyle(fontSize: 11)),
                            selected: draft.zone == z,
                            visualDensity: VisualDensity.compact,
                            onSelected: (_) => onZone(z),
                          ),
                      ],
                    ),
                    Text(
                      '保质期约 ${draft.shelfLifeDays} 天 (AI 预判)',
                      style: TextStyle(
                          fontSize: 11, color: Colors.grey.shade500),
                    ),
                  ],
                ),
              ),
              // 数量步进
              Row(
                children: [
                  IconButton(
                    icon: const Icon(Icons.remove_circle_outline, size: 20),
                    onPressed: draft.quantity > 0.5 ? () => onQty(-1) : null,
                  ),
                  Text(
                    '${draft.quantity}${draft.unit}',
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w600),
                  ),
                  IconButton(
                    icon: const Icon(Icons.add_circle_outline, size: 20),
                    onPressed: () => onQty(1),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  final String text;
  final VoidCallback? onRetry;
  const _ErrorBanner({required this.text, this.onRetry});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.red.shade50,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.red.shade200),
      ),
      child: Row(
        children: [
          Icon(Icons.error_outline, size: 18, color: Colors.red.shade700),
          const SizedBox(width: 8),
          Expanded(
              child: Text(text,
                  style: TextStyle(
                      fontSize: 13, color: Colors.red.shade700))),
          if (onRetry != null)
            TextButton(onPressed: onRetry, child: const Text('重试')),
        ],
      ),
    );
  }
}
