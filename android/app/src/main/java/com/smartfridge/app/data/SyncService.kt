package com.smartfridge.app.data

import com.smartfridge.app.data.local.FridgeDb
import com.smartfridge.app.domain.Ingredient
import com.smartfridge.app.domain.IngredientDraft
import com.smartfridge.app.domain.StorageZone
import com.smartfridge.app.domain.FreshnessTable
import com.smartfridge.app.domain.guessCategoryFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.UUID

/**
 * 本地优先同步服务 (Local-First) —— 核心模块 (与 Flutter 版同构)。
 *
 * 数据流:
 *   UI ← 写 → SQLite(真相源) → outbox 队列 → 联网后推 Supabase
 *   UI ← 读 ← SQLite ← Realtime 事件 / 主动拉取
 *
 * 冲突策略 = 行级 LWW (按 updated_at 时间戳):
 *   - 推送: 云行 updated_at 更新 → 云胜回写本地; 否则本地胜覆盖云
 *   - 接收: 云行更新才覆盖本地; 本地有未推送变更且更新 → 保留本地
 * 离线: 写入先落本地, 网络失败由 30s 周期自动重试, 无需人工干预。
 */
class SyncService(
    private val db: FridgeDb,
    private val api: SupabaseApi,
    private val tokenProvider: () -> String?,
    private val onUnauthorized: (suspend () -> String?)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<List<Ingredient>>(emptyList())
    val items: StateFlow<List<Ingredient>> = _items.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastSyncAt = MutableStateFlow(0L)
    val lastSyncAt: StateFlow<Long> = _lastSyncAt.asStateFlow()

    /** 同步出错回调 (UI 用它弹提示) */
    var onSyncError: ((String) -> Unit)? = null

    private fun reportError(msg: String) {
        _lastError.value = msg
        onSyncError?.invoke(msg)
    }

    @Volatile
    private var familyId: String = ""
    private var realtimeJob: kotlinx.coroutines.Job? = null
    private var retryJob: kotlinx.coroutines.Job? = null
    private val pushLock = Mutex()

    val activated: Boolean get() = familyId.isNotBlank()

    /** 登录并确定家庭后调用: 恢复上下文 → 拉全量 → 补推积压 → 订阅实时 */
    fun activate(newFamilyId: String) {
        familyId = newFamilyId
        db.setMeta("family_id", newFamilyId)

        realtimeJob?.cancel()
        realtimeJob = scope.launch {
            // Realtime 流与登录态绑定: token 变化(刷新)时自动重启订阅
            api.watchRealtime(newFamilyId, tokenProvider).collectLatest { change ->
                applyRemoteChange(change)
            }
        }

        retryJob?.cancel()
        retryJob = scope.launch {
            while (isActive) {
                push()
                pull()
                delay(30_000)
            }
        }

        scope.launch { pull(); push() }
    }

    fun deactivate() {
        familyId = ""
        realtimeJob?.cancel()
        retryJob?.cancel()
    }

    fun dispose() = scope.cancel()

    // ---------- 写入 (先本地, 后尽力推送) ----------

    /** 解析出的草稿批量入库; 同名同区已存在则数量累加 */
    suspend fun addDrafts(drafts: List<IngredientDraft>, userName: String): List<Ingredient> {
        require(activated) { "同步服务未激活" }
        val created = mutableListOf<Ingredient>()
        for (d in drafts) {
            val now = Instant.now()
            val existing = db.findByNameAndZone(familyId, d.name, d.zone.db)
            if (existing != null) {
                val merged = existing.copyWith(
                    quantity = existing.quantity + d.quantity,
                    addedByUserName = userName,
                    updatedAt = now,
                )
                db.upsertIngredient(merged)
                db.enqueueOutbox(merged.id, "upsert", merged.toJsonObject().toString())
                created += merged
            } else {
                val item = Ingredient(
                    id = UUID.randomUUID().toString(),
                    familyId = familyId,
                    name = d.name,
                    category = guessCategoryFor(d.name, d.zone),
                    quantity = d.quantity,
                    unit = d.unit,
                    zone = d.zone,
                    purchasedAt = now,
                    shelfLifeDays = d.shelfLifeDays,
                    addedByUserName = userName,
                    updatedAt = now,
                )
                db.upsertIngredient(item)
                db.enqueueOutbox(item.id, "upsert", item.toJsonObject().toString())
                created += item
            }
        }
        emit()
        pushQuietly()
        return created
    }

    /** 消耗用量: 扣减后不足则整条删除 */
    suspend fun consume(id: String, amount: Double) {
        val row = db.getIngredient(id) ?: return
        val now = Instant.now()
        if (row.quantity - amount <= 0.0001) {
            db.deleteIngredient(id)
            db.enqueueOutbox(id, "delete", now.toEpochMilli().toString()) // payload=删除时间戳, push 冲突仲裁用
        } else {
            val updated = row.copyWith(quantity = row.quantity - amount, updatedAt = now)
            db.upsertIngredient(updated)
            db.enqueueOutbox(id, "upsert", updated.toJsonObject().toString())
        }
        emit()
        pushQuietly()
    }

    suspend fun remove(id: String) {
        db.deleteIngredient(id)
        db.enqueueOutbox(id, "delete", System.currentTimeMillis().toString()) // payload=删除时间戳
        emit()
        pushQuietly()
    }

    /** 刷新保质期：购买时间重置为现在 + 按保鲜表重算 shelf；全家同步 */
    suspend fun refreshFreshness(id: String) {
        val row = db.getIngredient(id) ?: return
        val now = Instant.now()
        val shelf = FreshnessTable.daysFor(row.name, row.zone.db) ?: row.shelfLifeDays
        val fresh = row.copyWith(purchasedAt = now, shelfLifeDays = shelf, updatedAt = now)
        db.upsertIngredient(fresh)
        db.enqueueOutbox(id, "upsert", fresh.toJsonObject().toString())
        emit()
        pushQuietly()
    }

    /** 更改食材存放分区 (冷藏/冷冻/常温), 全家实时同步; 换区自动按保鲜表重算保质期 */
    suspend fun changeZone(id: String, zone: StorageZone) {
        val row = db.getIngredient(id) ?: return
        if (row.zone == zone) return
        val shelf = FreshnessTable.daysFor(row.name, zone.db) ?: row.shelfLifeDays
        val updated = row.copyWith(zone = zone, shelfLifeDays = shelf, updatedAt = Instant.now())
        db.upsertIngredient(updated)
        db.enqueueOutbox(id, "upsert", updated.toJsonObject().toString())
        emit()
        pushQuietly()
    }

    /** 重新编辑食材 (名称/数量/单位/分区/类别), 全家实时同步 */
    suspend fun updateIngredient(
        id: String,
        name: String,
        quantity: Double,
        unit: String,
        zone: StorageZone,
        category: String? = null,
    ) {
        val row = db.getIngredient(id) ?: return
        val shelf = if (zone != row.zone) (FreshnessTable.daysFor(name.ifBlank { row.name }, zone.db) ?: row.shelfLifeDays) else row.shelfLifeDays
        val updated = row.copyWith(
            name = name.ifBlank { row.name },
            quantity = if (quantity > 0) quantity else row.quantity,
            unit = unit.ifBlank { row.unit },
            zone = zone,
            category = if (category.isNullOrBlank()) row.category else category,
            shelfLifeDays = shelf,
            updatedAt = Instant.now(),
        )
        db.upsertIngredient(updated)
        db.enqueueOutbox(id, "upsert", updated.toJsonObject().toString())
        emit()
        pushQuietly()
    }

    /** 手动触发一次完整同步 (设置页按钮) */
    suspend fun syncNow() {
        push()
        pull()
    }

    // ---------- 推送 & 冲突解决 ----------

    private fun pushQuietly() {
        scope.launch { push() }
    }

    /** 把 outbox 逐条推向云端; 任一条失败立即停止 (保序), 周期任务稍后重试 */
    suspend fun push() {
        if (!activated) return
        pushLock.withLock {
            var token = tokenProvider() ?: return
            var refreshed = false
            for (entry in db.pendingOutbox()) {
                try {
                    if (entry.op == "upsert") {
                        val local = parsePayload(entry.payload)
                        if (local == null) {
                            db.removeOutbox(entry.id) // 坏数据: 清理, 防死条目永远阻塞队列
                            continue
                        }
                        val remote = api.fetchOne(familyId, local.id, token)
                        if (remote != null && remote.updatedAt.isAfter(local.updatedAt)) {
                            db.upsertIngredient(remote) // 云胜: 回写本地, 丢弃本地变更
                        } else {
                            api.upsert(local, token)    // 本地胜 (或云无此行)
                        }
                    } else {
                        // delete: 删除时间戳作为仲裁依据                        //  - 云端行已无 → 删除达成
                        //  - 云端行 updatedAt 比删除时间新(他人又改了) → 云胜, 回写
                        //  - 否则 → 真正从云端删除 (修复: 之前只回写不删导致"删不掉"bug)
                        val deletedAtMs = entry.payload.toLongOrNull() ?: 0L
                        val remote = api.fetchOne(familyId, entry.entityId, token)
                        if (remote != null) {
                            if (remote.updatedAt.toEpochMilli() > deletedAtMs) {
                                db.upsertIngredient(remote)
                            } else {
                                api.delete(familyId, entry.entityId, token)
                            }
                        }
                    }
                    db.removeOutbox(entry.id)
                } catch (e: Exception) {
                    // 401 哨兵: 刷新一次 token 后重试当前条目 (不再 401 才 break)
                    if (!refreshed && e.message?.contains("401") == true && onUnauthorized != null) {
                        refreshed = true
                        token = onUnauthorized() ?: return
                        continue
                    }
                    if (e.message?.contains("HTTP") == true) reportError("推送失败: ${e.message}")
                    break // 网络失败: 保序, 等周期重试
                }
            }
            if (_lastError.value == null && db.pendingOutbox().isEmpty()) {
                _lastSyncAt.value = System.currentTimeMillis()
            }
            emit()
        }
    }

    /** 从云端拉取全量 → 合入本地 (LWW 守卫 + 删除传播) */
    suspend fun pull() {
        if (!activated) return
        val token = tokenProvider() ?: return
        try {
            val remoteRows = api.fetchAll(familyId, token)
            val pendingById = db.pendingOutbox().associateBy { it.entityId } // id -> op
            for (r in remoteRows) {
                val local = db.getIngredient(r.id)
                // 本地有未推送的删除 → 不复活, 交给 push 真删
                if (local == null && pendingById[r.id]?.op == "delete") continue
                // 本地有未推送变更且本地更新 → 保留本地, 交给 push 裁决
                if (local != null && pendingById.containsKey(r.id) && local.updatedAt.isAfter(r.updatedAt)) continue
                if (local == null || !r.updatedAt.isBefore(local.updatedAt)) db.upsertIngredient(r)
            }
            // 删除传播: 云端已消失、且本地无未推送变动的行 → 移除
            val remoteIds = remoteRows.map { it.id }.toSet()
            for (localRow in db.allIngredients(familyId)) {
                if (localRow.id !in remoteIds && !pendingById.containsKey(localRow.id)) db.deleteIngredient(localRow.id)
            }
            db.setMeta("last_pull_at", Instant.now().toString())
            _lastError.value = null // 成功清除错误
            _lastSyncAt.value = System.currentTimeMillis()
            emit()
        } catch (e: Exception) {
            // 401 哨兵: 刷新 token 后重拉一次
            if (e.message?.contains("401") == true && onUnauthorized != null) {
                if (onUnauthorized() != null) { pull(); return }
            }
            reportError("拉取失败: ${e.message}")
        }
    }

    // ---------- Realtime 合入 ----------

    private suspend fun applyRemoteChange(change: RealtimeChange) {
        try {
            var changed = false
            for (remote in change.upserts) {
                val local = db.getIngredient(remote.id)
                if (local == null || !remote.updatedAt.isBefore(local.updatedAt)) {
                    db.upsertIngredient(remote)
                    changed = true
                }
            }
            for (id in change.deletedIds) {
                val local = db.getIngredient(id)
                // 本地有未推送变更时让位 (push 时按 LWW 裁决)
                if (local != null && db.pendingOutbox().none { it.entityId == id }) {
                    db.deleteIngredient(id)
                    changed = true
                }
            }
            if (changed) emit()
        } catch (e: Exception) {
            reportError("实时数据合入失败: ${e.message}")
        }
    }

    // ---------- 内部 ----------

    private fun parsePayload(payload: String): Ingredient? = try {
        Ingredient.fromJson(Json.parseToJsonElement(payload).jsonObject)
    } catch (_: Exception) { null }

    private fun emit() {
        scope.launch { _items.value = db.allIngredients(familyId) }
    }
}
