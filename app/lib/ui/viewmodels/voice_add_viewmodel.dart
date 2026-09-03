import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import '../../ai/llm_client.dart';
import '../../data/app_services.dart';
import '../../domain/models/ingredient.dart';
import '../../domain/models/ingredient_draft.dart';
import '../../speech/speech_service.dart';

/// 语音/文本一键入库 VM —— 状态机:
/// idle → transcribing(录音中) → transcribed(已转写, 可编辑) → parsing(AI 解析)
///      → preview(待确认草稿) → done / error
enum VoiceAddPhase { idle, transcribing, transcribed, parsing, preview, done }

class VoiceAddViewModel extends ChangeNotifier {
  final IngredientRepository repo;
  final AiService ai;
  final SpeechService speech;
  final String userName;

  VoiceAddPhase phase = VoiceAddPhase.idle;
  String error = '';
  String rawText = '';
  bool editingManually = false;
  List<IngredientDraft> drafts = const [];
  final Set<int> _excluded = {};

  StreamSubscription<String>? _partialSub;

  VoiceAddViewModel({
    required this.repo,
    required this.ai,
    required this.speech,
    required this.userName,
  });

  int get includedCount => drafts.length - _excluded.length;

  bool isExcluded(int i) => _excluded.contains(i);

  void toggleExclude(int i) {
    if (!_excluded.remove(i)) _excluded.add(i);
    notifyListeners();
  }

  void adjustQuantity(int i, double delta) {
    final d = drafts[i];
    drafts = List.of(drafts)..[i] = d.copyWith(
        quantity: math.max(0.5, double.parse((d.quantity + delta).toStringAsFixed(1))));
    notifyListeners();
  }

  void setZone(int i, StorageZone zone) {
    final d = drafts[i];
    drafts = List.of(drafts)..[i] = d.copyWith(zone: zone);
    notifyListeners();
  }

  void setRawText(String v) {
    rawText = v;
    notifyListeners();
  }

  void startManualInput() {
    phase = VoiceAddPhase.transcribed;
    editingManually = true;
    error = '';
    notifyListeners();
  }

  /// 点麦克风: 启动识别; 识别不可用则自动降级为手动输入
  Future<void> toggleListening() async {
    if (speech.isListening) {
      await speech.stop();
      return;
    }
    phase = VoiceAddPhase.transcribing;
    error = '';
    notifyListeners();
    try {
      final ok = await speech.init();
      if (!ok) {
        error = '语音引擎不可用 (可能缺少麦克风权限或系统限制)，可直接输入文字';
        phase = VoiceAddPhase.transcribed;
        editingManually = true;
        notifyListeners();
        return;
      }
      _partialSub ??= speech.partialResults.listen((t) {
        rawText = t;
        notifyListeners();
      });
      final text = await speech.startListening();
      if (text.trim().isNotEmpty) rawText = text;
      if (phase == VoiceAddPhase.transcribing) {
        phase = VoiceAddPhase.transcribed;
        notifyListeners();
      }
    } catch (e) {
      error = '语音识别失败: $e';
      phase = VoiceAddPhase.transcribed;
      editingManually = true;
      notifyListeners();
    }
  }

  /// 调用 LLM 解析为结构化食材 (核心链路: 容错 JSON 解析器在 AiService 内)
  Future<void> parse() async {
    if (rawText.trim().isEmpty) {
      error = '请输入要入库的内容 (例如: 今天买了半斤猪肉和三个番茄)';
      phase = VoiceAddPhase.transcribed;
      notifyListeners();
      return;
    }
    phase = VoiceAddPhase.parsing;
    error = '';
    notifyListeners();
    try {
      final parsed = await ai.parseNaturalLanguage(rawText.trim());
      if (parsed.isEmpty) {
        error = '没有识别出食材，可修改文字后重试';
        phase = VoiceAddPhase.transcribed;
        notifyListeners();
        return;
      }
      drafts = parsed;
      _excluded.clear();
      phase = VoiceAddPhase.preview;
      notifyListeners();
    } on AiException catch (e) {
      error = e.message;
      phase = VoiceAddPhase.transcribed;
    } catch (e) {
      error = '解析失败: $e';
      phase = VoiceAddPhase.transcribed;
    }
    notifyListeners();
  }

  /// 确认入库 → 写本地 + 异步推送 (离线也能成功)
  Future<bool> confirm() async {
    final list = [
      for (var i = 0; i < drafts.length; i++)
        if (!_excluded.contains(i)) drafts[i],
    ];
    if (list.isEmpty) {
      error = '没有待入库的食材';
      phase = VoiceAddPhase.preview;
      notifyListeners();
      return false;
    }
    try {
      await repo.addFromDrafts(list, userName);
      phase = VoiceAddPhase.done;
      notifyListeners();
      return true;
    } catch (e) {
      error = '入库失败: $e';
      phase = VoiceAddPhase.preview;
      notifyListeners();
      return false;
    }
  }

  void reset() {
    phase = VoiceAddPhase.idle;
    error = '';
    rawText = '';
    drafts = const [];
    _excluded.clear();
    notifyListeners();
  }

  @override
  void dispose() {
    _partialSub?.cancel();
    speech.dispose();
    super.dispose();
  }
}
