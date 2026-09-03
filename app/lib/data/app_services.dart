import '../../domain/models/ingredient_draft.dart';
import '../../domain/models/ingredient.dart';
import '../ai/llm_client.dart';
import '../data/local/fridge_local_db.dart';
import '../data/remote/supabase_api.dart';
import '../data/sync/sync_service.dart';

/// 仓库门面: UI 只依赖它, 不接触数据源细节 (可替换性: 换 Firebase 只动这一层)
class IngredientRepository {
  final SyncService sync;
  IngredientRepository(this.sync);

  Stream<List<Ingredient>> watchAll() => sync.watchAll();
  Future<List<Ingredient>> listAll() => sync.listAll();
  Future<List<Ingredient>> addFromDrafts(List<IngredientDraft> drafts, String userName) =>
      sync.addDrafts(drafts, userName);
  Future<void> consume(String id, {double amount = 1}) => sync.consume(id, amount);
  Future<void> remove(String id) => sync.remove(id);
  Future<void> syncNow() => sync.syncNow();
}

/// 应用服务容器: 启动时组装一次, 经 ServicesScope 提供给全 App
class AppServices {
  final FridgeLocalDb db;
  final SupabaseApi api;
  final SyncService sync;
  final AiService ai;
  IngredientRepository? _repository;

  AppServices({
    required this.db,
    required this.api,
    required this.sync,
    required this.ai,
  });

  /// 家庭确定后激活同步并暴露仓库 (ready 状态前为 null, UI 不可用)
  Future<void> activateFamily(String familyId) async {
    await sync.activate(familyId);
    _repository = IngredientRepository(sync);
  }

  IngredientRepository? get repository => _repository;
}
