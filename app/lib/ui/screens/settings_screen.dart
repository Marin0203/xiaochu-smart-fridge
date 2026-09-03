import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../../core/config.dart';
import '../auth/auth_viewmodel.dart';
import '../scope.dart';

/// 设置页: 家庭/邀请码、手动同步、账号、关于
class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthViewModel>();
    final services = ServicesScope.of(context);
    final email = Supabase.instance.client.auth.currentSession?.user.email ?? '';

    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: ListView(
        padding: const EdgeInsets.symmetric(vertical: 8),
        children: [
          Card(
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.family_restroom),
                  title: Text(auth.familyName ?? '我的家庭'),
                  subtitle: Text('邀请码: ${auth.inviteCode ?? '-'}'),
                ),
                const Divider(height: 1),
                ListTile(
                  leading: const Icon(Icons.copy),
                  title: const Text('复制邀请码'),
                  onTap: () async {
                    await Clipboard.setData(
                        ClipboardData(text: auth.inviteCode ?? ''));
                    if (context.mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('邀请码已复制，发给家人即可加入')));
                    }
                  },
                ),
              ],
            ),
          ),
          Card(
            child: ListTile(
              leading: const Icon(Icons.sync),
              title: const Text('立即同步'),
              subtitle: const Text('手动触发一次 拉取+推送 (通常 30 秒自动同步)'),
              onTap: () async {
                final repo = services.repository;
                if (repo == null) return;
                ScaffoldMessenger.of(context)
                    .showSnackBar(const SnackBar(content: Text('正在同步…')));
                await repo.syncNow();
                if (context.mounted) {
                  ScaffoldMessenger.of(context)
                      .showSnackBar(const SnackBar(content: Text('同步完成 ✓')));
                }
              },
            ),
          ),
          Card(
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.person),
                  title: Text(auth.displayName ?? '家庭成员'),
                  subtitle: Text(email),
                ),
                const Divider(height: 1),
                ListTile(
                  leading: const Icon(Icons.logout),
                  title: const Text('退出登录'),
                  onTap: () async {
                    final ok = await showDialog<bool>(
                      context: context,
                      builder: (ctx) => AlertDialog(
                        title: const Text('退出登录'),
                        content: const Text('退出后本机仍保留库存缓存，重新登录即可同步。'),
                        actions: [
                          TextButton(
                              onPressed: () => Navigator.pop(ctx, false),
                              child: const Text('取消')),
                          FilledButton(
                              onPressed: () => Navigator.pop(ctx, true),
                              child: const Text('退出')),
                        ],
                      ),
                    );
                    if (ok == true) await auth.signOut();
                  },
                ),
              ],
            ),
          ),
          Card(
            child: ListTile(
              leading: const Icon(Icons.info_outline),
              title: const Text('AI 模式'),
              subtitle: Text(AppConfig.isDirectMode
                  ? '客户端直连 OpenAI 兼容 API (本地/自部署)'
                  : 'Supabase Edge Function 代理 (密钥在服务端)'),
            ),
          ),
        ],
      ),
    );
  }
}
