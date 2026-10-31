import 'package:flutter/material.dart';

import 'data/authenticated_api_client.dart';
import 'data/health_repository.dart';
import 'data/instrument_repository.dart';
import 'data/market_data_repository.dart';
import 'data/operator_session.dart';
import 'presentation/appearance_switcher.dart';
import 'presentation/instruments_page.dart';
import 'presentation/market_data_page.dart';
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

  AuthenticatedApiClient get _api => AuthenticatedApiClient(session: widget.session);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'AiTradingSystem',
      debugShowCheckedModeBanner: false,
      themeMode: _themeMode,
      theme: buildTheme(_style, Brightness.light),
      darkTheme: buildTheme(_style, Brightness.dark),
      initialRoute: widget.initialRoute,
      builder: (context, child) => Stack(
        children: [
          Positioned.fill(child: child ?? const SizedBox.shrink()),
          Positioned(
            top: 0,
            right: 0,
            child: AppearanceSwitcher(
              designStyle: _style,
              themeMode: _themeMode,
              onDesignStyleChanged: _setDesignStyle,
              onThemeModeChanged: _setThemeMode,
            ),
          ),
        ],
      ),
      routes: {
        '/': (_) => _overviewPage(),
        '/instruments': (_) => InstrumentsPage(repository: HttpInstrumentRepository(_api)),
        '/market': (_) => MarketDataPage(repository: HttpMarketDataRepository(_api)),
        '/market-data': (_) => MarketDataPage(repository: HttpMarketDataRepository(_api)),
        '/signals': (_) => const WorkspacePage.signals(),
        '/portfolio': (_) => const WorkspacePage.portfolio(),
        '/risk': (_) => const WorkspacePage.risk(),
        '/operations': (_) => _healthPage(),
        '/operations/health': (_) => _healthPage(),
      },
      onUnknownRoute: (_) => MaterialPageRoute<void>(builder: (_) => _overviewPage()),
    );
  }

  void _setDesignStyle(DesignStyle value) {
    if (_style == value) return;
    setState(() => _style = value);
  }

  void _setThemeMode(ThemeMode value) {
    if (_themeMode == value) return;
    setState(() => _themeMode = value);
  }

  Widget _overviewPage() => OverviewPage(
        session: widget.session,
        dashboardRepository: HttpDashboardRepository(session: widget.session),
      );

  Widget _healthPage() => OperationsHealthPage(
        key: const ValueKey('operations-health-page'),
        repository: widget.repositoryFactory(),
        session: widget.session,
        designStyle: _style,
        themeMode: _themeMode,
        onDesignStyleChanged: _setDesignStyle,
        onThemeModeChanged: _setThemeMode,
      );
}
