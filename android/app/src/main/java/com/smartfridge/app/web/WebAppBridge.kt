package com.smartfridge.app.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.delay
import com.smartfridge.app.UiUpdater
import androidx.core.content.ContextCompat
import com.smartfridge.app.ai.localFallbackRecipes
import com.smartfridge.app.data.AppServices
import com.smartfridge.app.core.CreamSkin
import com.smartfridge.app.core.IconSet
import com.smartfridge.app.core.SkinManager
import com.smartfridge.app.Trace
import com.smartfridge.app.domain.ExpiringItem
import com.smartfridge.app.domain.Ingredient
import com.smartfridge.app.domain.IngredientDraft
import com.smartfridge.app.domain.RecipeMode
import com.smartfridge.app.domain.Recipe
import com.smartfridge.app.domain.RecipePlan
import com.smartfridge.app.domain.guessCategoryFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * WebView 数据桥 (对接命令 §3 / WebView集成指南 §3):
 *  页面 → Kotlin: AndroidBridge.onEvent(type, payloadJsonString) — 全事件表
 *  Kotlin 只负责: 数据落库/云端 LWW、安全区、剪贴板、设置持久化、setData 回灌。
 *  页面是"纯视图 + 交互", 永远以注入的数据为准 (D-22)。
 */
class WebAppBridge(
    private val context: Context,
    private val services: AppServices,
    private val webView: WebView,
    private val onThemeChanged: (dark: Boolean) -> Unit = {},
    private val onReminderConfig: (enabled: Boolean, hours: Int) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = kotlinx.coroutines.MainScope()

    // id 双向映射: UUID ↔ 页面数字 id (页面 edit 事件回传数字)
    private val idToUuid = LinkedHashMap<Int, String>()
    private val uuidToId = LinkedHashMap<String, Int>()

    /** 体检回路 latch: ping 发出 → 页面 pong 回来才 complete */
    @Volatile
    private var pendingPong: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    // 菜谱缓存: 首次/同步成功后生成, setData 回灌
    @Volatile private var recipesPlan: RecipePlan? = null
    // 烘焙好的菜谱 JSON (含关东煮插位/徽章): 计划生成时定死一次, 回灌只重放 —— 防止每推重掷导致页面反复闪动
    @Volatile private var recipesJson: kotlinx.serialization.json.JsonObject? = null

    // 菜谱生成模式: 页面「临期优先」开关切换(事件 recipe-mode); 持久化, 下次启动沿用
    @Volatile private var recipeMode: com.smartfridge.app.domain.RecipeMode = run {
        val saved = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            .getString("recipe_mode", "normal")
        com.smartfridge.app.domain.RecipeMode.entries.firstOrNull { it.db == saved }
            ?: com.smartfridge.app.domain.RecipeMode.NORMAL
    }

    // =====================================================================
    // 页面 → Kotlin
    // =====================================================================

    @JavascriptInterface
    fun onEvent(type: String, payload: String) {
        android.util.Log.i("FridgeBridge", "event=$type payload=$payload") // W-12 日志
        Trace.log(context, "event: $type payload=${payload.take(120)}")
        val p = try { Json.parseToJsonElement(payload.ifBlank { "{}" }).jsonObject } catch (_: Exception) { JsonObject(emptyMap()) }
        scope.launch {
            try {
                when (type) {
                    "edit-save" -> handleEditSave(p)
                    "edit-delete" -> handleEditDelete(p)
                    "deduct" -> handleDeduct(p)
                    "add" -> handleAdd(p)
                    "entry-add" -> Unit // 埋点: 录入浮窗打开 (可选)
                    "data" -> Unit // 预留
                    "refresh" -> handleSync() // 菜谱刷新钮: 云端同步 + 菜谱重生成 + 回灌
                    "sync" -> handleSync()
                    "copy" -> handleCopy(p)
                    // theme 涉及状态栏(UI 线程) → 主线程处理
                    "theme" -> main.launch { handleTheme(p) }
                    "icon-set" -> handleIconSet(p)
                    "skin" -> handleSkin(p)
                    "zone" -> handleZone(p)
                    // DeepSeek 智能识别: 单名猜料 / 整段解析
                    "ai-guess" -> handleAiGuess(p)
                    "ai-parse" -> handleAiParse(p)
                    "pong" -> handlePong(p)
                    "health-check" -> main.launch { handleHealthCheck() }
                    "reminder-settings" -> main.launch { handleReminderSettings(p) }
                    "recipe-mode" -> main.launch { handleRecipeMode(p) }
                    "check-update" -> main.launch { handleCheckUpdate() }
                    "refresh-pool" -> main.launch { handleRefreshPool() }
                    "edit-fresh" -> main.launch { handleEditFresh(p) }
                }
            } catch (e: Exception) {
                android.util.Log.e("FridgeBridge", "handle $type failed", e)
            }
        }
    }

    // ---------------- 事件处理 ----------------

    private suspend fun handleEditSave(p: JsonObject) {
        val uuid = uuidOf(p["id"]?.jsonPrimitive?.intOrNull) ?: return
        val cur = services.sync.items.value.firstOrNull { it.id == uuid } ?: return
        val qty = p["qty"]?.jsonPrimitive?.doubleOrNull ?: cur.quantity
        val zone = WebData.zoneFromKey(p["zone"]?.jsonPrimitive?.contentOrNull ?: "")
        val unit = p["unit"]?.jsonPrimitive?.contentOrNull ?: cur.unit
        services.sync.updateIngredient(id = uuid, name = cur.name, quantity = qty, unit = unit, zone = zone, category = cur.category)
        pushInv()
    }

    private suspend fun handleEditDelete(p: JsonObject) {
        val uuid = uuidOf(p["id"]?.jsonPrimitive?.intOrNull) ?: return
        val remove = p["remove"]?.jsonPrimitive?.boolean ?: false
        if (remove) services.sync.remove(uuid) else services.sync.consume(uuid, 1.0)
        pushInv()
    }

    private suspend fun handleDeduct(p: JsonObject) {
        val items = p["items"] as? kotlinx.serialization.json.JsonArray ?: return
        for (el in items) {
            val o = el.jsonObject
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val qty = o["qty"]?.jsonPrimitive?.doubleOrNull ?: continue
            val unit = o["unit"]?.jsonPrimitive?.contentOrNull ?: ""
            val target = services.sync.items.value.firstOrNull { it.name == name || it.name.contains(name) || name.contains(it.name) }
                ?: continue
            val real = toStockQty(qty, unit, target.unit)
            if (real > 0) services.sync.consume(target.id, real)
        }
        pushInv()
    }

    private suspend fun handleAdd(p: JsonObject) {
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: return
        val qty = p["qty"]?.jsonPrimitive?.doubleOrNull ?: 1.0
        val unit = p["unit"]?.jsonPrimitive?.contentOrNull ?: "个"
        val zone = WebData.zoneFromKey(p["zone"]?.jsonPrimitive?.contentOrNull ?: "chill")
        val tag = p["tag"]?.jsonPrimitive?.contentOrNull
        // 权威保质期表优先(已知食材直接覆盖页面/AI默认值); 未收录回退 AI 识别值, 再退 3
        val shelf = p["shelfLifeDays"]?.jsonPrimitive?.intOrNull
        val days = com.smartfridge.app.domain.PreservationTable.daysForCalib(name, zone.db)
            ?: if (shelf != null && shelf in 1..3650) shelf else 3
        val created = services.sync.addDrafts(listOf(IngredientDraft(name, qty, unit, zone, days)), "小厨")
        // 页面自选分类 (tag) 覆盖规则分类 (A 套图标由页面负责, Kotlin 只存数据)
        if (tag != null && tag.isNotBlank()) {
            created.firstOrNull { it.name == name }?.let { it2 ->
                services.sync.updateIngredient(id = it2.id, name = it2.name, quantity = it2.quantity, unit = it2.unit, zone = it2.zone, category = tag)
            }
        }
        pushInv()
    }

    private fun handlePong(p: JsonObject) {
        pendingPong?.complete(true)
        pendingPong = null
    }

    /** 刷新保质期：购买时间重置为现在，shelf 按保鲜表重算 */
    private suspend fun handleEditFresh(p: JsonObject) {
        val uuid = uuidOf(p["id"]?.jsonPrimitive?.intOrNull) ?: return
        val row = services.sync.items.value.firstOrNull { it.id == uuid } ?: return
        Trace.log(context, "edit-fresh: ${row.name}")
        services.sync.refreshFreshness(uuid)
        pushInv()
    }
    /** 临期优先开关(2026-09-05 新规): 只筛选展示 —— 不作废池、不重生成; 直接按当前模式从池里切一批(临期菜带标靠前) */
    private fun handleRecipeMode(p: JsonObject) {
        val mode = if (p["mode"]?.jsonPrimitive?.contentOrNull == "expiring")
            com.smartfridge.app.domain.RecipeMode.EXPIRING
        else com.smartfridge.app.domain.RecipeMode.NORMAL
        recipeMode = mode
        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE).edit()
            .putString("recipe_mode", mode.db).apply()
        Trace.log(context, "recipe-mode: ${mode.db} (pool kept=${recipePool.size})")
        refreshRecipesIfNeeded(force = true)   /* 池在 → 立即筛选一批; 池不在 → 生成(带至多10道临期约束) */
    }

    /** 手动检查更新(设置页「检查更新」按钮): 查服务器 → 有新版本自动拉取并刷新; 无则提示已最新 */
    private suspend fun handleCheckUpdate() {
        val cur = try {
            val jf = java.io.File(context.filesDir, "web/version.json")
            if (jf.exists()) kotlinx.serialization.json.Json.parseToJsonElement(jf.readText()).jsonObject["ver"]?.jsonPrimitive?.contentOrNull ?: "" else ""
        } catch (_: Exception) { "" }
        val v = UiUpdater.checkForUpdate(context, services)
        if (v != null) {
            Trace.log(context, "check-update: new $v (cur=$cur)")
            eval("window.setUiUpdate && window.setUiUpdate({\"cur\":\"$cur\",\"target\":\"$v\",\"state\":\"new\"})")
            UiUpdater.downloadNow(
                context, services,
                onDone = { ver -> main.launch {
                    android.widget.Toast.makeText(context, "已更新至 v$ver，正在刷新…", android.widget.Toast.LENGTH_SHORT).show()
                    eval("location.reload()")
                } },
                onFail = { msg -> main.launch { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() } },
            )
        } else {
            Trace.log(context, "check-update: up to date ($cur)")
            eval("window.setUiUpdate && window.setUiUpdate({\"cur\":\"$cur\",\"target\":\"\",\"state\":\"ok\"})")
            main.launch { android.widget.Toast.makeText(context, "已是最新版本", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    /** 长按刷新按钮: 主动重新生成池(更新完提示, 下一次短按即用新池) */
    private suspend fun handleRefreshPool() {
        main.launch { android.widget.Toast.makeText(context, "正在更新菜谱池…", android.widget.Toast.LENGTH_SHORT).show() }
        regeneratePool()
        main.launch { android.widget.Toast.makeText(context, "菜谱池已更新", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun handleReminderSettings(p: JsonObject) {
        val enabled = p["enabled"]?.jsonPrimitive?.boolean ?: true
        val hours = p["hours"]?.jsonPrimitive?.intOrNull ?: 24
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean("reminder_enabled", enabled)
            .putInt("reminder_hours", hours.coerceIn(6, 72))
            .apply()
        Trace.log(context, "reminder: enabled=$enabled hours=$hours")
        onReminderConfig(enabled, hours)
    }

    private suspend fun handleHealthCheck() {
        val lines = mutableListOf<String>()
        lines += runCatching {
            val r = com.smartfridge.app.data.Http.get(
                "${com.smartfridge.app.core.Config.supabaseUrl.trimEnd('/')}/auth/v1/health",
                mapOf("apikey" to com.smartfridge.app.core.Config.supabaseKey),
            )
            if (r.isOk) "✅ Supabase 云 · 可达" else "❌ Supabase 云 · HTTP ${r.code}（检查网络或项目配置）"
        }.getOrElse { e -> "❌ Supabase 云 · 连不上（${e.message}）" }
        lines += if (services.auth.token() == null)
            "❌ 登录 · 未登录（重新进入应用触发自动登录；或查 local.properties 自动账号）"
        else "✅ 登录 · 会话有效"

        val pong = awaitPagePong()
        lines += if (pong) "✅ 页面桥回路 · ping→pong 正常"
        else "❌ 页面桥回路 · 页面未回应（桥/页面 JS 异常：检查 trace.log 的 event 记录与页面报错）"

        lines += runCatching {
            services.ai.parseNaturalLanguage("体检测试")
            "✅ DeepSeek · 识别就绪（ai-parse 正常）"
        }.getOrElse { e -> "❌ DeepSeek · ${e.message}" }

        // 临期提醒功能已下线(2026-09-04): 体检不再触发检查/通知
        val report = lines.joinToString("\n")
        Trace.log(context, "health: $report")
        eval("window.showHealthReport(${kotlinx.serialization.json.JsonPrimitive(report)})")
        eval("window.__healthDone && window.__healthDone()")
    }

    /** 页面回路测试: ping → 等 2.5s → pong 收到即 true */
    private suspend fun awaitPagePong(): Boolean {
        val latch = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingPong = latch
        main.launch { eval("window.__ping && window.__ping(1)") }
        return kotlinx.coroutines.withTimeoutOrNull(2500) { latch.await() } ?: false
    }

    private suspend fun handleSync() {
        try {
            services.sync.syncNow()
            refreshRecipesIfNeeded(force = true)
            pushAll()
        } catch (e: Exception) {
            eval("toast('同步失败，点击重试')")
        }
    }

    private fun handleCopy(p: JsonObject) {
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("小厨", "「$name」"))
    }

    private fun handleTheme(p: JsonObject) {
        val dark = p["dark"]?.jsonPrimitive?.boolean ?: false
        SkinManager.setDarkMode(dark)
        onThemeChanged(dark)
        eval("window.setAppTheme($dark)")
    }

    private fun handleIconSet(p: JsonObject) {
        val id = p["id"]?.jsonPrimitive?.contentOrNull ?: "a"
        SkinManager.setIconSet(
            when (id) {
                "a" -> IconSet.EMOJI
                "b" -> IconSet.LINE
                else -> IconSet.STICKER
            },
        )
    }

    private fun handleSkin(p: JsonObject) {
        val id = p["id"]?.jsonPrimitive?.contentOrNull ?: "glass"
        SkinManager.setSkinPref(if (id == "classic") CreamSkin.CLASSIC else CreamSkin.GLASS)
    }

    private fun handleZone(p: JsonObject) {
        val zone = p["zone"]?.jsonPrimitive?.contentOrNull
        context.getSharedPreferences("inventory_prefs", Context.MODE_PRIVATE)
            .edit().putString("last_zone", zone).apply()
    }

    // ---------- DeepSeek 智能识别 ----------

    /** 单名猜料: 分类 + 默认单位 → 推页面 onAiGuess */
    private suspend fun handleAiGuess(p: JsonObject) {
        val name = p["v"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isBlank()) return
        val zoneKey = p["zone"]?.jsonPrimitive?.contentOrNull ?: "chill"
        val zone = WebData.zoneFromKey(zoneKey)
        val cat = com.smartfridge.app.domain.guessCategoryFor(name, zone)
        val unit = when (cat) {
            "蔬菜", "水果" -> "个"
            "肉蛋", "海鲜" -> "克"
            "乳品" -> "盒"
            "主食" -> "袋"
            "调味" -> "瓶"
            "速冻" -> "袋"
            else -> "个"
        }
        val res = buildJsonObject {
            put("name", name)
            put("cat", cat)
            put("unit", unit)
        }
        main.launch { eval("window.onAiGuess(${jsSafe(res.toString())})") }
    }

    /** 整段智能解析: AI 识别多食材 → 推页面 onAiParse (草稿列表) */
    private suspend fun handleAiParse(p: JsonObject) {
        val text = p["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (text.isBlank()) return
        val res = try {
            val drafts = services.ai.parseNaturalLanguage(text)
            if (drafts.isEmpty()) buildJsonObject { put("ok", false); put("error", "没有识别出食材, 换个说法试试"); put("items", kotlinx.serialization.json.JsonArray(emptyList())) }
            else buildJsonObject {
                put("ok", true)
                put("error", "")
                put("items", kotlinx.serialization.json.buildJsonArray {
                    drafts.forEach { d ->
                        add(buildJsonObject {
                            put("name", d.name)
                            put("qty", d.quantity)
                            put("unit", d.unit)
                            put("zone", WebData.zoneKey(d.zone))
                            put("cat", com.smartfridge.app.domain.guessCategoryFor(d.name, d.zone))
                            /* AI 保质天 → 权威表(xlsx)覆盖(有则覆盖, 无则保留 AI 值) */
                            put("shelfLifeDays", com.smartfridge.app.domain.PreservationTable.daysForCalib(d.name, d.zone.db) ?: d.shelfLifeDays)
                        })
                    }
                })
            }
        } catch (e: Exception) {
            buildJsonObject { put("ok", false); put("error", "AI 解析失败: ${e.message}"); put("items", kotlinx.serialization.json.JsonArray(emptyList())) }
        }
        main.launch { eval("window.onAiParse(${jsSafe(res.toString())})") }
    }

    // =====================================================================
    // Kotlin → 页面 (setData 回灌)
    // =====================================================================

    /** 页面就绪/登录就绪后全量注入 (库存 + 菜谱 + iconSet) */
    fun pushAll() {
        Trace.log(context, "pushAll entered")
        refreshRecipesIfNeeded(force = false)
        main.launch { pushInvNow() }
    }

    /** 仅库存 (本地变更后快速回灌, 并防抖调度池刷新: 库存变更→池预刷新→以后开机直接用缓存) */
    fun pushInv() {
        main.launch { pushInvNow() }
        scheduleRefresh(6000)
    }

    // ---------- 食谱池: 磁盘缓存 + 签名判脏 + 防抖刷新 ----------
    @Volatile private var poolRefreshJob: kotlinx.coroutines.Job? = null
    @Volatile private var lastPoolAt = 0L

    private fun itemsSignature(): String =
        services.sync.items.value.joinToString("|") { "${it.name}:${it.quantity}:${it.updatedAt.toEpochMilli()}" }.hashCode().toString()

    private fun poolFile() = java.io.File(context.filesDir, "recipe_pool.json")

    private fun Recipe.toPoolJson(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("title", title)
        put("minutes", kotlinx.serialization.json.JsonPrimitive(minutes))
        put("uses", kotlinx.serialization.json.JsonArray(uses.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("ingredients", kotlinx.serialization.json.JsonArray(ingredients.map { l ->
            kotlinx.serialization.json.buildJsonObject { put("name", l.name); put("amount", l.amount) }
        }))
        put("steps", kotlinx.serialization.json.JsonArray(steps.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("tips", kotlinx.serialization.json.JsonPrimitive(tips))
    }

    private fun persistPool(pool: List<Recipe>) {
        try {
            poolFile().writeText(kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("items", kotlinx.serialization.json.JsonArray(pool.map { it.toPoolJson() }))
                    put("sig", kotlinx.serialization.json.JsonPrimitive(itemsSignature()))
                },
            ), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    /** 读磁盘池; 返回 true=签名过期(需后台刷新), false=无缓存/与你一致 */
    private fun loadPool(): Boolean {
        try {
            val f = poolFile()
            if (!f.exists()) return false
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(f.readText()).jsonObject
            val pool = (obj["items"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? kotlinx.serialization.json.JsonObject)?.let { o -> Recipe.fromJson(o) }
            } ?: emptyList()
            if (pool.isEmpty()) return false
            recipePool = pool
            val cachedSig = obj["sig"]?.jsonPrimitive?.contentOrNull ?: ""
            Trace.log(context, "pool: LOADED ${pool.size} cachedSig=${cachedSig} curSig=${itemsSignature()}")
            return cachedSig != itemsSignature()
        } catch (_: Exception) { return false }
    }

    /** 防抖调度的池刷新(库存变更后6s / 缓存过期后3s), 不打扰当前显示; 刚生成过(8s内)则跳过, 避免启动重复生成 */
    private fun scheduleRefresh(delayMs: Long) {
        poolRefreshJob?.cancel()
        poolRefreshJob = scope.launch {
            delay(delayMs)
            if (System.currentTimeMillis() - lastPoolAt < 8000) {
                Trace.log(context, "pool: skip (fresh ${System.currentTimeMillis() - lastPoolAt}ms)")
                return@launch
            }
            if (recipePool.isNotEmpty() && releasedTitles.isNotEmpty()) {
                /* 静默预刷新: 直接换池, 不推送(用户下次短按自然用新池) */
                regeneratePool()
                Trace.log(context, "pool: PRE-REFRESHED ${recipePool.size}")
            } else {
                regenerateAndRelease()
            }
        }
    }

    private fun pushInvNow() {
        val items = services.sync.items.value
        rebuildIdMap(items)
        val root = buildJsonObject {
            put("inv", WebData.invArray(items) { uuidToId[it] ?: 0 })
            put("iconSet", WebData.iconSetId())
            put("recipes", recipesJson ?: kotlinx.serialization.json.buildJsonObject { })
        }
        val json = root.toString()
        Trace.log(context, "pushInvNow json len=${json.length} items=${items.size}")
        android.util.Log.d("FridgeInject", "JSON=${json.substring(0, minOf(300, json.length))}")
        eval("window.setData(" + jsSafe(json) + ")")
    }

    /** 菜谱池(定稿版): 一次生成 30 道入池; 每次刷新从 30 道里随机乱序出 5 道(循环, 不抽走, 不再调用 AI)。
     *  仅当: 空池(首启/模式切换后) → 调 AI 生成 30; 避重用已发过的标题 */
    private val BATCH = 5
    @Volatile private var recipePool: List<Recipe> = emptyList()
    @Volatile private var releasedTitles: List<String> = emptyList()

    private fun refreshRecipesIfNeeded(force: Boolean) {
        if (recipesPlan != null && !force) return
        val ready = services.sync.items.value
        if (ready.isEmpty()) {
            Trace.log(context, "recipes: items empty, postpone AI")
            return
        }
        // 开机: 池空 → 先读磁盘缓存(秒开); 签名字符串不符 → 后台判脏重生成(不阻塞显示)
        if (recipePool.isEmpty()) {
            val stale = loadPool()
            if (stale) scheduleRefresh(3000)   /* 磁盘池与当前库存签名不一致 → 后台更新 */
        }
        // 池非空(内存或缓存) → 直接从池出(零等待)
        if (recipePool.isNotEmpty()) {
            sliceFromPool()
            return
        }
        scope.launch { regenerateAndRelease() }
    }

    /** 生成30道新池(至多10道临期)并落盘; 然后首发一批 */
    private suspend fun regenerateAndRelease() {
        regeneratePool()
        val batch = releaseBatch()
        val plan = RecipePlan(true, false, batch, null, null)
        recipesPlan = plan
        releasedTitles += batch.map { it.title }
        recipesJson = withOden(badgeRecipes(plan))
        main.launch { pushInvNow() }
    }

    /** 核心: 调 AI 生成 → 入池(√30) → 持久化 */
    private suspend fun regeneratePool() {
        val plan = try {
            val items = services.sync.items.value
            val all = items.map { ExpiringItem.fromIngredient(it) }
            // 生成时检测临期: 始终把黄/红食材作为"临期列表"传给服务端(其按 至多10道临期约束 生成)
            val expiring = items.filter { it.freshnessStatus().isAlert }.map { ExpiringItem.fromIngredient(it) }
            // 池生成固定 NORMAL 请求(约30道): 开关只影响"筛选展示", 不改变生成
            val mode = com.smartfridge.app.domain.RecipeMode.NORMAL
            val p = services.ai.recommendRecipes(
                expiring, all.take(20), mode,
                avoid = releasedTitles.takeLast(20),   /* 避重: 已发放过的菜 */
            )
            Trace.log(context, "recipes: AI ok=${p.ok} count=${p.recipes.size} err=${p.error ?: "-"}")
            p
        } catch (e: Exception) {
            Trace.log(context, "recipes: AI FAILED ${e.message} -> localFallback")
            localFallbackRecipes(emptyList())
        }
        // 兜底过滤: 丢弃"uses 与现有食材零交集"的菜(主料全需补充, 如糖醋蒜苔/酸汤虾滑)
        val invNames = services.sync.items.value.map { it.name }.toSet()
        val clean = plan.recipes.filter { r ->
            r.uses.any { u -> invNames.any { e -> u.contains(e) || e.contains(u) } }
        }
        if (clean.size < plan.recipes.size) Trace.log(context, "recipes: FILTERED ${plan.recipes.size - clean.size} dishes without real ingredients")
        recipePool = clean.distinctBy { it.title }.take(30)
        lastPoolAt = System.currentTimeMillis()
        persistPool(recipePool)
        Trace.log(context, "recipes: GENERATE pool=${recipePool.size}")
    }

    /** 当前临期食材名集合(isAlert=非新鲜) */
    private fun alertNames(): Set<String> =
        services.sync.items.value.filter { it.freshnessStatus().isAlert }.map { it.name }.toSet()

    private fun expiringDish(r: Recipe, alert: Set<String>): Boolean =
        r.uses.any { u -> alert.any { e -> u.contains(e) || e.contains(u) } }

    /** 出一批 5 道: 临期模式下「含临期食材的菜」排前(≤3道)其余随机; 正常模式纯随机乱序 */
    private fun releaseBatch(): List<Recipe> {
        val alert = alertNames()
        val exp = recipePool.filter { expiringDish(it, alert) }
        val other = recipePool.filterNot { expiringDish(it, alert) }
        return if (recipeMode == com.smartfridge.app.domain.RecipeMode.EXPIRING && exp.isNotEmpty()) {
            val n = minOf(3, exp.size)
            (exp.shuffled().take(n) + other.shuffled().take(BATCH - n)).take(BATCH)
        } else {
            recipePool.shuffled().take(BATCH)
        }
    }

    /** 从 30 道池里随机乱序出 5(不抽走, 循环模式): 无网络, 秒级; 每批独立烘焙(关东煮/徽章) */
    private fun sliceFromPool() {
        val batch = releaseBatch()
        val plan = RecipePlan(true, false, batch, null, null)
        recipesPlan = plan
        releasedTitles += batch.map { it.title }
        recipesJson = withOden(badgeRecipes(plan))
        Trace.log(context, "recipes: SLICE from pool(${recipePool.size}) mode=${recipeMode.db}")
        main.launch { pushInvNow() }
    }

    /** 常驻菜：关东煮（用户对象“没想法就吃这个”——AI 刷新不替换，永远置顶） */
    private val ODEN: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("name", "关东煮")
        put("time", "15 分钟")
        put("emoji", "🍢")
        put("badge", 0)
        put("tags", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("蔬菜"))
            add(kotlinx.serialization.json.JsonPrimitive("鱼丸"))
            add(kotlinx.serialization.json.JsonPrimitive("蘑菇"))
            add(kotlinx.serialization.json.JsonPrimitive("肉"))
        })
        put("steps", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("锅里加水烧开，放入浓汤宝/昆布+干贝煮出汤底。"))
            add(kotlinx.serialization.json.JsonPrimitive("先下鱼丸、肉丸、胡萝卜，中火煮 5 分钟。"))
            add(kotlinx.serialization.json.JsonPrimitive("再下白萝卜、玉米、香菇、魔芋，煮 5 分钟。"))
            add(kotlinx.serialization.json.JsonPrimitive("最后下白菜/油麦菜和金针菇煮 2 分钟，调盐、关火，蘸料同吃。"))
        })
        put("ing", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("name", "鱼丸"); put("unit", "克"); put("base", 200)
            })
            add(kotlinx.serialization.json.buildJsonObject {
                put("name", "白萝卜"); put("unit", "节"); put("base", 1)
            })
            add(kotlinx.serialization.json.buildJsonObject {
                put("name", "白菜"); put("unit", "颗"); put("base", 1)
            })
            add(kotlinx.serialization.json.buildJsonObject {
                put("name", "玉米"); put("unit", "段"); put("base", 2)
            })
            add(kotlinx.serialization.json.buildJsonObject {
                put("name", "香菇"); put("unit", "朵"); put("base", 4)
            })
            add(kotlinx.serialization.json.buildJsonObject {
                put("name", "金针菇"); put("unit", "把"); put("base", 1)
            })
        })
    }

    /** 关东煮=普通菜，只是出现概率更高(60%)：AI 只保留 4 道 + 关东煮 = 恒 5 道；位置随机 */
    private val ODEN_CHANCE = 60

    /** 把关东煮作为第 5 道菜插入：AI 结果取前 4 道 + 关东煮（抽中时整批恰 5 道，不再 6 道） */
    private fun withOden(recipesObj: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        if (recipesObj.isEmpty()) return recipesObj
        if ((0 until 100).random() >= ODEN_CHANCE) return recipesObj
        val out = kotlinx.serialization.json.buildJsonObject {
            val keys = recipesObj.keys.toList().take(4)   // AI 只拿 4 道, 关东煮占第 5 席
            // 带标(1)菜必须在最前; 关东煮在"其余剩余位置"随机(不带标时全随机)
            val badged = keys.count { k ->
                ((recipesObj[k] as? kotlinx.serialization.json.JsonObject)?.get("badge")
                    ?.jsonPrimitive?.contentOrNull ?: "0") == "1"
            }
            val pos = badged + (0..keys.size - badged).random()
            var idx = 0
            for (k in keys) {
                if (idx == pos) { put((idx + 1).toString(), ODEN); idx++ }
                put((idx + 1).toString(), recipesObj[k]!!)
                idx++
            }
            if (idx == keys.size) put((keys.size + 1).toString(), ODEN)
        }
        return out
    }

    /** 徽章 per-recipe: 每道菜 uses 与该菜临期食材交集 → 各自 0/1（页面开关开启时才显示） */
    private fun badgeRecipes(plan: RecipePlan): kotlinx.serialization.json.JsonObject {
        val expiringNames = services.sync.items.value
            .filter { it.freshnessStatus().isAlert }
            .map { it.name }
            .toSet()
        val badges = plan.recipes.map { r ->
            if (r.uses.any { u -> expiringNames.any { e -> u.contains(e) || e.contains(u) } }) 1 else 0
        }
        return WebData.recipesObject(
            recipes = plan.recipes,
            withBadge = badges.any { it == 1 },
            badges = badges,
        )
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private fun rebuildIdMap(items: List<Ingredient>) {
        // 保持稳定: 先清空重建; 顺序与数据库一致 → 数字稳定
        idToUuid.clear()
        uuidToId.clear()
        items.forEachIndexed { i, it ->
            val num = i + 1
            idToUuid[num] = it.id
            uuidToId[it.id] = num
        }
    }

    private fun uuidOf(id: Int?): String? = id?.let { idToUuid[it] }

    private fun eval(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }

    /** JSON 放 JS 字符串字面量: 转义 U+2028/2029 (D-34) */
    private fun jsSafe(json: String): String =
        json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun toStockQty(value: Double, fromUnit: String, stockUnit: String): Double {
        val f = WebData.unitFactor(fromUnit)
        val s = WebData.unitFactor(stockUnit)
        return when {
            f != null && s != null -> value * f / s
            f == null && s == null -> value
            s == null -> 1.0
            else -> value
        }
    }
}
