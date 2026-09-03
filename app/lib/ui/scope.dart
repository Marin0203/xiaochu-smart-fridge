import 'package:flutter/widgets.dart';

import '../data/app_services.dart';

/// 全局服务作用域: 组装好的 AppServices 一次注入, 全树可取。
class ServicesScope extends InheritedWidget {
  final AppServices services;

  const ServicesScope({super.key, required this.services, required super.child});

  static AppServices of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<ServicesScope>()!.services;

  @override
  bool updateShouldNotify(ServicesScope oldWidget) =>
      services != oldWidget.services;
}
