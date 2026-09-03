package com.smartfridge.app.web

import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Capacitor 桥插件（搬家方案 Step 4）：
 *  页面 → 原生：FridgeNative.receiveEvent(type, payload) → Host.onCapEvent → WebAppBridge.onEvent（复用原事件分发）
 *  页面就绪：FridgeNative.ready() → Host.onCapPageReady → 推送安全区/主题/数据
 *  原生 → 页面：继续 evaluateJavascript(window.setData/…)（Capacitor WebView 同内核）
 *  host 通过静态引用注入（Capacitor 6 registerPlugin 单参构造，无法直接传实例）。
 */
@CapacitorPlugin(name = "FridgeNative")
class FridgeNative : Plugin() {

    interface Host {
        fun onCapEvent(type: String, payload: String)
        fun onCapPageReady()
    }

    companion object {
        @Volatile
        var host: Host? = null
    }

    @PluginMethod
    fun receiveEvent(call: PluginCall) {
        val type = call.getString("type") ?: run { call.resolve(); return }
        val payload = call.getString("payload").orEmpty()
        host?.onCapEvent(type, payload)
        call.resolve()
    }

    @PluginMethod
    fun ready(call: PluginCall) {
        host?.onCapPageReady()
        call.resolve()
    }
}
