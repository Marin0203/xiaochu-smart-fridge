import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../../data/app_services.dart';

/// 认证 + 家庭状态机 (MVVM 的 VM 之一)
/// checking → signedOut / signedInNoFamily → ready
/// 注意: 不用 AuthState 这个名字 —— supabase_flutter 也导出同名类型, 会冲突
enum AuthFlowState { checking, signedOut, signedInNoFamily, ready }

class AuthViewModel extends ChangeNotifier {
  final SupabaseClient _sb = Supabase.instance.client;
  final AppServices services;

  AuthFlowState state = AuthFlowState.checking;
  String? errorMessage;

  String? familyId;
  String? familyName;
  String? inviteCode;
  String? displayName;

  StreamSubscription<AuthState>? _sub; // 注意: 此处 AuthState 指 supabase_flutter 导出的类型

  AuthViewModel(this.services) {
    _sub = _sb.auth.onAuthStateChange.listen((_) => _refresh());
    _refresh();
  }

  Future<void> _refresh() async {
    final session = _sb.auth.currentSession;
    if (session == null) {
      _set(AuthFlowState.signedOut);
      return;
    }
    try {
      final fam = await _sb.rpc('get_my_family');
      if (fam == null) {
        _set(AuthFlowState.signedInNoFamily);
        return;
      }
      final m = (fam as Map).map((k, v) => MapEntry(k.toString(), v));
      familyId = (m['family_id'] ?? '').toString();
      familyName = (m['name'] ?? '我的家庭').toString();
      inviteCode = (m['invite_code'] ?? '').toString();
      displayName =
          session.user.userMetadata?['display_name']?.toString() ??
              session.user.email ??
              '家庭成员';
      await services.activateFamily(familyId!);
      errorMessage = null;
      _set(AuthFlowState.ready);
    } catch (e) {
      errorMessage = '加载家庭信息失败: $e';
      _set(AuthFlowState.signedInNoFamily);
    }
  }

  Future<void> signIn(String email, String password) async {
    errorMessage = null;
    notifyListeners();
    try {
      await _sb.auth.signInWithPassword(email: email, password: password);
      await _refresh();
    } on AuthException catch (e) {
      errorMessage = '登录失败: ${e.message}';
    } catch (e) {
      errorMessage = '登录失败: $e';
    }
    notifyListeners();
  }

  /// 注册并创建第一个家庭 (后续家庭成员用邀请码加入)
  Future<void> signUpAndCreateFamily({
    required String email,
    required String password,
    required String name,
    required String familyName,
  }) async {
    errorMessage = null;
    notifyListeners();
    try {
      final res = await _sb.auth.signUp(
        email: email,
        password: password,
        data: {'display_name': name},
      );
      if (res.session == null) {
        // 项目开启了邮箱验证: 用户需验证后才能登录
        errorMessage = '注册成功，请完成邮箱验证后登录';
      } else {
        await _sb
            .rpc('create_family', params: {'family_name': familyName, 'display_name': name});
        await _refresh();
      }
    } on AuthException catch (e) {
      errorMessage = '注册失败: ${e.message}';
    } catch (e) {
      errorMessage = '注册失败: $e';
    }
    notifyListeners();
  }

  /// 已登录但无家庭时: 直接创建 (注册时已建家庭则不会走到这里)
  Future<void> createFamily(String name) async {
    errorMessage = null;
    notifyListeners();
    try {
      await _sb.rpc('create_family', params: {
        'family_name': name,
        'display_name': displayName ?? '',
      });
      await _refresh();
    } catch (e) {
      errorMessage = '创建失败: ${(e as dynamic).message ?? e}';
    }
    notifyListeners();
  }

  Future<void> joinFamily(String code) async {
    errorMessage = null;
    notifyListeners();
    try {
      await _sb.rpc('join_family', params: {'code': code});
      await _refresh();
    } catch (e) {
      errorMessage = '加入失败: ${(e as dynamic).message ?? e}';
    }
    notifyListeners();
  }

  Future<void> signOut() async {
    await services.sync.deactivate();
    await _sb.auth.signOut();
  }

  void _set(AuthFlowState s) {
    state = s;
    notifyListeners();
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }
}
