package com.smartfridge.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** 应用内追踪通道（logcat 不可靠时的替代：写 filesDir/trace.log，adb run-as 可读） */
object Trace {
    private val lock = Any()

    fun log(context: Context, msg: String) {
        try {
            synchronized(lock) {
                val f = File(context.filesDir, "trace.log")
                FileOutputStream(f, true).use { out ->
                    out.write("${System.currentTimeMillis()} $msg\n".toByteArray())
                }
            }
        } catch (_: Exception) {
        }
        try { android.util.Log.w("XC_TRACE", msg) } catch (_: Exception) {}
    }
}
