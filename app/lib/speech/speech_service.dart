import 'dart:async';

import 'package:speech_to_text/speech_to_text.dart' as stt;

/// 语音转文字抽象 —— 引擎可插拔 (设计上分离的方向之一):
///  - NativeSpeechService: 系统原生识别 (开箱即用, 零模型体积)
///  - WhisperCppSpeechService: 本地离线 whisper.cpp 升级接入点
/// UI 层只依赖本接口, 换引擎不改任何视图/ViewModel 代码。
abstract class SpeechService {
  Future<bool> init();
  Future<String> startListening({Duration timeout});
  Future<void> stop();
  Stream<String> get partialResults;
  bool get isListening;
  void dispose();
}

class SpeechUnavailableException implements Exception {
  final String message;
  SpeechUnavailableException(this.message);
  @override
  String toString() => message;
}

/// 实现 A: 系统原生 ASR (speech_to_text)。
/// 优点: 免费/无模型体积; 注意: 无 Google 服务的国行安卓可能不可用
/// (此时 UI 自动降级为「手动输入文字」, 功能不中断)。
class NativeSpeechService implements SpeechService {
  final stt.SpeechToText _stt = stt.SpeechToText();
  final _controller = StreamController<String>.broadcast();
  bool _available = false;
  String _lastText = '';

  @override
  Future<bool> init() async {
    try {
      // speech_to_text v7: 命名参数是 onError / onStatus (不是 errorListener)
      _available = await _stt.initialize(onError: (e) {});
    } catch (_) {
      _available = false;
    }
    return _available;
  }

  @override
  Future<String> startListening({Duration timeout = const Duration(seconds: 15)}) {
    if (!_available) throw SpeechUnavailableException('语音引擎不可用');
    _lastText = '';
    final completer = Completer<String>();
    _stt.listen(
      onResult: (r) {
        _lastText = r.recognizedWords;
        if (!_controller.isClosed) _controller.add(_lastText);
      },
      localeId: 'zh_CN',
      listenFor: timeout,
      partialResults: true,
      listenMode: stt.ListenMode.dictation,
    );
    // 兜底完成: 引擎可能不回调结束, 用超时收尾
    Timer(timeout + const Duration(seconds: 2), () {
      if (!completer.isCompleted) {
        _stt.stop();
        completer.complete(_lastText);
      }
    });
    return completer.future;
  }

  @override
  Future<void> stop() async {
    await _stt.stop();
  }

  @override
  Stream<String> get partialResults => _controller.stream;

  @override
  bool get isListening => _stt.isListening;

  @override
  void dispose() {
    _controller.close();
    _stt.cancel();
  }
}

/// 实现 B (升级接入点): 本地 Whisper.cpp / sherpa-onnx 离线 ASR。
/// 中文场景推荐 sherpa-onnx + FunASR(paraformer) 模型, 或 flutter_whisper +
/// whisper small 模型; 按所选插件 API 实现 startListening 内
/// 「录音 → 16k 16bit mono wav → 模型推理 → 文本」即可, 其余协议不变。
/// 接入步骤见根目录 README「本地离线 ASR 升级」。
class WhisperCppSpeechService implements SpeechService {
  @override
  Future<bool> init() async =>
      throw UnimplementedError('接入 whisper.cpp 插件后实现, 见 README');

  @override
  Future<String> startListening({Duration timeout = const Duration(seconds: 15)}) =>
      throw UnimplementedError('接入 whisper.cpp 插件后实现, 见 README');

  @override
  Future<void> stop() async {}

  @override
  Stream<String> get partialResults => const Stream.empty();

  @override
  bool get isListening => false;

  @override
  void dispose() {}
}
