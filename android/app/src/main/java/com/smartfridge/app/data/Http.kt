package com.smartfridge.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** HTTP 响应 (不自动抛错, 状态码与原文交给上层裁决) */
data class HttpResult(val code: Int, val body: String) {
    val isOk: Boolean get() = code in 200..299
    fun jsonOrNull(): JsonElement? = try {
        if (body.isBlank()) null else Json.parseToJsonElement(body)
    } catch (_: Exception) {
        null
    }
}

/**
 * 统一 HTTP 客户端 (OkHttp):
 *  - Supabase 侧: PostgREST (/rest/v1) / GoTrue (/auth/v1) / Edge Functions (/functions/v1)
 *  - 全部走纯 REST, 依赖面最小且协议稳定; 想换官方 supabase-kt 只需替换 data 包
 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    /** 统一请求入口 —— 强制切到 IO 线程 (安卓禁止主线程网络请求),
     *  所有调用方都在 suspend 上下文, 这里包一层 withContext 即彻底规避 NetworkOnMainThreadException */
    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): HttpResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete(body?.toRequestBody(jsonMedia))
            else -> builder.method(method, body?.toRequestBody(jsonMedia))
        }
        try {
            client.newCall(builder.build()).execute().use { resp ->
                HttpResult(resp.code, resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            HttpResult(0, e.message ?: "网络错误") // code=0 表示无响应(离线/断网)
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()) = request("GET", url, headers)
    suspend fun post(url: String, headers: Map<String, String> = emptyMap(), body: String? = null) =
        request("POST", url, headers, body)
    suspend fun delete(url: String, headers: Map<String, String> = emptyMap()) = request("DELETE", url, headers)
}

/** GoTrue / PostgREST 公共头 */
fun baseHeaders(apikey: String, token: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> =
    buildMap {
        put("apikey", apikey)
        if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
        putAll(extra)
    }

/** 解析 GoTrue / PostgREST 错误正文: 兼容 {"message":...} / {"error_description":...} / {"msg":...} / {"hint":...};
 *  code=0 表示请求根本没发出去(离线/DNS/超时), 直接显示原始原因 */
fun parseAuthError(result: HttpResult): String {
    if (result.code == 0) {
        return result.body.ifBlank { "网络连接失败(请检查网络或切换流量/WiFi)" }
    }
    val obj = result.jsonOrNull() as? kotlinx.serialization.json.JsonObject
        ?: return "HTTP ${result.code}: ${result.body.take(120)}"
    val message = obj["message"]?.toString()?.trim('"')
        ?: obj["error_description"]?.toString()?.trim('"')
        ?: obj["msg"]?.toString()?.trim('"')
    val hint = obj["hint"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
    return buildString {
        append(message ?: "HTTP ${result.code}")
        if (!hint.isNullOrBlank()) append(" ($hint)")
    }
}
