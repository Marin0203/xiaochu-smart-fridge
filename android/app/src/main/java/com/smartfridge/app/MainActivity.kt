package com.smartfridge.app

import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.getcapacitor.BridgeActivity
import com.smartfridge.app.core.Config
import com.smartfridge.app.core.SkinManager
import com.smartfridge.app.data.AppServices
import com.smartfridge.app.data.AuthFlowState
import com.smartfridge.app.web.FridgeNative
import com.smartfridge.app.web.WebAppBridge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Capacitor 宿主（搬家方案 Step 5）：
 *  - 页面: assets/public/index.html（Capacitor WebViewAssetLoader, https://localhost）
 *  - 桥: FridgeNative 插件 → WebAppBridge（事件分发 100% 复用, 数据层原封不动）
 *  - 原生 → 页面: evaluateJavascript(window.setData/…) 路径不变
 */
class MainActivity : BridgeActivity(), FridgeNative.Host {

    private val services: AppServices by lazy { (application as SmartFridgeApp).services }

    /** 桥延迟创建: 页面 ready（FridgeNative.ready()）后, 此时 getWebView() 可用 */
    private var bridge: WebAppBridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        FridgeNative.host = this
        // S3: 页面根 = files/web（热更版优先）；首次从 assets 播种出厂版兜底
        val webRoot = java.io.File(filesDir, "web").apply { mkdirs() }
        val boot = java.io.File(webRoot, "index.html")
        if (!boot.exists()) {
            try {
                assets.open("public/index.html").use { ins -> boot.writeBytes(ins.readBytes()) }
                Trace.log(this, "s3: asset-seeded webRoot -> files/web (fallback ready)")
            } catch (e: Exception) {
                Trace.log(this, "s3: seed fail ${e.message}")
            }
        } else {
            Trace.log(this, "s3: webRoot has hot version, using files/web")
        }
        // S3 最终版：Cap server.url = 本地 mini 服务（热更版优先/出厂兜底；官方 remote 模式桥可用）
        LocalWebServer(this, webRoot).start()
        bridgeBuilder.setConfig(
            com.getcapacitor.CapConfig.Builder(this)
                .setServerUrl("http://127.0.0.1:8890/")
                .create()
        )
        registerPlugin(FridgeNative::class.java)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (!Config.isConfigured) {
            Toast.makeText(this, "缺少配置：检查 android/local.properties (SUPABASE_URL / SUPABASE_KEY)", Toast.LENGTH_LONG).show()
            return
        }
        applyStatusBar(SkinManager.darkMode.value)
        Trace.log(this, "s3: webRoot=" + java.io.File(filesDir, "web").absolutePath)

        // 自动登录 + 同步激活（两人自用: autoEmail 或已存会话）
        lifecycleScope.launch { services.auth.restore() }
        lifecycleScope.launch {
            services.auth.state.collect { state ->
                android.util.Log.i("SYNC", "auth state=$state")
                if (state == AuthFlowState.READY) {
                    services.activateFamily()
                    applyStatusBar(SkinManager.darkMode.value)
                    // S2: READY 后仅检查（不自动下载）；点「更新」按钮才下载+生效
                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val v = UiUpdater.checkForUpdate(this@MainActivity, services)
                        if (v != null) {
                            val cur = loadedWebVersion()
                            runOnUiThread {
                                webView()?.evaluateJavascript(
                                    "window.setUiUpdate && window.setUiUpdate({\"cur\":\"$cur\",\"target\":\"$v\",\"state\":\"new\",\"ver\":\"$v\"})", null,
                                )
                            }
                        }
                    }
                    lifecycleScope.launch {
                        kotlinx.coroutines.withTimeoutOrNull(10_000) {
                            services.sync.items.first { it.isNotEmpty() }
                        }
                        android.util.Log.i("SYNC", "READY: items=${services.sync.items.value.size} bridge=${bridge != null} pushAll")
                        Trace.log(this@MainActivity, "READY items=${services.sync.items.value.size}")
                        services.migrateLegacyCategories()
                        bridge?.pushAll()
                    }
                    bridge?.pushAll()
                }
            }
        }

        // 夜间状态栏图标跟随
        lifecycleScope.launch { SkinManager.darkMode.collect { dark -> applyStatusBar(dark) } }

        // 返回键: 先关浮窗/详情（页面 handleBack），再交还系统
        onBackPressedDispatcher.addCallback(this) {
            val wv = webView()
            if (wv != null) {
                wv.evaluateJavascript("window.handleBack()") { raw ->
                    if (raw != "true") onBackPressedDispatcher.onBackPressed()
                }
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    /** Capacitor WebView（父 Bridge 提供；页面 ready 后可用） */
    private fun webView(): WebView? = getBridge()?.webView

    /** 当前生效的页面版本（files/web/version.json 的 ver；无 = 出厂种子未热更） */
    private fun loadedWebVersion(): String = try {
        val jf = java.io.File(filesDir, "web/version.json")
        if (jf.exists()) {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(jf.readText()).jsonObject
            o["ver"]?.jsonPrimitive?.contentOrNull ?: ""
        } else ""
    } catch (_: Exception) { "" }

    /** FridgeNative: 页面就绪 → 建桥 + 推安全区/主题/数据（插件线程 → 必须切主线程操作 WebView）
     *  Cap WebView 在 ready 瞬间可能尚未挂载：延迟重试至多 10 次（250ms/次）*/
    override fun onCapPageReady() {
        runOnUiThread { ensureBridgeAndPushAttempt(0) }
    }
    private fun ensureBridgeAndPushAttempt(attempt: Int) {
        val wv = webView()
        if (wv == null) {
            Trace.log(this, "ensure: attempt=$attempt webView=null")
            if (attempt < 10) {
                window.decorView.postDelayed({ ensureBridgeAndPushAttempt(attempt + 1) }, 250)
                return
            }
            Trace.log(this, "ensure: webView GAVE UP")
            // S4 熔断：页面连续 3 次未就绪 → 自动回滚上一版并重启
            val ui = getSharedPreferences("ui", MODE_PRIVATE)
            val fails = ui.getInt("page_fail", 0) + 1
            ui.edit().putInt("page_fail", fails).apply()
            Trace.log(this, "s4: page_fail=$fails")
            if (fails >= 3) {
                val root = java.io.File(filesDir, "web")
                val prev = java.io.File(root, "prev.html")
                val idx = java.io.File(root, "index.html")
                if (prev.exists()) {
                    prev.copyTo(idx, overwrite = true)
                    val pv = java.io.File(root, "prev-version.json")
                    if (pv.exists()) pv.copyTo(java.io.File(root, "version.json"), overwrite = true)
                    Trace.log(this, "s4: ROLLBACK to prev (fails=$fails)")
                }
                ui.edit().putInt("page_fail", 0).apply()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ recreate() }, 800)
            }
            return
        }
        if (bridge == null) {
            Trace.log(this, "bridge: creating (attempt=$attempt)")
            bridge = WebAppBridge(
                this, services, wv,
                onThemeChanged = { dark -> applyStatusBar(dark) },
                onReminderConfig = { enabled, hours -> applyReminder(enabled, hours) },
            )
        }
        // 双通道桥：AndroidBridge(自营,绝对可靠) 优先 + FridgeNative(Cap 插件) 兜底
        val b = bridge ?: return
        wv.addJavascriptInterface(b, "AndroidBridge")
        pushInsets()
        wv.evaluateJavascript("window.setAppTheme(${SkinManager.darkMode.value})", null)
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val en = sp.getBoolean("reminder_enabled", true)
        val hr = sp.getInt("reminder_hours", 24)
        wv.evaluateJavascript("window.setReminder && window.setReminder({\"enabled\":$en,\"hours\":$hr})", null)
        Trace.log(this, "bridge: pushAll called")
        bridge?.pushAll()
    }

    /** FridgeNative: 页面事件 → 复用 WebAppBridge 分发（15 类事件契约不变） */
    override fun onCapEvent(type: String, payload: String) {
        if (type == "trace") { Trace.log(this, "page-trace: $payload"); return }
        if (type == "ui-update" || type == "ui-restart") {
            // 点击「更新」：下载 → 验签 → 落盘 → 自动 reload 生效（一条龙；兼容旧页 ui-restart）
            Trace.log(this, "s4: update clicked ($type)")
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    UiUpdater.downloadNow(
                        this@MainActivity, services,
                        onDone = { ver ->
                            runOnUiThread {
                                Trace.log(this@MainActivity, "s4: applied v$ver, reload")
                                webView()?.reload()
                            }
                        },
                        onFail = {
                            runOnUiThread {
                                webView()?.evaluateJavascript(
                                    "window.toast && window.toast('更新校验失败，请重试')", null,
                                )
                            }
                        },
                    )
                } catch (e: Exception) {
                    Trace.log(this@MainActivity, "s4: update fail ${e.message}")
                    runOnUiThread {
                        webView()?.evaluateJavascript("window.toast && window.toast('更新失败，请检查网络后重试')", null)
                    }
                }
            }
            return
        }
        if (type == "page-ready") {
            // 页面就绪：推安全区/主题/提醒/数据/UI版本状态（统一初始化入口）
            runOnUiThread {
                val wv = webView() ?: return@runOnUiThread
                pushInsets()
                wv.evaluateJavascript("window.setAppTheme(${SkinManager.darkMode.value})", null)
                // 版本卡信息: App 版本(原生) + UI 版本(version.json, 走 setUiUpdate)
                wv.evaluateJavascript(
                    "window.setAppInfo && window.setAppInfo(\"${BuildConfig.VERSION_NAME}\", ${BuildConfig.VERSION_CODE})", null,
                )
                val ui = getSharedPreferences("ui", MODE_PRIVATE)
                // 以 files/web/version.json 为真相：已生效=ok，未生效=pending=new
                val pending = ui.getString("ui_ver", "") ?: ""
                val loaded = loadedWebVersion()
                val st = if (pending.isNotEmpty() && pending != loaded) "new" else "ok"
                if (loaded.isNotEmpty()) {
                    // 契约 v2: cur=本机版本 target=可更新版本（ver 保留兼容旧页=本机版本）
                    val target = if (st == "new") pending else ""
                    wv.evaluateJavascript(
                        "window.setUiUpdate && window.setUiUpdate({\"cur\":\"$loaded\",\"target\":\"$target\",\"state\":\"$st\",\"ver\":\"$loaded\"})", null,
                    )
                }
                getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("page_fail", 0).apply()
                Trace.log(this, "bridge: pushAll called")
                bridge?.pushAll()
            }
            return
        }
        bridge?.onEvent(type, payload)
    }

    /** 安全区: 状态栏/挖孔 → top; 手势条 → bottom（px） */
    private fun pushInsets() {
        val wv = webView() ?: return
        val insets = ViewCompat.getRootWindowInsets(wv) ?: return
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val top = maxOf(bars.top, cutout.top)
        val bottom = maxOf(bars.bottom, cutout.bottom)
        wv.evaluateJavascript("window.setInsets && window.setInsets($top, $bottom)", null)
    }

    /** 状态栏图标颜色跟随夜间 */
    private fun applyStatusBar(dark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
        }
    }

    /** 临期提醒功能已下线(2026-09-04): 一律禁用并取消历史任务；不再请求通知权限/调度 */
    private fun applyReminder(enabled: Boolean, hours: Int) {
        com.smartfridge.app.notify.ExpiryReminder.disable(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
