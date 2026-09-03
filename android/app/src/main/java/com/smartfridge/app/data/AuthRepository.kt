package com.smartfridge.app.data

import android.content.Context
import com.smartfridge.app.core.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 认证 + 家庭状态机 (检查 → 登录 → 无家庭 → 就绪) */
enum class AuthFlowState { CHECKING, SIGNED_OUT, SIGNED_IN_NO_FAMILY, READY }

data class FamilyInfo(val familyId: String, val name: String, val inviteCode: String, val displayName: String)

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
)

/**
 * 认证仓库 —— 直接对接 Supabase GoTrue REST:
 *   POST /auth/v1/token?grant_type=password|refresh_token
 *   POST /auth/v1/signup          (注册, 可选邮箱验证)
 *   POST /auth/v1/logout
 * 家庭: POST /rest/v1/rpc/get_my_family|create_family|join_family (security definer, 见迁移 SQL)
 */
class AuthRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
    private val base = Config.supabaseUrl.trimEnd('/') + "/auth/v1"
    private val rest = Config.supabaseUrl.trimEnd('/') + "/rest/v1"

    private val _state = MutableStateFlow(AuthFlowState.CHECKING)
    val state: StateFlow<AuthFlowState> = _state.asStateFlow()

    var family: FamilyInfo? = null
        private set
    var lastError: String? = null
        private set
    var displayName: String? = null
        private set

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    fun token(): String? = _session.value?.accessToken

    /** 强制刷新一次会话（自愈入口：成功则更新并返回新 token，失败返回 null） */
    suspend fun refreshSession(): String? {
        val s = _session.value ?: return null
        val r = refreshToken(s) ?: return null
        _session.value = r
        save(r)
        return r.accessToken
    }

    /** 启动恢复(无感): 本地会话有效 → 直接进主页;
     *  无会话 → 用配置的家庭共享账号自动登录 (首次自动注册+建「我们家」);
     *  只有自动登录也失败(配置缺失/邮箱验证未关)时才落到手动登录页 */
    suspend fun restore() {
        displayName = prefs.getString("display_name", null)
        val saved = loadSavedSession()
        android.util.Log.i("AUTH", "restore: saved=${saved != null}")
        if (saved != null) {
            // 不轻信本地 expiresAt：只要有缓存会话就先强制刷新一次（防"陈旧 token 复用"导致全 401）
            _session.value = saved
            val refreshed = refreshToken(saved)
            android.util.Log.i("AUTH", "restore: refresh=${refreshed != null}")
            if (refreshed != null) {
                _session.value = refreshed
                _refreshFamily()
                android.util.Log.i("AUTH", "restore: done state=$_state")
                return
            }
            clear()
            _session.value = null
        }
        // —— 自动共享账号登录 (两人自用, 免注册流程) ——
        android.util.Log.i("AUTH", "restore: auto-login path")
        if (Config.autoEmail.isNotBlank() && Config.autoPassword.isNotBlank()) {
            val ok = signIn(Config.autoEmail, Config.autoPassword)
            android.util.Log.i("AUTH", "restore: signIn=$ok state=$_state")
            if (!ok) {
                // 首次安装: 账号不存在 → 自动注册并创建家庭
                val signed = signUpAndCreateFamily(
                    Config.autoEmail, Config.autoPassword, "小厨", "我们家",
                )
                if (!signed) {
                    if (lastError?.contains("邮箱验证") == true) {
                        lastError = "请到 Supabase → Authentication → Email 里关闭「Confirm email」后重试"
                    }
                    _state.value = AuthFlowState.SIGNED_OUT // 回落到手动登录页
                }
            }
        } else {
            _state.value = AuthFlowState.SIGNED_OUT
        }
    }

    suspend fun signIn(email: String, password: String): Boolean {
        val url = "$base/token?grant_type=password"
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }.toString()
        val res = Http.post(url, baseHeaders(Config.supabaseKey), body)
        if (!res.isOk) { lastError = parseAuthError(res); return false }
        val session = parseSession(res.body) ?: run { lastError = "登录响应异常"; return false }
        _session.value = session
        save(session)
        applyDisplayName(res.body)
        _refreshFamily()
        return _state.value != AuthFlowState.SIGNED_IN_NO_FAMILY || family != null
    }

    /** 注册并创建第一个家庭; 返回 false 时看 lastError (可能提示需邮箱验证) */
    suspend fun signUpAndCreateFamily(
        email: String, password: String, displayName: String, familyName: String,
    ): Boolean {
        val url = "$base/signup"
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
            put("data", buildJsonObject { put("display_name", displayName.trim()) })
        }.toString()
        val res = Http.post(url, baseHeaders(Config.supabaseKey), body)
        if (!res.isOk) { lastError = parseAuthError(res); return false }
        val session = parseSession(res.body)
        if (session == null) {
            // 项目开启邮箱验证: 用户需验证后再登录
            lastError = "注册成功，请先完成邮箱验证，再回来登录"
            return false
        }
        _session.value = session
        save(session)
        applyDisplayName(res.body)
        return createFamily(familyName, displayName)
    }

    suspend fun createFamily(familyName: String, displayName: String): Boolean {
        val body = buildJsonObject {
            put("family_name", familyName.trim())
            put("display_name", displayName.trim())
        }.toString()
        val res = callRpc("create_family", body)
        if (!res.isOk) { lastError = parseAuthError(res); return false }
        return _refreshFamily()
    }

    suspend fun joinFamily(code: String): Boolean {
        val res = callRpc("join_family", buildJsonObject { put("code", code.trim()) }.toString())
        if (!res.isOk) { lastError = parseAuthError(res); return false }
        return _refreshFamily()
    }

    suspend fun signOut() {
        val token = token() ?: return
        Http.post("$base/logout", baseHeaders(Config.supabaseKey, token))
        clear()
        _session.value = null
        family = null
        _state.value = AuthFlowState.SIGNED_OUT
    }

    // ---------- private ----------

    /** RPC 需要 Authorization Bearer(登录态); 与 lastError 联动 */
    private suspend fun callRpc(name: String, body: String): HttpResult {
        val token = token() ?: return HttpResult(401, "未登录")
        return Http.post(
            "$rest/rpc/$name",
            baseHeaders(Config.supabaseKey, token, mapOf("Content-Type" to "application/json")),
            body,
        )
    }

    private suspend fun _refreshFamily(): Boolean {
        val token = token()
        if (token == null) { _state.value = AuthFlowState.SIGNED_OUT; return false }
        val res = Http.post(
            "$rest/rpc/get_my_family",
            baseHeaders(Config.supabaseKey, token, mapOf("Content-Type" to "application/json")),
            "{}",
        )
        if (!res.isOk) { lastError = parseAuthError(res); _state.value = AuthFlowState.SIGNED_IN_NO_FAMILY; return false }
        val body = res.body.trim()
        if (body.isEmpty() || body == "null") {
            family = null
            _state.value = AuthFlowState.SIGNED_IN_NO_FAMILY
            return true
        }
        val obj = (res.jsonOrNull() as? JsonObject) ?: run { _state.value = AuthFlowState.SIGNED_IN_NO_FAMILY; return false }
        family = FamilyInfo(
            familyId = obj["family_id"]?.jsonPrimitive?.contentOrNull ?: "",
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "我的家庭",
            inviteCode = obj["invite_code"]?.jsonPrimitive?.contentOrNull ?: "",
            displayName = obj["display_name"]?.jsonPrimitive?.contentOrNull?.ifBlank { "家庭成员" } ?: "家庭成员",
        )
        lastError = null
        _state.value = AuthFlowState.READY
        return true
    }

    private fun parseSession(body: String): Session? {
        val obj = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
            ?: return null
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
        val refresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull ?: ""
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        return Session(access, refresh, System.currentTimeMillis() + expiresIn * 1000)
    }

    private suspend fun refreshToken(old: Session): Session? {
        val url = "$base/token?grant_type=refresh_token"
        val res = Http.post(
            url,
            baseHeaders(Config.supabaseKey),
            buildJsonObject { put("refresh_token", old.refreshToken) }.toString(),
        )
        if (!res.isOk) return null
        return parseSession(res.body)?.also { save(it) }
    }

    private fun save(s: Session) {
        val payload = buildJsonObject {
            put("access_token", s.accessToken)
            put("refresh_token", s.refreshToken)
            put("expires_at", s.expiresAtEpochMs)
        }.toString()
        prefs.edit().putString("session", payload).apply()
    }

    /** 登录/注册响应里提取用户昵称 (user_metadata.display_name 或邮箱前缀) */
    private fun applyDisplayName(body: String) {
        val obj = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null } ?: return
        val user = obj["user"] as? JsonObject ?: return
        val md = (user["user_metadata"] as? JsonObject)?.get("display_name")?.jsonPrimitive?.contentOrNull
        val name = md?.takeIf { it.isNotBlank() }
            ?: user["email"]?.jsonPrimitive?.contentOrNull?.substringBefore("@")
        if (!name.isNullOrBlank()) {
            displayName = name
            prefs.edit().putString("display_name", name).apply()
        }
    }

    private fun loadSavedSession(): Session? {
        val raw = prefs.getString("session", null) ?: return null
        return try {
            val obj = Json.parseToJsonElement(raw) as JsonObject
            Session(
                accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: return null,
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull ?: "",
                expiresAtEpochMs = obj["expires_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) { null }
    }

    private fun clear() {
        prefs.edit().remove("session").apply()
    }
}
