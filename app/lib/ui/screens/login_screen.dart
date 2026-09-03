import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../auth/auth_viewmodel.dart';

/// 登录 / 注册页 (邮箱+密码; 注册即创建首个家庭)
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _displayName = TextEditingController();
  final _familyName = TextEditingController(text: '我的家庭');
  bool _registerMode = false;
  bool _loading = false;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    _displayName.dispose();
    _familyName.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final email = _email.text.trim();
    final password = _password.text;
    if (email.isEmpty || password.isEmpty) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('请输入邮箱和密码')));
      return;
    }
    setState(() => _loading = true);
    final auth = context.read<AuthViewModel>();
    if (_registerMode) {
      await auth.signUpAndCreateFamily(
        email: email,
        password: password,
        name: _displayName.text.trim().isEmpty ? '我的' : _displayName.text.trim(),
        familyName: _familyName.text.trim().isEmpty ? '我的家庭' : _familyName.text.trim(),
      );
    } else {
      await auth.signIn(email, password);
    }
    if (mounted) setState(() => _loading = false);
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthViewModel>();
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(28),
          children: [
            const SizedBox(height: 40),
            Icon(Icons.kitchen,
                size: 72, color: Theme.of(context).colorScheme.primary),
            const SizedBox(height: 12),
            const Center(
              child: Text('小厨',
                  style: TextStyle(fontSize: 26, fontWeight: FontWeight.w700)),
            ),
            const SizedBox(height: 6),
            const Center(
              child: Text('家庭共享库存 · 保质期预警 · AI 菜谱', style: TextStyle(color: Colors.grey)),
            ),
            const SizedBox(height: 32),
            if (auth.errorMessage != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.red.shade50,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(auth.errorMessage!,
                    style: TextStyle(color: Colors.red.shade700, fontSize: 13)),
              ),
              const SizedBox(height: 16),
            ],
            TextField(
              controller: _email,
              keyboardType: TextInputType.emailAddress,
              decoration: const InputDecoration(
                  labelText: '邮箱', prefixIcon: Icon(Icons.email_outlined)),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _password,
              obscureText: true,
              decoration: const InputDecoration(
                  labelText: '密码', prefixIcon: Icon(Icons.lock_outline)),
            ),
            if (_registerMode) ...[
              const SizedBox(height: 12),
              TextField(
                controller: _displayName,
                decoration: const InputDecoration(
                    labelText: '你的昵称', prefixIcon: Icon(Icons.person_outline)),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _familyName,
                decoration: const InputDecoration(
                    labelText: '家庭名称 (第一个成员自动成为家长)',
                    prefixIcon: Icon(Icons.family_restroom)),
              ),
            ],
            const SizedBox(height: 24),
            FilledButton(
              onPressed: _loading ? null : _submit,
              child: _loading
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Colors.white))
                  : Text(_registerMode ? '注册并创建家庭' : '登录'),
            ),
            const SizedBox(height: 8),
            Center(
              child: TextButton(
                onPressed: () =>
                    setState(() => _registerMode = !_registerMode),
                child: Text(_registerMode ? '已有账号? 去登录' : '没有账号? 注册新家庭'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
