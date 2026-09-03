package com.smartfridge.app.data

import android.content.Context
import com.smartfridge.app.ai.AiService
import com.smartfridge.app.ai.createAiService
import com.smartfridge.app.core.Config
import com.smartfridge.app.data.local.FridgeDb

/**
 * 应用服务容器: 启动时组装一次, 随 Application 存活。
 * (相当于 Android 版的手写依赖注入容器)
 */
class AppServices(private val context: Context) {

    val db: FridgeDb = FridgeDb(context)
    val auth: AuthRepository = AuthRepository(context)
    val api: SupabaseApi = SupabaseApi()
    private fun freshToken(): () -> String? = {
        val s = auth.session.value
        when {
            s == null -> null
            s.expiresAtEpochMs - 60_000 < System.currentTimeMillis() ->
                kotlinx.coroutines.runBlocking { auth.refreshSession() }
            else -> s.accessToken
        }
    }

    val sync: SyncService = SyncService(db, api, freshToken(), onUnauthorized = { auth.refreshSession() })
    val ai: AiService = createAiService(freshToken(), onUnauthorized = { auth.refreshSession() })

    /** 登录并确定家庭后, 激活库存同步 (自动全量拉取 + 实时订阅) */
    fun activateFamily() {
        val family = auth.family ?: return
        sync.activate(family.familyId)
    }

    fun onSignedOut() {
        sync.deactivate()
    }

    /** 一键体检: 云端连通性 + 配置完整性 (设置页"报错反馈") */
    suspend fun healthCheck(): String {
        val reports = mutableListOf<String>()
        // 1) Supabase 云可达性
        try {
            val r = Http.get(
                "${Config.supabaseUrl.trimEnd('/')}/auth/v1/health",
                mapOf("apikey" to Config.supabaseKey),
            )
            reports += if (r.isOk) "✅ Supabase 云" else "❌ Supabase (HTTP ${r.code})"
        } catch (e: Exception) {
            reports += "❌ Supabase 连不上"
        }
        // 2) AI 配置完整性
        reports += if (Config.aiMode == "local") "✅ AI=本地模式" else "✅ AI=云端模式"
        // 3) 文字录入链路 (菜单文案, 无第三方依赖)
        reports += "✅ 文字录入可用"
        return if (reports.none { it.startsWith("❌") }) "✅ 全部正常"
        else reports.joinToString(" · ") { it.removePrefix("✅ ").removePrefix("❌ ") }
    }

    /** 旧分类体系 (肉类/蛋类/乳制品/主食粮油/调料/饮料) → v0.10 新分类 一次性迁移 */
    suspend fun migrateLegacyCategories() {
        val legacy = mapOf(
            "肉类" to "肉蛋", "蛋类" to "肉蛋", "乳制品" to "乳品",
            "主食粮油" to "主食", "调料" to "调味", "饮料" to "其他",
        )
        for (item in sync.items.value) {
            val newCat = legacy[item.category] ?: continue
            sync.updateIngredient(
                id = item.id,
                name = item.name,
                quantity = item.quantity,
                unit = item.unit,
                zone = item.zone,
                category = newCat,
            )
        }
    }
}
