import 'dart:async';
import 'dart:convert';

import 'package:uuid/uuid.dart';

import '../../domain/models/ingredient.dart';
import '../../domain/models/ingredient_draft.dart';
import '../../domain/services/category_guesser.dart';
import '../local/fridge_local_db.dart';
import '../remote/supabase_api.dart';

/// 本地优先同步服务 (Local-First)  —— 本应用最核心的模块。
///
/// 数据流:
///   UI ← 写 → SQLite(真相源) → outbox 队列 → 联网后推 Supabase
///   UI ← 读 ← SQLite ← Realtime 事件 / 主动拉取
///
/// 冲突策略 = **行级 LWW (Last-Write-Wins, 按 updated_at 时间戳)**:
///   - 推送时: 云行 updated_at 更新 → 云胜, 回写本地; 否则本地胜, 覆盖云
///   - 接收时: 云行比本地新 → 覆盖本地; 本地有未推送变更且更新 → 保留本地
///   选择理由: 库存行小、家庭内编辑频次低, LWW 简单可靠; 真正的 CRDT 需要
///   per-field 编辑语义, 收益不足以支撑复杂度 (演进方案见 docs/architecture.md)
///
/// 离线支持: 所有写入先落 SQLite + outbox, 网络失败自动进入 30s 周期重试,
/// 恢复网络无需手动干预; 其他家庭成员通过 Supabase Realtime 秒级看到变更。
class SyncService {
  final FridgeLocalDb _db;
  final SupabaseApi _api;
  static final _uuid = Uuid();

  String _familyId = '';
  StreamSubscription<List<Ingredient>>? _remoteSub;
  Timer? _retryTimer;
  Timer? _emitTimer;
  bool _syncing = false;

  final _controller = StreamController<List<Ingredient>>.broadcast();

  /// 同步失败时通知 UI (可选回调, 用于 SnackBar)
  void Function(String message)? onSyncError;

  SyncService(this._db, this._api);

  bool get activated => _familyId.isNotEmpty;

  /// 登录并确定家庭后调用: 恢复上下文 → 拉全量 → 补推积压 → 订阅实时
  Future<void> activate(String familyId) async {
    _familyId = familyId;
    await _db.setMeta('family_id', familyId);
    await _remoteSub?.cancel();
    _remoteSub = _api.watch(familyId).listen(_applyRemoteEvent,
        onError: (Object e) {
      onSyncError?.call('实时通道断开: $e');
    });
    _retryTimer?.cancel();
    _retryTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      if (!_syncing) {
        unawaited(push());
        unawaited(pull());
      }
    });
    await pull();
    await push();
    _emit();
  }

  void deactivate() {
    _remoteSub?.cancel();
    _retryTimer?.cancel();
    _familyId = '';
  }

  // ---------- 对外: 数据流 ----------

  Stream<List<Ingredient>> watchAll() => _controller.stream;

  Future<List<Ingredient>> listAll() async =>
      _db.allIngredients(_familyId);

  // ---------- 写入 (先本地, 后尽力推送) ----------

  /// 语音/文本解析出的草稿批量入库; 同名同区已存在则数量累加
  Future<List<Ingredient>> addDrafts(
      List<IngredientDraft> drafts, String userName) async {
    final created = <Ingredient>[];
    for (final d in drafts) {
      final now = DateTime.now().toUtc();
      final existing =
          await _db.findByNameAndZone(_familyId, d.name, d.zone.dbValue);
      if (existing != null) {
        final merged = existing.copyWith(
          quantity: existing.quantity + d.quantity,
          updatedAt: now,
          addedByUserName: userName,
        );
        await _db.upsertIngredient(merged);
        await _enqueue('upsert', merged);
        created.add(merged);
      } else {
        final item = Ingredient(
          id: _uuid.v4(),
          familyId: _familyId,
          name: d.name,
          category: guessCategory(d.name),
          quantity: d.quantity,
          unit: d.unit,
          zone: d.zone,
          purchasedAt: now,
          shelfLifeDays: d.shelfLifeDays,
          addedByUserName: userName,
          updatedAt: now,
        );
        await _db.upsertIngredient(item);
        await _enqueue('upsert', item);
        created.add(item);
      }
    }
    _emit();
    unawaited(push()); // 尽量即时推; 失败自动进重试周期
    return created;
  }

  /// 消耗用量: 扣减后不足则整条删除
  Future<void> consume(String id, double amount) async {
    final row = await _db.getIngredient(id);
    if (row == null) return;
    final now = DateTime.now().toUtc();
    if (row.quantity - amount <= 0.0001) {
      await _db.deleteIngredient(id);
      await _enqueue('delete', row);
    } else {
      final updated = row.copyWith(quantity: row.quantity - amount, updatedAt: now);
      await _db.upsertIngredient(updated);
      await _enqueue('upsert', updated);
    }
    _emit();
    unawaited(push());
  }

  Future<void> remove(String id) async {
    final row = await _db.getIngredient(id);
    if (row == null) return;
    await _db.deleteIngredient(id);
    await _enqueue('delete', row);
    _emit();
    unawaited(push());
  }

  // ---------- 推送 & 冲突解决 ----------

  /// 把 outbox 逐条推向云端; 任一条网络失败立即停止 (保序), 等下次周期重试
  Future<void> push() async {
    if (_syncing || !activated) return;
    _syncing = true;
    try {
      for (final entry in await _db.pendingOutbox()) {
        if (entry.op == 'upsert') {
          final local =
              Ingredient.fromJson(jsonDecode(entry.payload) as Map<String, dynamic>);
          final remote = await _api.fetchOne(_familyId, local.id);
          if (remote != null && remote.updatedAt.isAfter(local.updatedAt)) {
            // 云端更新 → 云端胜: 回写本地并丢弃本地变更
            await _db.upsertIngredient(remote);
          } else {
            await _api.upsert(local); // 本地胜 (或云端无此行)
          }
        } else {
          // delete: 云端若仍有该行 (他人更新过) → 云胜, 回写本地放弃删除;
          // 云端本就没有 → 删除已达成, 无需再调接口
          final remote = await _api.fetchOne(_familyId, entry.entityId);
          if (remote != null) {
            await _db.upsertIngredient(remote);
          }
        }
        await _db.removeOutbox(entry.id);
      }
      _emit();
    } catch (e) {
      onSyncError?.call('同步未完成, 将在稍后重试: $e');
    } finally {
      _syncing = false;
    }
  }

  /// 从云端拉取全量 → 合入本地 (带 LWW 守卫)
  Future<void> pull() async {
    if (!activated) return;
    try {
      final remoteRows = await _api.fetchAll(_familyId);
      final pending = await _db.pendingOutbox();
      final pendingIds = pending.map((e) => e.entityId).toSet();
      for (final r in remoteRows) {
        final local = await _db.getIngredient(r.id);
        // 本地有未推送变更且本地更新 → 保留本地, 交给 push 裁决
        if (local != null &&
            pendingIds.contains(r.id) &&
            local.updatedAt.isAfter(r.updatedAt)) {
          continue;
        }
        if (local == null || !r.updatedAt.isBefore(local.updatedAt)) {
          await _db.upsertIngredient(r);
        }
      }
      // 删除传播: 服务器上已消失、且本地无未推送变动的行 → 从本地移除
      final remoteIds = remoteRows.map((r) => r.id).toSet();
      for (final localRow in await _db.allIngredients(_familyId)) {
        if (!remoteIds.contains(localRow.id) &&
            !pendingIds.contains(localRow.id)) {
          await _db.deleteIngredient(localRow.id);
        }
      }
      await _db.setMeta(
          'last_pull_at', DateTime.now().toUtc().toIso8601String());
      _emit();
    } catch (e) {
      onSyncError?.call('拉取失败: $e');
    }
  }

  /// 手动触发一次完整同步 (设置页按钮)
  Future<void> syncNow() async {
    await push();
    await pull();
  }

  // ---------- 内部 ----------

  Future<void> _enqueue(String op, Ingredient item) =>
      _db.enqueueOutbox(item.id, op, jsonEncode(item.toJson()));

  /// Realtime 行集合到达 → 逐行合入本地 (LWW: 比本地新才覆盖)。
  /// 注: supabase stream 的 DELETE 事件会让该行从流中消失, 无法靠流做删除传播,
  /// 删除传播由 pull() 的全量比对兜底 (30s 周期内收敛)。
  Future<void> _applyRemoteEvent(List<Ingredient> remoteItems) async {
    try {
      var changed = false;
      for (final remote in remoteItems) {
        final local = await _db.getIngredient(remote.id);
        if (local == null || !remote.updatedAt.isBefore(local.updatedAt)) {
          await _db.upsertIngredient(remote);
          changed = true;
        }
      }
      if (changed) _emit();
    } catch (e) {
      onSyncError?.call('实时数据合入失败: $e');
    }
  }

  /// 防抖 300ms 后向 UI 发全量列表
  void _emit() {
    _emitTimer?.cancel();
    _emitTimer = Timer(const Duration(milliseconds: 300), () async {
      if (!_controller.isClosed) {
        _controller.add(await listAll());
      }
    });
  }

  Future<void> dispose() async {
    _retryTimer?.cancel();
    _emitTimer?.cancel();
    await _remoteSub?.cancel();
    await _controller.close();
  }
}
