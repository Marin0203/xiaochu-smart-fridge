package com.smartfridge.app.ai

import com.smartfridge.app.core.Config
import com.smartfridge.app.data.Http
import com.smartfridge.app.data.HttpResult
import com.smartfridge.app.data.baseHeaders
import kotlinx.coroutines.delay
import com.smartfridge.app.domain.ExpiringItem
import com.smartfridge.app.domain.IngredientDraft
import com.smartfridge.app.domain.RecipeMode
import com.smartfridge.app.domain.RecipePlan
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** AI 调用统一异常 (UI 层只认它, 出错给友好提示) */
class AiException(message: String) : Exception(message)

/**
 * AI 服务抽象: ① 自然语言解析入库 ② 临期优先食谱推荐。
 *
 * 两个实现, 用工厂按配置切换 (「密钥分离」的关键点):
 *  - EdgeFunctionAiService 默认: DeepSeek Key 只存 Supabase Edge Function
 *  - DirectOpenAiService    直连 OpenAI 兼容 API (仅本地 Ollama / 自部署)
 */
interface AiService {
    suspend fun parseNaturalLanguage(text: String): List<IngredientDraft>
    suspend fun recommendRecipes(
        expiring: List<ExpiringItem>,
        context: List<ExpiringItem> = emptyList(),
        mode: RecipeMode = RecipeMode.EXPIRING,
        avoid: List<String> = emptyList(),   /* 上次菜名：尽量避开，求花样 */
    ): RecipePlan
}

fun createAiService(tokenProvider: () -> String?, onUnauthorized: (suspend () -> String?)? = null): AiService =
    if (Config.isDirectMode) {
        DirectOpenAiService(Config.openAiBaseUrl, Config.openAiApiKey, Config.openAiModel)
    } else {
        EdgeFunctionAiService(tokenProvider, onUnauthorized)
    }

/** 实现一 (默认): 走 Supabase Edge Function, LLM 密钥不出服务器 */
class EdgeFunctionAiService(
    private val tokenProvider: () -> String?,
    private val onUnauthorized: (suspend () -> String?)? = null,
) : AiService {

    private val functions = Config.supabaseUrl.trimEnd('/') + "/functions/v1"

    private fun headers(token: String): Map<String, String> =
        baseHeaders(Config.supabaseKey, token, mapOf("Content-Type" to "application/json"))

    /** 网络闪断自动重试 (2 轮, 间隔 800ms) */
    private suspend fun postWithRetry(url: String, headers: Map<String, String>, body: String): HttpResult {
        var last: Exception? = null
        repeat(2) {
            try {
                val r = Http.post(url, headers, body)
                if (r.isOk || r.code != 0) return r // 服务器有响应(含错误码)不重试; 仅网络层失败重试
                last = Exception("网络不可达")
            } catch (e: Exception) {
                last = e
            }
            delay(800)
        }
        throw AiException("网络连接失败, 请检查网络: ${last?.message}")
    }

    override suspend fun parseNaturalLanguage(text: String): List<IngredientDraft> {
        var token = tokenProvider() ?: throw AiException("未登录, 无法调用 AI")
        val body = buildJsonObject { put("text", text) }.toString()
        var res = postWithRetry("$functions/ai-parse", headers(token), body)
        // 401 哨兵: token 失效 → 刷新一次后重试
        if (!res.isOk && res.code == 401 && onUnauthorized != null) {
            token = onUnauthorized() ?: throw AiException("登录已过期, 请重新进入应用")
            res = postWithRetry("$functions/ai-parse", headers(token), body)
        }
        if (!res.isOk) throw AiException("解析服务不可用 (HTTP ${res.code})")
        val obj = res.jsonOrNull() as? JsonObject ?: throw AiException("解析服务返回异常")
        val items = obj["items"]
            ?: throw AiException(obj["error"]?.jsonPrimitive?.contentOrNull ?: "解析服务返回异常")
        // 链路二次校验: 即使服务器/代理坏了, 客户端解析器也能兜住
        return ResilientJson.parseIngredientDrafts(items.toString())
    }

    override suspend fun recommendRecipes(
        expiring: List<ExpiringItem>,
        context: List<ExpiringItem>,
        mode: RecipeMode,
        avoid: List<String>,
    ): RecipePlan {
        val token = tokenProvider() ?: throw AiException("未登录, 无法调用 AI")
        val body = buildJsonObject {
            put("expiring", JsonArray(expiring.map { it.toJsonObject() }))
            put("context", JsonArray(context.map { it.toJsonObject() }))
            put("mode", mode.db)
            put("avoid", JsonArray(avoid.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString()
        val res = postWithRetry("$functions/ai-recipe", headers(token), body)
        if (!res.isOk) throw AiException("食谱服务不可用 (HTTP ${res.code})")
        val obj = res.jsonOrNull() as? JsonObject ?: throw AiException("食谱服务返回异常")
        val recipes = obj["recipes"]
        if (recipes is JsonArray) return ResilientJson.parseRecipePlan(recipes.toString())
        // 加固: 若 recipes 被转义成字符串 (前两层转义), 二次解析
        recipes?.jsonPrimitive?.contentOrNull?.let { raw ->
            return try {
                ResilientJson.parseRecipePlan(raw)
            } catch (_: Exception) {
                RecipePlan.failure(rawMarkdown = null, error = "AI 返回结构异常, 点击刷新重试")
            }
        }
        obj["markdown"]?.jsonPrimitive?.contentOrNull?.let {
            return RecipePlan.failure(rawMarkdown = it) // 容错: 原文给 UI 兜底渲染
        }
        throw AiException("食谱服务返回异常: ${res.body.take(80)}")
        throw AiException(obj["error"]?.jsonPrimitive?.contentOrNull ?: "食谱服务返回异常")
    }
}

/** 实现二: 直连 OpenAI 兼容 API (DeepSeek / Qwen / Ollama / vLLM 均可)。 ⚠️ 密钥进客户端, 仅本地/自部署可用 */
class DirectOpenAiService(
    private val baseUrl: String = Config.openAiBaseUrl,
    private val apiKey: String = Config.openAiApiKey,
    private val model: String = Config.openAiModel,
) : AiService {

    private suspend fun chat(system: String, user: String): String {
        if (apiKey.isBlank()) throw AiException("direct 模式需要 OPENAI_API_KEY (或改用 edge 模式)")
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.1)
            put("max_tokens", 2500)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", JsonArray(listOf(
                buildJsonObject { put("role", "system"); put("content", system) },
                buildJsonObject { put("role", "user"); put("content", user) },
            )))
        }.toString()
        val res = Http.post(
            "${baseUrl.trimEnd('/')}/chat/completions",
            mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $apiKey",
            ),
            body,
        )
        if (!res.isOk) throw AiException("LLM HTTP ${res.code}")
        val obj = res.jsonOrNull() as? JsonObject ?: throw AiException("LLM 返回内容不是合法 JSON")
        val choices = obj["choices"] as? JsonArray ?: throw AiException("LLM 响应缺少内容")
        val msg = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        val content = msg?.get("content")?.jsonPrimitive?.contentOrNull
        if (content.isNullOrBlank()) throw AiException("LLM 响应缺少内容")
        return content
    }

    override suspend fun parseNaturalLanguage(text: String): List<IngredientDraft> {
        val content = chat(Prompts.parseSystem, Prompts.buildParseUser(text))
        return ResilientJson.parseIngredientDrafts(content)
    }

    override suspend fun recommendRecipes(
        expiring: List<ExpiringItem>,
        context: List<ExpiringItem>,
        mode: RecipeMode,
        avoid: List<String>,
    ): RecipePlan {
        val (system, user) = if (mode == RecipeMode.NORMAL) {
            Prompts.recipeSystemNormal to Prompts.buildNormalRecipeUser(context.ifEmpty { expiring })
        } else {
            Prompts.recipeSystem to Prompts.buildRecipeUser(expiring, context)
        }
        val content = chat(system, user)
        return ResilientJson.parseRecipePlan(content)
    }
}
