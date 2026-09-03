package com.smartfridge.app

import com.smartfridge.app.core.Config
import com.smartfridge.app.data.AppServices
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * UI 热更新拉取器（S2）：
 * 启动后台静默:
 *   1) GET ui-release?cv=1&lv=<本地版本>   （契约校验+版本比对）
 *   2) 有新版本 → GET ui-page?ver=<v>     （用户 token 鉴权下载）
 *   3) 本地算 sha256 与清单比对（防篡改/防坏包）
 *   4) 通过 → 写 filesDir/web/index.html + version.json（当前版先备份 prev）
 * 全链路 Trace 打点；失败静默（S3 加载层有三级兜底）。
 */
object UiUpdater {

    private const val CONTRACT = 1
    private val base get() = Config.supabaseUrl.trimEnd('/')
    private val key get() = Config.supabaseKey

    private fun webDir(context: android.content.Context): File {
        val d = File(context.filesDir, "web")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun currentVer(context: android.content.Context): String {
        val v = File(webDir(context), "version.json")
        if (!v.exists()) return ""
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(v.readText()).jsonObject
            obj["ver"]?.jsonPrimitive?.contentOrNull ?: ""
        } catch (_: Exception) { "" }
    }

    /** 只检查：有新版本 → 写入 prefs(ui_ver) 并返回版本号；无 → null（页面提示"更新"按钮） */
    suspend fun checkForUpdate(context: android.content.Context, services: AppServices): String? {
        val token = services.auth.token()
        if (token.isNullOrBlank()) return null
        try {
            val r = com.smartfridge.app.data.Http.get(
                "$base/functions/v1/ui-release?cv=$CONTRACT&lv=${currentVer(context)}",
                mapOf("apikey" to key, "Authorization" to "Bearer $token"),
            )
            if (!r.isOk) return null
            val obj = r.jsonOrNull() as? kotlinx.serialization.json.JsonObject ?: return null
            val ver = obj["ver"]?.jsonPrimitive?.contentOrNull ?: return null
            if (ver == currentVer(context)) return null
            context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit()
                .putString("ui_ver", ver)
                .apply()
            Trace.log(context, "ui: update available $ver (lv=${currentVer(context)})")
            return ver
        } catch (_: Exception) {
            return null
        }
    }

    /** 下载并应用：下载→sha 验签→落盘（prev 备份）→回调通知 */
    suspend fun downloadNow(
        context: android.content.Context,
        services: AppServices,
        onDone: (String) -> Unit = {},
    ) {
        val token = services.auth.token()
        if (token.isNullOrBlank()) return
        try {
            val pending = context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
                .getString("ui_ver", "") ?: return
            Trace.log(context, "ui: download pending=$pending")
            val page = com.smartfridge.app.data.Http.get(
                "$base/functions/v1/ui-page?ver=$pending",
                mapOf("apikey" to key, "Authorization" to "Bearer $token"),
            )
            if (!page.isOk) { Trace.log(context, "ui: page fail HTTP ${page.code}"); return }
            val html = page.body
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(html.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            // 清单重查取 sha（以发布门为准）
            val r = com.smartfridge.app.data.Http.get(
                "$base/functions/v1/ui-release?cv=$CONTRACT&lv=$pending",
                mapOf("apikey" to key, "Authorization" to "Bearer $token"),
            )
            val want = (r.jsonOrNull() as? kotlinx.serialization.json.JsonObject)
                ?.get("sha256")?.jsonPrimitive?.contentOrNull
            if (want != null && want != digest) {
                Trace.log(context, "ui: sha mismatch, reject")
                return
            }
            val dir = webDir(context)
            val cur = File(dir, "index.html")
            if (cur.exists()) cur.copyTo(File(dir, "prev.html"), overwrite = true)
            val oldV = File(dir, "version.json")
            if (oldV.exists()) oldV.copyTo(File(dir, "prev-version.json"), overwrite = true)
            cur.writeText(html, Charsets.UTF_8)
            oldV.writeText(
                kotlinx.serialization.json.buildJsonObject {
                    put("ver", kotlinx.serialization.json.JsonPrimitive(pending))
                    put("contract", kotlinx.serialization.json.JsonPrimitive(CONTRACT))
                    put("sha256", kotlinx.serialization.json.JsonPrimitive(digest))
                }.toString(),
                Charsets.UTF_8,
            )
            Trace.log(context, "ui: downloaded ver=$pending sha=$digest len=${html.length}")
            onDone(pending)
        } catch (e: Exception) {
            Trace.log(context, "ui: download error ${e.message}")
        }
    }

    /** 主入口：无 token 或失败一律静默（加载层兜底） */
    suspend fun checkAndDownload(
        context: android.content.Context,
        services: AppServices,
        onUpdated: (String) -> Unit = {},
    ) {
        val token = services.auth.token()
        if (token.isNullOrBlank()) {
            Trace.log(context, "ui: no token, skip")
            return
        }
        try {
            Trace.log(context, "ui: check lv=${currentVer(context)}")
            val r = com.smartfridge.app.data.Http.get(
                "$base/functions/v1/ui-release?cv=$CONTRACT&lv=${currentVer(context)}",
                mapOf("apikey" to key, "Authorization" to "Bearer $token"),
            )
            if (!r.isOk) { Trace.log(context, "ui: check fail HTTP ${r.code}"); return }
            val obj = r.jsonOrNull() as? kotlinx.serialization.json.JsonObject ?: return
            val ok = (obj["ok"]?.jsonPrimitive?.contentOrNull) == "true"
            val ver = obj["ver"]?.jsonPrimitive?.contentOrNull ?: return
            if (!ok || obj["upToDate"]?.jsonPrimitive?.contentOrNull == "true") {
                if (ver == currentVer(context)) { Trace.log(context, "ui: up to date ($ver)"); return }
            }
            if (ver == currentVer(context)) { Trace.log(context, "ui: up to date ($ver)"); return }

            // 下载页面
            val page = com.smartfridge.app.data.Http.get(
                "$base/functions/v1/ui-page?ver=$ver",
                mapOf("apikey" to key, "Authorization" to "Bearer $token"),
            )
            if (!page.isOk) { Trace.log(context, "ui: page fail HTTP ${page.code}"); return }
            val html = page.body

            // sha256 校验
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(html.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val want = obj["sha256"]?.jsonPrimitive?.contentOrNull
            if (want != digest) {
                Trace.log(context, "ui: sha mismatch want=$want got=$digest, reject")
                return
            }

            // 写入（旧版备份为 prev）
            val dir = webDir(context)
            val cur = File(dir, "index.html")
            if (cur.exists()) cur.copyTo(File(dir, "prev.html"), overwrite = true)
            val oldV = File(dir, "version.json")
            if (oldV.exists()) oldV.copyTo(File(dir, "prev-version.json"), overwrite = true)
            cur.writeText(html, Charsets.UTF_8)
            oldV.writeText(
                kotlinx.serialization.json.buildJsonObject {
                    put("ver", kotlinx.serialization.json.JsonPrimitive(ver))
                    put("contract", kotlinx.serialization.json.JsonPrimitive(CONTRACT))
                    put("sha256", kotlinx.serialization.json.JsonPrimitive(digest))
                }.toString(),
                Charsets.UTF_8,
            )
            Trace.log(context, "ui: downloaded ver=$ver sha=$digest len=${html.length}")
            // 通知 UI 状态：有新版本，等待重启生效
            context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE).edit()
                .putString("ui_ver", ver)
                .putString("ui_state", "new")
                .apply()
            onUpdated(ver)
        } catch (e: Exception) {
            Trace.log(context, "ui: error ${e.message}")
        }
    }
}
