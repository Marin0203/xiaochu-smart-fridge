import 'dart:async';

import 'package:supabase_flutter/supabase_flutter.dart';

import '../../domain/models/ingredient.dart';

/// Supabase 远程数据访问。
/// 只被 SyncService 使用; 所有请求都带用户会话 → Postgres RLS 自动过滤到本家庭。
class SupabaseApi {
  final SupabaseClient _sb;
  SupabaseApi(this._sb);

  Future<List<Ingredient>> fetchAll(String familyId) async {
    final rows = await _sb
        .from('ingredients')
        .select()
        .eq('family_id', familyId)
        .order('updated_at', ascending: false);
    return rows.map(Ingredient.fromJson).toList();
  }

  Future<Ingredient?> fetchOne(String familyId, String id) async {
    final row = await _sb
        .from('ingredients')
        .select()
        .eq('family_id', familyId)
        .eq('id', id)
        .maybeSingle();
    return row == null ? null : Ingredient.fromJson(row);
  }

  Future<void> upsert(Ingredient i) async {
    // updated_at 由客户端写入 (LWW 唯一权威), 服务端不覆盖
    await _sb.from('ingredients').upsert(i.toJson());
  }

  Future<void> delete(String familyId, String id) async {
    await _sb
        .from('ingredients')
        .delete()
        .eq('family_id', familyId)
        .eq('id', id);
  }

  /// 实时订阅本家庭库存变更 (Postgres Changes + RLS) —— 需迁移里启用 publication。
  /// 注意: supabase 的 stream 元素是「变更触发的行集合」(List<Map>), 不是单行。
  Stream<List<Ingredient>> watch(String familyId) => _sb
      .from('ingredients')
      .stream(primaryKey: ['id'])
      .eq('family_id', familyId)
      .map((rows) => rows.map(Ingredient.fromJson).toList());
}
