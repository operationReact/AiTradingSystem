import 'package:flutter/material.dart';

import 'data/health_repository.dart';
import 'data/operator_session.dart';
import 'presentation/operations_health_page.dart';
import 'theme/app_theme.dart';

class TradingApp extends StatefulWidget {
  const TradingApp({
    required this.repositoryFactory,
    required this.session,
    super.key,
  });

  final HealthRepository Function() repositoryFactory;
  final OperatorSession session;

  @override
  State<TradingApp> createState() => _TradingAppState();
}

class _TradingAppState extends State<TradingApp> {
  DesignStyle _style = DesignStyle.neonFuturisticAi;
  ThemeMode _themeMode = ThemeMode.system;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'AiTradingSystem',
      debugShowCheckedModeBanner: false,
      themeMode: _themeMode,
      theme: buildTheme(_style, Brightness.light),
      darkTheme: buildTheme(_style, Brightness.dark),
      initialRoute: '/operations/health',
      routes: {
        '/': (_) => _healthPage(),
        '/operations': (_) => _healthPage(),
        '/operations/health': (_) => _healthPage(),
      },
    );
  }

  Widget _healthPage() => OperationsHealthPage(
        key: const ValueKey('operations-health-page'),
        repository: widget.repositoryFactory(),
        session: widget.session,
        designStyle: _style,
        themeMode: _themeMode,
        onDesignStyleChanged: (value) => setState(() => _style = value),
        onThemeModeChanged: (value) => setState(() => _themeMode = value),
      );
}
