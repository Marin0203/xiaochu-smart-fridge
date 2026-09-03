/// 全局配置 —— 全部通过 --dart-define 注入, 不留任何密钥硬编码。
///
/// 使用方式 (推荐, 见根目录 README):
/// ```bash
/// flutter run --dart-define-from-file=secrets.json
/// ```
/// secrets.json 示例:
/// {
///   "SUPABASE_URL": "https://xxxx.supabase.co",
///   "SUPABASE_ANON_KEY": "eyJ...",
///   "AI_MODE": "edge"
/// }
class AppConfig {
  AppConfig._();

  /// Supabase 项目地址 (仪表盘 Project Settings → API)
  static const String supabaseUrl = String.fromEnvironment('SUPABASE_URL');

  /// Supabase anon key (RLS 保护下可公开; 勿用 service_role key!)
  static const String supabaseAnonKey = String.fromEnvironment('SUPABASE_ANON_KEY');

  /// AI 调用模式:
  ///  - 'edge'  (默认, 推荐): DeepSeek Key 只存服务器 Edge Function
  ///  - 'direct': 客户端直连 OpenAI 兼容 API (仅建议本地 Ollama / 自部署)
  static const String aiMode = String.fromEnvironment('AI_MODE', defaultValue: 'edge');

  /// direct 模式用 (OpenAI 兼容协议)
  static const String openAiBaseUrl = String.fromEnvironment(
    'OPENAI_BASE_URL',
    defaultValue: 'https://api.deepseek.com/v1',
  );
  static const String openAiApiKey = String.fromEnvironment('OPENAI_API_KEY');
  static const String openAiModel = String.fromEnvironment(
    'OPENAI_MODEL',
    defaultValue: 'deepseek-chat',
  );

  static bool get isConfigured => supabaseUrl.isNotEmpty && supabaseAnonKey.isNotEmpty;
  static bool get isDirectMode => aiMode == 'direct';
}
