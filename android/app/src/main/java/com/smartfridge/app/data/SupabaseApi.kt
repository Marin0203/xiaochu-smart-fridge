package com.smartfridge.app.data

import com.smartfridge.app.core.Config
import com.smartfridge.app.domain.Ingredient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID

/**
 * Supabase 远程数据访问 (PostgREST REST 直连 + Realtime WebSocket)。
 * 所有请求带用户 JWT → Postgres RLS 自动过滤到本家庭。
 *
 * PostgREST 约定:
 *  - 查询  GET /rest/v1/ingredients?select=*&family_id=eq.X&order=updated_at.desc
 *  - 写入  POST /rest/v1/ingredients?on_conflict=id  body=[{...}]  Prefer: resolution=merge-duplicates
 *  - 删除  DELETE /rest/v1/ingredients?id=eq.X  (由 RLS 保证只能删本家庭)
 */
class SupabaseApi(private val key: String = Config.supabaseKey) {

    private val rest = Config.supabaseUrl.trimEnd('/') + "/rest/v1"
    private val wsBase = "wss://${Config.projectRef}.supabase.co/realtime/v1/websocket"
    private val client: OkHttpClient = Http.client

    fun headers(token: String?): Map<String, String> =
        baseHeaders(key, token, mapOf("Accept" to "application/json"))

    suspend fun fetchAll(familyId: String, token: String?): List<Ingredient> {
        val url = "$rest/ingredients?select=*&family_id=eq.$familyId&order=updated_at.desc"
        val res = Http.get(url, headers(token))
        if (!res.isOk) throw IllegalStateException("fetchAll HTTP ${res.code}")
        return Ingredient.parseList(res.jsonOrNull())
    }

    suspend fun fetchOne(familyId: String, id: String, token: String?): Ingredient? {
        val url = "$rest/ingredients?select=*&family_id=eq.$familyId&id=eq.$id&limit=1"
        val res = Http.get(url, headers(token))
        if (!res.isOk) throw IllegalStateException("fetchOne HTTP ${res.code}")
        val parsed = Ingredient.parseList(res.jsonOrNull())
        return parsed.firstOrNull()
    }

    suspend fun upsert(item: Ingredient, token: String?) {
        val url = "$rest/ingredients?on_conflict=id"
        val body = listOf(item.toJsonObject()).toString()
        val res = Http.post(
            url,
            baseHeaders(key, token, mapOf(
                "Content-Type" to "application/json",
                "Prefer" to "resolution=merge-duplicates,return=minimal",
            )),
            body,
        )
        if (!res.isOk) throw IllegalStateException("upsert HTTP ${res.code}")
    }

    suspend fun delete(familyId: String, id: String, token: String?) {
        val url = "$rest/ingredients?family_id=eq.$familyId&id=eq.$id"
        val res = Http.delete(
            url,
            baseHeaders(key, token, mapOf("Prefer" to "return=minimal")),
        )
        if (!res.isOk) throw IllegalStateException("delete HTTP ${res.code}")
    }

    // ---------- Realtime (Postgres Changes over WebSocket) ----------

    /**
     * Realtime 变更流: 服务器推 INSERT/UPDATE/DELETE → 行级事件。
     * 断线自动重连 (3s 间隔); token 变化时由调用方重启流 (见 SyncService)。
     */
    fun watchRealtime(familyId: String, tokenProvider: () -> String?): Flow<RealtimeChange> = callbackFlow {
        val job = launch(Dispatchers.IO) {
            while (!isClosedForSend) {
                val closed = CompletableDeferred<Unit>()
                val channelId = UUID.randomUUID().toString()
                val topic = "realtime:ingredients"

                val listener = object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        // 1) 先 join 占位 channel, 换取 channel access_token
                        ws.send(
                            """{"topic":"realtime:stub","event":"phx_join","payload":{},"ref":"1"}"""
                        )
                    }

                    override fun onMessage(ws: WebSocket, text: String) {
                        val obj = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return }
                        when (obj["event"]?.jsonPrimitive?.contentOrNull) {
                            "access_token" -> {
                                // 2) 回执 access_token, 随后订阅 postgres_changes
                                val t = obj["payload"]?.jsonObject
                                    ?.get("access_token")?.jsonPrimitive?.contentOrNull ?: return
                                ws.send(accessTokenReply("realtime:stub", t, "2"))
                                ws.send(subscribeJson(channelId, topic, familyId, "3", tokenProvider()))
                            }
                            "postgres_changes" -> {
                                val data = obj["payload"]?.jsonObject?.get("data")?.jsonObject ?: return
                                // 协议兼容: 老版本字段 type/record/old_record, 新包装层 eventType/new/old
                                val type = (data["type"] ?: data["eventType"])?.jsonPrimitive?.contentOrNull
                                val record = (data["record"] ?: data["new"])?.jsonObject
                                val old = (data["old_record"] ?: data["old"])?.jsonObject
                                when (type?.uppercase()) {
                                    "INSERT", "UPDATE" -> {
                                        record?.let { Ingredient.fromJson(it)?.let { r -> trySend(RealtimeChange(upserts = listOf(r))) } }
                                    }
                                    "DELETE" -> {
                                        val id = old?.get("id")?.jsonPrimitive?.contentOrNull
                                        if (id != null) trySend(RealtimeChange(deletedIds = listOf(id)))
                                    }
                                }
                            }
                            "phx_reply" -> { /* 订阅应答, 忽略 */ }
                        }
                    }

                    override fun onClosed(ws: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                }

                val ws = client.newWebSocket(
                    Request.Builder().url("$wsBase?apikey=$key&vsn=1.0.0").build(),
                    listener,
                )
                try {
                    closed.await()
                } catch (_: CancellationException) {
                    ws.cancel()
                    break
                }
                ws.cancel()
                delay(3000) // 断线重连间隔
            }
        }
        awaitClose { job.cancel() }
    }

    private fun accessTokenReply(topic: String, token: String, ref: String) = buildJsonObject {
        put("topic", topic); put("event", "access_token")
        put("payload", buildJsonObject { put("access_token", token) }); put("ref", ref)
    }.toString()

    private fun subscribeJson(
        channelId: String, topic: String, familyId: String, ref: String, userToken: String?,
    ): String = buildJsonObject {
        put("topic", topic)
        put("event", "phx_join")
        put("payload", buildJsonObject {
            put("config", buildJsonObject {
                put("broadcast", buildJsonObject { put("ack", false); put("self", false) })
                put("presence", buildJsonObject { put("key", ""); put("join", buildJsonObject {}) })
                put("postgres_changes", kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject {
                        put("event", "*")
                        put("schema", "public")
                        put("table", "ingredients")
                        put("filter", "family_id=eq.$familyId")
                    }
                )))
            })
        })
        put("ref", ref)
    }.toString()
}

/** 一次 Realtime 变更: upserts 为新插入/更新行, deletedIds 为被删除行 */
data class RealtimeChange(
    val upserts: List<Ingredient> = emptyList(),
    val deletedIds: List<String> = emptyList(),
)
