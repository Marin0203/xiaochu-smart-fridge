package com.smartfridge.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smartfridge.app.data.local.FridgeDb
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 临期食材提醒:
 *  - 每日后台检查一次本地库存 (WorkManager)
 *  - 有黄色/红色预警 → 发系统通知 (Android 13+ 需用户授权通知权限)
 */
object ExpiryReminder {

    const val CHANNEL_ID = "expiry"
    private const val WORK_NAME = "expiry-check"

    /** 创建通知渠道 + 注册每日检查任务 (幂等, 可重复调用) */
    fun setup(context: Context) {
        // 1) 通知渠道 (Android 8+ 必需)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "保质期提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "食材临期/过期时提醒你"
                },
            )
        }
        // 2) 每日 09:00 附近检查一次
        val request = PeriodicWorkRequestBuilder<ExpiryCheckWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** 主动触发的立即检查 (用于「测试提醒」) */
    fun checkNow(context: Context) {
        val request = androidx.work.OneTimeWorkRequestBuilder<ExpiryCheckWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
    }

    /** 关闭提醒 (设置页滑块) */
    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** 设置「提前 N 小时」提醒阈值 (6-72h, 默认 24) */
    fun setAheadHours(context: Context, hours: Int) {
        context.getSharedPreferences("expiry_prefs", Context.MODE_PRIVATE)
            .edit().putInt("ahead_hours", hours.coerceIn(6, 72)).apply()
    }

    fun aheadHours(context: Context): Int =
        context.getSharedPreferences("expiry_prefs", Context.MODE_PRIVATE)
            .getInt("ahead_hours", 24)
}

/** 后台检查 Worker: 读本地库存, 有临期/过期则发通知 */
class ExpiryCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = FridgeDb(applicationContext)
            val familyId = db.getMeta("family_id")
            if (familyId.isNullOrBlank()) {
                Result.success() // 还没登录, 跳过
            } else {
                val ahead = ExpiryReminder.aheadHours(applicationContext)
                val alerts = db.allIngredients(familyId).filter {
                    Duration.between(Instant.now(), it.expiresAt()).toHours() <= ahead
                }
                if (alerts.isNotEmpty()) showExpiryNotification(applicationContext, alerts)
                Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun showExpiryNotification(context: Context, items: List<com.smartfridge.app.domain.Ingredient>) {
        // Android 13+ 未授权通知权限 → 静默跳过 (授权入口在设置页)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val expired = items.count { it.freshnessStatus() == com.smartfridge.app.domain.FreshnessStatus.EXPIRED }
        val expiring = items.size - expired
        val names = items.take(3).joinToString("、") { it.name } +
            if (items.size > 3) " 等 ${items.size} 项" else ""
        val text = if (expiring > 0 && expired > 0) {
            "$names — $expired 项已过期, $expiring 项临期, 交给 AI 菜谱优先消耗!"
        } else if (expired > 0) {
            "$names — 已过期, 记得取出来处理哦"
        } else {
            "$names — 快到保质期了, 优先吃掉它们!"
        }

        val notification = NotificationCompat.Builder(context, ExpiryReminder.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("小厨提醒: ${items.size} 项食材需要处理")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(1001, notification)
    }
}
