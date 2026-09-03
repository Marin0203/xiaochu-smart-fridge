import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

import '../../domain/models/ingredient.dart';

/// 本地 SQLite —— 离线优先 (Local-First) 的「真相源」。
/// 三张表:
///  - ingredients: 本地库存镜像 (与云端行级对应)
///  - outbox:      待推送变更队列 (离线期间的所有写入先落这里)
///  - sync_state:  同步元数据
class FridgeLocalDb {
  final Database db;
  FridgeLocalDb._(this.db);

  static Future<FridgeLocalDb> open() async {
    final path = p.join(await getDatabasesPath(), 'smart_fridge.db');
    final db = await openDatabase(
      path,
      version: 1,
      onCreate: (db, version) async {
        await db.execute('''
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
)''');
        await db.execute(
            'CREATE INDEX idx_ingredients_family ON ingredients(family_id, zone)');
        await db.execute('''
CREATE TABLE outbox(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  entity_id TEXT NOT NULL,
  op TEXT NOT NULL CHECK(op IN ('upsert','delete')),
  payload TEXT NOT NULL,
  created_at INTEGER NOT NULL
)''');
        await db.execute(
            'CREATE TABLE sync_state(key TEXT PRIMARY KEY, value TEXT NOT NULL)');
      },
    );
    return FridgeLocalDb._(db);
  }

  // ---------- ingredients ----------

  Future<void> upsertIngredient(Ingredient i) => db.insert(
        'ingredients',
        i.toRow(),
        conflictAlgorithm: ConflictAlgorithm.replace,
      );

  Future<void> bulkUpsert(List<Ingredient> list) async {
    final batch = db.batch();
    for (final i in list) {
      batch.insert('ingredients', i.toRow(),
          conflictAlgorithm: ConflictAlgorithm.replace);
    }
    await batch.commit(noResult: true);
  }

  Future<void> deleteIngredient(String id) =>
      db.delete('ingredients', where: 'id = ?', whereArgs: [id]);

  Future<Ingredient?> getIngredient(String id) async {
    final rows = await db.query('ingredients',
        where: 'id = ?', whereArgs: [id], limit: 1);
    return rows.isEmpty ? null : Ingredient.fromRow(rows.first);
  }

  /// 同名同区是否已存在 (入库合并用: 存在则数量累加)
  Future<Ingredient?> findByNameAndZone(
      String familyId, String name, String zoneDb) async {
    final rows = await db.query(
      'ingredients',
      where: 'family_id = ? AND name = ? AND zone = ?',
      whereArgs: [familyId, name, zoneDb],
      limit: 1,
    );
    return rows.isEmpty ? null : Ingredient.fromRow(rows.first);
  }

  Future<List<Ingredient>> allIngredients(String familyId) async {
    final rows = await db.query(
      'ingredients',
      where: 'family_id = ?',
      whereArgs: [familyId],
      orderBy: 'updated_at DESC',
    );
    return rows.map(Ingredient.fromRow).toList();
  }

  // ---------- outbox ----------

  Future<void> enqueueOutbox(
          String entityId, String op, String payload) =>
      db.insert('outbox', {
        'entity_id': entityId,
        'op': op,
        'payload': payload,
        'created_at': DateTime.now().millisecondsSinceEpoch,
      });

  Future<List<OutboxEntry>> pendingOutbox() async {
    final rows = await db.query('outbox', orderBy: 'id ASC');
    return rows
        .map((r) => OutboxEntry(
              id: r['id'] as int,
              entityId: r['entity_id'] as String,
              op: r['op'] as String,
              payload: r['payload'] as String,
            ))
        .toList();
  }

  Future<void> removeOutbox(int id) =>
      db.delete('outbox', where: 'id = ?', whereArgs: [id]);

  // ---------- sync_state ----------

  Future<void> setMeta(String key, String value) => db.insert(
      'sync_state',
      {'key': key, 'value': value},
      conflictAlgorithm: ConflictAlgorithm.replace);

  Future<String?> getMeta(String key) async {
    final rows =
        await db.query('sync_state', where: 'key = ?', whereArgs: [key], limit: 1);
    return rows.isEmpty ? null : rows.first['value'] as String?;
  }

  Future<void> close() => db.close();
}

class OutboxEntry {
  final int id;
  final String entityId;
  final String op; // upsert | delete
  final String payload; // JSON (delete 时可为空串)

  const OutboxEntry({
    required this.id,
    required this.entityId,
    required this.op,
    required this.payload,
  });
}
