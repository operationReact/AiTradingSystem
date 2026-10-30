import 'package:flutter/material.dart';

import 'data/health_repository.dart';
import 'data/operator_session.dart';
import 'presentation/operations_health_page.dart';
import 'presentation/overview_page.dart';
import 'theme/app_theme.dart';

class TradingApp extends StatefulWidget {
  const TradingApp({
    required this.repositoryFactory,
    required this.session,
    this.initialRoute = '/',
    super.key,
  });

  final HealthRepository Function() repositoryFactory;
  final OperatorSession session;
  final String initialRoute;

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
      initialRoute: widget.initialRoute,
      routes: {
        '/': (_) => const OverviewPage(),
        '/market': (_) => const WorkspacePage.market(),
        '/signals': (_) => const WorkspacePage.signals(),
        '/portfolio': (_) => const WorkspacePage.portfolio(),
        '/risk': (_) => const WorkspacePage.risk(),
        '/operations': (_) => _healthPage(),
        '/operations/health': (_) => _healthPage(),
      },
      onUnknownRoute: (_) => MaterialPageRoute<void>(
        builder: (_) => const OverviewPage(),
      ),
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
