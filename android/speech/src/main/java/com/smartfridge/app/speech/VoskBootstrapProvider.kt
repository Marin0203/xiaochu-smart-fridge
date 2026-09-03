package com.smartfridge.app.speech

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 进程启动时自动注册 Vosk 引擎 (零配置)。
 * :app 侧只需依赖 :speech, 无需任何初始化代码。
 */
class VoskBootstrapProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        VoskVoiceEngine.install(context!!)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
