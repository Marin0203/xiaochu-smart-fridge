package com.smartfridge.app.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
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
        // 保质期优先用 AI 识别的 shelfLifeDays(1..3650), 缺省回退保鲜表
        val shelf = p["shelfLifeDays"]?.jsonPrimitive?.intOrNull
        val days = if (shelf != null && shelf in 1..3650) shelf
            else com.smartfridge.app.domain.FreshnessTable.daysFor(name, zone.db) ?: 3
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
                            put("shelfLifeDays", d.shelfLifeDays)   /* AI 商品保质天 → 页面透传 → add 事件 */
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

    /** 仅库存 (本地变更后快速回灌) */
    fun pushInv() {
        main.launch { pushInvNow() }
    }

    private fun pushInvNow() {
        val items = services.sync.items.value
        rebuildIdMap(items)
        val root = buildJsonObject {
            put("inv", WebData.invArray(items) { uuidToId[it] ?: 0 })
            put("iconSet", WebData.iconSetId())
            put("recipes", withOden(
                recipesPlan?.let { badgeRecipes(it) } ?: kotlinx.serialization.json.buildJsonObject { }
            ))
        }
        val json = root.toString()
        Trace.log(context, "pushInvNow json len=${json.length} items=${items.size}")
        android.util.Log.d("FridgeInject", "JSON=${json.substring(0, minOf(300, json.length))}")
        eval("window.setData(" + jsSafe(json) + ")")
    }

    /** 菜谱生成 (AI 优先, 失败本地兜底 5 道); 完成后回灌
     *  数据未就绪时不抢跑 (空库存发 ai-recipe 会 400): 等 READY 后再次 pushAll 自然触发 */
    private fun refreshRecipesIfNeeded(force: Boolean) {
        if (recipesPlan != null && !force) return
        val ready = services.sync.items.value
        if (ready.isEmpty()) {
            Trace.log(context, "recipes: items empty, postpone AI")
            return
        }
        scope.launch {
            val plan = try {
                val all = services.sync.items.value.map { ExpiringItem.fromIngredient(it) }
                val p = services.ai.recommendRecipes(
                    emptyList(), all.take(20), RecipeMode.NORMAL,
                    avoid = recipesPlan?.recipes?.map { it.title } ?: emptyList(),
                )
                Trace.log(context, "recipes: AI ok=${p.ok} count=${p.recipes.size} err=${p.error ?: "-"}")
                p
            } catch (e: Exception) {
                Trace.log(context, "recipes: AI FAILED ${e.message} -> localFallback")
                localFallbackRecipes(emptyList())
            }
            recipesPlan = plan
            main.launch { pushInvNow() }
        }
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

    /** 关东煮=普通菜，只提高出现概率(80%)：AI 结果返回后才插入(不再占位"看门")；位置随机 1..n+1 */
    private val ODEN_CHANCE = 80

    /** 把关东煮插入菜谱（概率 ODEN_CHANCE%；AI 未回/空结果时不插入，交给页面加载态） */
    private fun withOden(recipesObj: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        if (recipesObj.isEmpty()) return recipesObj
        if ((0 until 100).random() >= ODEN_CHANCE) return recipesObj
        val out = kotlinx.serialization.json.buildJsonObject {
            val keys = recipesObj.keys.toList()
            val pos = (0..keys.size).random()
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
