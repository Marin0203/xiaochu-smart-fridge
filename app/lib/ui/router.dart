import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import '../core/config.dart';
import '../data/app_services.dart';
import '../data/local/fridge_local_db.dart';
import '../data/remote/supabase_api.dart';
import '../data/sync/sync_service.dart';
import '../ai/llm_client.dart';
import '../domain/models/recipes.dart';
import 'auth/auth_viewmodel.dart';
import 'scope.dart';
import 'screens/login_screen.dart';
import 'screens/onboarding_screen.dart';
import 'screens/splash_screen.dart';
import 'screens/inventory_screen.dart';
import 'screens/recipe_screen.dart';
import 'screens/recipe_detail_screen.dart';
import 'screens/settings_screen.dart';
import 'theme/app_theme.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 配置缺失时给出明确引导 (不闪退)
  if (!AppConfig.isConfigured) {
    runApp(const BootstrapErrorApp());
    return;
  }

  await Supabase.initialize(
    url: AppConfig.supabaseUrl,
    anonKey: AppConfig.supabaseAnonKey,
  );

  final db = await FridgeLocalDb.open();
  final api = SupabaseApi(Supabase.instance.client);
  final services = AppServices(
    db: db,
    api: api,
    sync: SyncService(db, api),
    ai: createAiService(),
  );

  runApp(FridgeApp(services: services));
}

class FridgeApp extends StatefulWidget {
  final AppServices services;
  const FridgeApp({super.key, required this.services});

  @override
  State<FridgeApp> createState() => _FridgeAppState();
}

class _FridgeAppState extends State<FridgeApp> {
  late final AuthViewModel _auth;
  late final GoRouter _router;

  @override
  void initState() {
    super.initState();
    _auth = AuthViewModel(widget.services);
    _router = _buildRouter(_auth);
  }

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [ChangeNotifierProvider.value(value: _auth)],
      child: MaterialApp.router(
        title: '小厨',
        debugShowCheckedModeBanner: false,
        theme: AppTheme.light(),
        routerConfig: _router,
        builder: (context, child) =>
            ServicesScope(services: widget.services, child: child!),
      ),
    );
  }
}

/// 认证状态 → 路由守卫
GoRouter _buildRouter(AuthViewModel auth) => GoRouter(
      initialLocation: '/splash',
      refreshListenable: auth,
      redirect: (context, state) => switch (auth.state) {
        AuthFlowState.checking => '/splash',
        AuthFlowState.signedOut =>
          state.matchedLocation == '/login' ||
                  state.matchedLocation == '/splash'
              ? null
              : '/login',
        AuthFlowState.signedInNoFamily =>
          state.matchedLocation == '/onboarding' ? null : '/onboarding',
        AuthFlowState.ready =>
          const ['/login', '/onboarding', '/splash']
                  .contains(state.matchedLocation)
              ? '/inventory'
              : null,
      },
      routes: [
        GoRoute(path: '/splash', builder: (_, __) => const SplashScreen()),
        GoRoute(path: '/login', builder: (_, __) => const LoginScreen()),
        GoRoute(path: '/onboarding', builder: (_, __) => const OnboardingScreen()),
        StatefulShellRoute.indexedStack(
          builder: (context, state, navigationShell) =>
              AppShell(navigationShell: navigationShell),
          branches: [
            StatefulShellBranch(
              routes: [
                GoRoute(
                    path: '/inventory',
                    builder: (_, __) => const InventoryScreen()),
              ],
            ),
            StatefulShellBranch(
              routes: [
                GoRoute(
                  path: '/recipes',
                  builder: (_, __) => const RecipeScreen(),
                  routes: [
                    GoRoute(
                      path: 'detail',
                      builder: (_, s) =>
                          RecipeDetailScreen(recipe: s.extra as Recipe),
                    ),
                  ],
                ),
              ],
            ),
            StatefulShellBranch(
              routes: [
                GoRoute(
                    path: '/settings',
                    builder: (_, __) => const SettingsScreen()),
              ],
            ),
          ],
        ),
      ],
    );

/// 底部导航壳 (库存 / AI 菜谱 / 设置)
class AppShell extends StatelessWidget {
  final StatefulNavigationShell navigationShell;
  const AppShell({super.key, required this.navigationShell});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: navigationShell,
      bottomNavigationBar: NavigationBar(
        selectedIndex: navigationShell.currentIndex,
        onDestinationSelected: (i) => navigationShell.goBranch(
          i,
          initialLocation: i == navigationShell.currentIndex,
        ),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.kitchen_outlined),
            selectedIcon: Icon(Icons.kitchen),
            label: '库存',
          ),
          NavigationDestination(
            icon: Icon(Icons.restaurant_menu_outlined),
            selectedIcon: Icon(Icons.restaurant_menu),
            label: 'AI 菜谱',
          ),
          NavigationDestination(
            icon: Icon(Icons.settings_outlined),
            selectedIcon: Icon(Icons.settings),
            label: '设置',
          ),
        ],
      ),
    );
  }
}

/// 配置缺失引导页
class BootstrapErrorApp extends StatelessWidget {
  const BootstrapErrorApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text(
              '缺少配置\n\n请通过 --dart-define-from-file=secrets.json 提供 '
              'SUPABASE_URL 与 SUPABASE_ANON_KEY (见 README)',
              textAlign: TextAlign.center,
            ),
          ),
        ),
      ),
    );
  }
}
