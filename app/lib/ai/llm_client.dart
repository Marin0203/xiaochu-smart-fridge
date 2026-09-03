import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:supabase_flutter/supabase_flutter.dart';

import '../core/config.dart';
import '../domain/models/ingredient_draft.dart';
import '../domain/models/recipes.dart';
import 'prompts.dart';
import 'resilient_json.dart';

/// AI 调用统一异常 (UI 层只认它, 出错给友好提示)
class AiException implements Exception {
  final String message;
  AiException(this.message);
  @override
  String toString() => message;
}

/// AI 服务抽象: ① 自然语言解析入库 ② 临期优先食谱推荐。
///
/// 两个实现, 用工厂按配置切换 (这是「密钥分离」的关键点):
///  - EdgeFunctionAiService  默认: DeepSeek Key 只存 Supabase Edge Function
///  - DirectOpenAiAiService  直连 OpenAI 兼容 API (仅本地 Ollama / 自部署时用)
abstract class AiService {
  Future<List<IngredientDraft>> parseNaturalLanguage(String text);
  Future<RecipePlan> recommendRecipes({
    required List<ExpiringItem> expiring,
    List<ExpiringItem> context = const [],
  });
}

AiService createAiService() =>
    AppConfig.isDirectMode ? DirectOpenAiAiService() : EdgeFunctionAiService();

/// 实现一 (默认): 走 Supabase Edge Function, LLM 密钥不出服务器
class EdgeFunctionAiService implements AiService {
  final SupabaseClient _sb = Supabase.instance.client;

  @override
  Future<List<IngredientDraft>> parseNaturalLanguage(String text) async {
    try {
      final res =
          await _sb.functions.invoke('ai-parse', body: {'text': text});
      final data = res.data;
      if (data is Map && data['items'] is List) {
        // 链路二次校验: 即使服务器/代理坏了, 客户端解析器也能兜住
        return ResilientJson.parseIngredientDrafts(jsonEncode(data['items']));
      }
      throw AiException(_errorOf(data) ?? '解析服务返回数据异常');
    } on FunctionException catch (e) {
      throw AiException('解析服务不可用 (${e.status}): ${e.message}');
    } catch (e) {
      if (e is AiException) rethrow;
      throw AiException('解析调用失败: $e');
    }
  }

  @override
  Future<RecipePlan> recommendRecipes({
    required List<ExpiringItem> expiring,
    List<ExpiringItem> context = const [],
  }) async {
    try {
      final res = await _sb.functions.invoke('ai-recipe', body: {
        'expiring': expiring.map((e) => e.toJson()).toList(),
        'context': context.map((e) => e.toJson()).toList(),
      });
      final data = res.data;
      if (data is Map && data['recipes'] is List) {
        return ResilientJson.parseRecipePlan(jsonEncode(data));
      }
      if (data is Map && data['markdown'] is String) {
        return RecipePlan.failure(rawMarkdown: data['markdown']);
      }
      throw AiException(_errorOf(data) ?? '食谱服务返回数据异常');
    } on FunctionException catch (e) {
      throw AiException('食谱服务不可用 (${e.status}): ${e.message}');
    } catch (e) {
      if (e is AiException) rethrow;
      throw AiException('食谱调用失败: $e');
    }
  }

  static String? _errorOf(Object? data) =>
      data is Map ? data['error']?.toString() : null;
}

/// 实现二: 直连 OpenAI 兼容 API (DeepSeek / Qwen / Ollama / vLLM 均可)。
/// ⚠️ 密钥进客户端, 仅推荐本地开发或自部署场景使用。
class DirectOpenAiAiService implements AiService {
  final String _base = AppConfig.openAiBaseUrl;
  final String _key = AppConfig.openAiApiKey;
  final String _model = AppConfig.openAiModel;

  Future<String> _chat(String system, String user) async {
    if (_key.isEmpty) {
      throw AiException('direct 模式需要 OPENAI_API_KEY (或改用 edge 模式)');
    }
    final resp = await http
        .post(
          Uri.parse('$_base/chat/completions'),
          headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer $_key',
          },
          body: jsonEncode({
            'model': _model,
            'temperature': 0.1,
            'max_tokens': 2500,
            'response_format': {'type': 'json_object'},
            'messages': [
              {'role': 'system', 'content': system},
              {'role': 'user', 'content': user},
            ],
          }),
        )
        .timeout(const Duration(seconds: 60));
    if (resp.statusCode != 200) {
      throw AiException('LLM HTTP ${resp.statusCode}: ${resp.body}');
    }
    Map<String, dynamic> data;
    try {
      data = jsonDecode(utf8.decode(resp.bodyBytes)) as Map<String, dynamic>;
    } catch (_) {
      throw AiException('LLM 返回内容不是合法 JSON');
    }
    final choices = data['choices'];
    final content = (choices is List && choices.isNotEmpty)
        ? (choices.first as Map)['message']?['content']?.toString()
        : null;
    if (content == null || content.trim().isEmpty) {
      throw AiException('LLM 响应缺少内容');
    }
    return content;
  }

  @override
  Future<List<IngredientDraft>> parseNaturalLanguage(String text) async {
    final content = await _chat(AiPrompts.parseSystem, AiPrompts.buildParseUser(text));
    return ResilientJson.parseIngredientDrafts(content);
  }

  @override
  Future<RecipePlan> recommendRecipes({
    required List<ExpiringItem> expiring,
    List<ExpiringItem> context = const [],
  }) async {
    final content = await _chat(
      AiPrompts.recipeSystem,
      AiPrompts.buildRecipeUser(
        expiring: expiring.map((e) => e.toJson()).toList(),
        context: context.map((e) => e.toJson()).toList(),
      ),
    );
    return ResilientJson.parseRecipePlan(content);
  }
}
