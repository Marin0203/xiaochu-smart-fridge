import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../auth/auth_viewmodel.dart';

/// 已登录但尚未加入/创建家庭 → 从这里选择
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final _name = TextEditingController(text: '我的家庭');
  final _code = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _name.dispose();
    _code.dispose();
    super.dispose();
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() => _busy = true);
    await action();
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthViewModel>();
    return Scaffold(
      appBar: AppBar(title: const Text('加入家庭')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const SizedBox(height: 8),
          const Text('你还需要加入一个家庭空间，才能与家人共享冰箱：',
              style: TextStyle(color: Colors.grey)),
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text('新建家庭', style: TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _name,
                    decoration: const InputDecoration(labelText: '家庭名称'),
                  ),
                  const SizedBox(height: 12),
                  FilledButton(
                    onPressed: _busy
                        ? null
                        : () => _run(() => auth.createFamily(_name.text.trim())),
                    child: const Text('创建'),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text('加入已有家庭', style: TextStyle(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 4),
                  Text('向家长索取 6 位邀请码',
                      style: TextStyle(fontSize: 12, color: Colors.grey.shade500)),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _code,
                    decoration: const InputDecoration(
                        labelText: '邀请码',
                        hintText: '例如 A1B2C3',
                        textCapitalization: TextCapitalization.characters),
                  ),
                  const SizedBox(height: 12),
                  FilledButton(
                    onPressed: _busy
                        ? null
                        : () => _run(() => auth.joinFamily(_code.text.trim())),
                    child: const Text('加入'),
                  ),
                ],
              ),
            ),
          ),
          if (auth.errorMessage != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(auth.errorMessage!,
                  style: TextStyle(color: Colors.red.shade700, fontSize: 13)),
            ),
        ],
      ),
    );
  }
}
