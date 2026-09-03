package com.smartfridge.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.smartfridge.app.domain.Ingredient
import com.smartfridge.app.domain.StorageZone
import java.time.Instant

/**
 * 本地 SQLite —— 离线优先 (Local-First) 的「真相源」。
 * 三张表: ingredients(库存镜像) / outbox(待推送队列) / sync_state(同步元数据)。
 * (用系统 SQLiteOpenHelper, 不依赖 Room/KSP, 减少构建链不确定性)
 */
class FridgeDb(context: Context) : SQLiteOpenHelper(context, "smart_fridge.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ingredients(
              id TEXT PRIMARY KEY,
              family_id TEXT NOT NULL,
              name TEXT NOT NULL,
              category TEXT NOT NULL DEFAULT '其他',
              quantity REAL NOT NULL DEFAULT 1,
              unit TEXT NOT NULL DEFAULT '份',
              zone TEXT NOT NULL,
              purchased_at INTEGER NOT NULL,
              shelf_life_days INTEGER NOT NULL,
              added_by_user_name TEXT NOT NULL DEFAULT '',
              updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ingredients_family ON ingredients(family_id, zone)")
        db.execSQL(
            """
            CREATE TABLE outbox(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              entity_id TEXT NOT NULL,
              op TEXT NOT NULL CHECK(op IN ('upsert','delete')),
              payload TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE sync_state(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    /** SQLiteOpenHelper 强制要求实现: 数据库版本升级时的迁移钩子。
     *  MVP 阶段表结构无变更, 留空即可; 将来加字段/建表时按 oldVersion 分支写迁移。 */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // TODO(升级迁移): if (oldVersion < 2) { db.execSQL(...) }
    }

    // ---------- ingredients ----------

    fun upsertIngredient(i: Ingredient) {
        val v = writableDatabase
        v.beginTransaction()
        try {
            upsertRaw(v, i)
            v.setTransactionSuccessful()
        } finally {
            v.endTransaction()
        }
    }

    fun bulkUpsert(list: List<Ingredient>) {
        val v = writableDatabase
        v.beginTransaction()
        try {
            for (i in list) upsertRaw(v, i)
            v.setTransactionSuccessful()
        } finally {
            v.endTransaction()
        }
    }

    private fun upsertRaw(v: SQLiteDatabase, i: Ingredient) {
        val cv = ContentValues().apply {
            put("id", i.id)
            put("family_id", i.familyId)
            put("name", i.name)
            put("category", i.category)
            put("quantity", i.quantity)
            put("unit", i.unit)
            put("zone", i.zone.db)
            put("purchased_at", i.purchasedAt.toEpochMilli())
            put("shelf_life_days", i.shelfLifeDays)
            put("added_by_user_name", i.addedByUserName)
            put("updated_at", i.updatedAt.toEpochMilli())
        }
        v.insertWithOnConflict("ingredients", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteIngredient(id: String) {
        writableDatabase.delete("ingredients", "id = ?", arrayOf(id))
    }

    fun getIngredient(id: String): Ingredient? =
        queryOne("SELECT * FROM ingredients WHERE id = ?", arrayOf(id))

    /** 同名同区是否已存在 (入库合并用: 存在则数量累加) */
    fun findByNameAndZone(familyId: String, name: String, zoneDb: String): Ingredient? =
        queryOne(
            "SELECT * FROM ingredients WHERE family_id = ? AND name = ? AND zone = ? LIMIT 1",
            arrayOf(familyId, name, zoneDb),
        )

    fun allIngredients(familyId: String): List<Ingredient> =
        readableDatabase
            .rawQuery(
                "SELECT * FROM ingredients WHERE family_id = ? ORDER BY updated_at DESC",
                arrayOf(familyId),
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toIngredient())
                }
            }

    // ---------- outbox ----------

    fun enqueueOutbox(entityId: String, op: String, payload: String) {
        writableDatabase.insert(
            "outbox", null,
            ContentValues().apply {
                put("entity_id", entityId)
                put("op", op)
                put("payload", payload)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    fun pendingOutbox(): List<OutboxEntry> =
        readableDatabase.rawQuery("SELECT * FROM outbox ORDER BY id ASC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        OutboxEntry(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            entityId = cursor.getString(cursor.getColumnIndexOrThrow("entity_id")),
                            op = cursor.getString(cursor.getColumnIndexOrThrow("op")),
                            payload = cursor.getString(cursor.getColumnIndexOrThrow("payload")),
                        )
                    )
                }
            }
        }

    fun removeOutbox(id: Long) {
        writableDatabase.delete("outbox", "id = ?", arrayOf(id.toString()))
    }

    // ---------- sync_state ----------

    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "sync_state", null,
            ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getMeta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM sync_state WHERE key = ?", arrayOf(key))
            .use { if (it.moveToNext()) it.getString(0) else null }

    // ---------- 内部 ----------

    private fun queryOne(sql: String, args: Array<String>): Ingredient? =
        readableDatabase.rawQuery(sql, args).use {
            if (it.moveToNext()) it.toIngredient() else null
        }

    private fun Cursor.toIngredient(): Ingredient = Ingredient(
        id = getString(getColumnIndexOrThrow("id")),
        familyId = getString(getColumnIndexOrThrow("family_id")),
        name = getString(getColumnIndexOrThrow("name")),
        category = getString(getColumnIndexOrThrow("category")),
        quantity = getDouble(getColumnIndexOrThrow("quantity")),
        unit = getString(getColumnIndexOrThrow("unit")),
        zone = StorageZone.fromDb(getString(getColumnIndexOrThrow("zone"))),
        purchasedAt = Instant.ofEpochMilli(getLong(getColumnIndexOrThrow("purchased_at"))),
        shelfLifeDays = getInt(getColumnIndexOrThrow("shelf_life_days")),
        addedByUserName = getString(getColumnIndexOrThrow("added_by_user_name")),
        updatedAt = Instant.ofEpochMilli(getLong(getColumnIndexOrThrow("updated_at"))),
    )
}

data class OutboxEntry(
    val id: Long,
    val entityId: String,
    val op: String, // upsert | delete
    val payload: String,
)
