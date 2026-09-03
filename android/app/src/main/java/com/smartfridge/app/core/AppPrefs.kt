package com.smartfridge.app.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局偏好 (WebView 版): 皮肤 / 图标方案 / 夜间模式 —— 持久化在 SharedPreferences。
 * 从旧 ui/theme 层提取 (Compose UI 已废弃删除), 数据桥 WebAppBridge/WebData 使用。
 */

/** 奶油皮肤 (经典原味=纯奶油 / 玻璃动效=磨砂&光晕) */
enum class CreamSkin(val id: String, val label: String, val desc: String) {
    GLASS("glass", "玻璃动效", "磨砂 & 光晕 (默认)"),
    CLASSIC("classic", "经典原味", "纯奶油风"),
}

/** 图标方案 (a emoji / b 线描 / c 贴纸 — 由页面渲染, Kotlin 只持久化) */
enum class IconSet(val id: String, val label: String, val desc: String) {
    EMOJI("a", "本色拟物", "系统 emoji"),
    LINE("b", "手绘线描", "可随主题染色"),
    STICKER("c", "奶油贴纸", "色块高光"),
}

object SkinManager {
    private const val PREFS = "skin_prefs"
    private const val KEY_ICON = "icon_set"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_SKIN = "cream_skin"
    private var prefs: android.content.SharedPreferences? = null

    private val _skinPref = MutableStateFlow(CreamSkin.GLASS)
    val skinPref: StateFlow<CreamSkin> = _skinPref

    private val _iconSet = MutableStateFlow(IconSet.EMOJI)
    val iconSet: StateFlow<IconSet> = _iconSet

    private val _darkMode = MutableStateFlow(false)
    val darkMode: StateFlow<Boolean> = _darkMode

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _skinPref.value = prefs?.getString(KEY_SKIN, null)?.let { id ->
            CreamSkin.entries.firstOrNull { it.id == id }
        } ?: CreamSkin.GLASS
        _iconSet.value = prefs?.getString(KEY_ICON, null)?.let { id ->
            IconSet.entries.firstOrNull { it.id == id }
        } ?: IconSet.EMOJI
        _darkMode.value = prefs?.getBoolean(KEY_DARK, false) ?: false
    }

    fun setSkinPref(skin: CreamSkin) {
        _skinPref.value = skin
        prefs?.edit()?.putString(KEY_SKIN, skin.id)?.apply()
    }

    fun setIconSet(set: IconSet) {
        _iconSet.value = set
        prefs?.edit()?.putString(KEY_ICON, set.id)?.apply()
    }

    /** 夜间模式开关 */
    fun setDarkMode(on: Boolean) {
        _darkMode.value = on
        prefs?.edit()?.putBoolean(KEY_DARK, on)?.apply()
    }
}
