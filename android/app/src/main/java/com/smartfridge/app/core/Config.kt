package com.smartfridge.app.core

import com.smartfridge.app.BuildConfig

/**
 * 全局配置 —— 全部来自 local.properties → BuildConfig, 无密钥硬编码。
 * 密钥文件见 android/local.properties.example。
 */
object Config {
    val supabaseUrl: String = BuildConfig.SUPABASE_URL
    val supabaseKey: String = BuildConfig.SUPABASE_KEY

    /** edge=Edge Function 代理(推荐) / direct=客户端直连 OpenAI 兼容 API(仅本地/自部署) */
    val aiMode: String = BuildConfig.AI_MODE
    val openAiBaseUrl: String = BuildConfig.OPENAI_BASE_URL
    val openAiApiKey: String = BuildConfig.OPENAI_API_KEY
    val openAiModel: String = BuildConfig.OPENAI_MODEL

    /** 家庭共享账号: 打开 App 自动登录 + 自动建家庭, 免注册 (两人自用场景; 见 AuthRepository) */
    val autoEmail: String = BuildConfig.AUTO_EMAIL
    val autoPassword: String = BuildConfig.AUTO_PASSWORD

    val isConfigured: Boolean get() = supabaseUrl.isNotBlank() && supabaseKey.isNotBlank()
    val isDirectMode: Boolean get() = aiMode == "direct"

    /** 项目 ref: 从 URL 提取 (用于拼 ws/realtime 地址) */
    val projectRef: String get() = Regex("https?://([^.]+)\\.").find(supabaseUrl)?.groupValues?.get(1) ?: ""
}
