import 'package:flutter/material.dart';

import 'data/authenticated_api_client.dart';
import 'data/backtest_repository.dart';
import 'data/health_repository.dart';
import 'data/instrument_repository.dart';
import 'data/market_data_repository.dart';
import 'data/operator_session.dart';
import 'data/operations_evidence_repository.dart';
import 'data/paper_trading_repository.dart';
import 'data/research_repository.dart';
import 'data/risk_approval_repository.dart';
import 'data/scenario_evidence_repository.dart';
import 'presentation/appearance_switcher.dart';
import 'presentation/backtests_page.dart';
import 'presentation/instruments_page.dart';
import 'presentation/market_data_page.dart';
import 'presentation/operations_evidence_page.dart';
import 'presentation/operations_health_page.dart';
import 'presentation/overview_page.dart';
import 'presentation/paper_orders_page.dart';
import 'presentation/paper_portfolio_page.dart';
import 'presentation/research_page.dart';
import 'presentation/risk_approvals_page.dart';
import 'presentation/scenario_lookup_page.dart';
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
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();
  DesignStyle _style = DesignStyle.neonFuturisticAi;
  ThemeMode _themeMode = ThemeMode.system;

  AuthenticatedApiClient get _api => AuthenticatedApiClient(session: widget.session);
  HttpOperationsEvidenceRepository get _operationsEvidence => HttpOperationsEvidenceRepository(_api);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: _navigatorKey,
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
          Positioned(
            left: 0,
            bottom: 0,
            child: SafeArea(
              minimum: const EdgeInsets.all(12),
              child: Material(
                elevation: 3,
                borderRadius: BorderRadius.circular(16),
                child: IconButton(
                  key: const ValueKey('global-workspace-navigation'),
                  tooltip: 'Open workspace navigation',
                  onPressed: _openWorkspaceNavigation,
                  icon: const Icon(Icons.apps_rounded),
                ),
              ),
            ),
          ),
        ],
      ),
      routes: {
        '/': (_) => _overviewPage(),
        '/instruments': (_) => InstrumentsPage(repository: HttpInstrumentRepository(_api)),
        '/market': (_) => MarketDataPage(repository: HttpMarketDataRepository(_api)),
        '/market-data': (_) => MarketDataPage(repository: HttpMarketDataRepository(_api)),
        '/backtests': (_) => BacktestsPage(repository: HttpBacktestRepository(_api)),
        '/signals': (_) => ResearchPage(repository: HttpResearchRepository(_api)),
        '/research': (_) => ResearchPage(repository: HttpResearchRepository(_api)),
        '/scenarios': (_) => ScenarioLookupPage(repository: HttpScenarioEvidenceRepository(_api)),
        '/portfolio': (_) => PaperPortfolioPage(repository: HttpPaperTradingRepository(_api)),
        '/orders': (_) => PaperOrdersPage(repository: HttpPaperTradingRepository(_api)),
        '/paper-orders': (_) => PaperOrdersPage(repository: HttpPaperTradingRepository(_api)),
        '/risk': (_) => RiskApprovalsPage(repository: HttpRiskApprovalRepository(_api)),
        '/approvals': (_) => RiskApprovalsPage(repository: HttpRiskApprovalRepository(_api)),
        '/news': (_) => OperationsEvidencePage(repository: _operationsEvidence, section: OperationsEvidenceSection.news),
        '/notifications': (_) => OperationsEvidencePage(repository: _operationsEvidence, section: OperationsEvidenceSection.notifications),
        '/audit': (_) => OperationsEvidencePage(repository: _operationsEvidence, section: OperationsEvidenceSection.audit),
        '/settings': (_) => OperationsEvidencePage(repository: _operationsEvidence, section: OperationsEvidenceSection.settings),
        '/operations': (_) => _healthPage(),
        '/operations/health': (_) => _healthPage(),
      },
      onUnknownRoute: (_) => MaterialPageRoute<void>(builder: (_) => _overviewPage()),
    );
  }

  Future<void> _openWorkspaceNavigation() async {
    final navContext = _navigatorKey.currentContext;
    if (navContext == null) return;
    final route = await showModalBottomSheet<String>(
      context: navContext,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (context) => SafeArea(
        child: ListView(
          key: const ValueKey('workspace-navigation-sheet'),
          shrinkWrap: true,
          padding: const EdgeInsets.fromLTRB(12, 0, 12, 24),
          children: const [
            _WorkspaceDestination(route: '/', icon: Icons.home_outlined, label: 'Overview'),
            _WorkspaceDestination(route: '/instruments', icon: Icons.list_alt_outlined, label: 'Instruments'),
            _WorkspaceDestination(route: '/market', icon: Icons.candlestick_chart_outlined, label: 'Market data'),
            _WorkspaceDestination(route: '/research', icon: Icons.radar_outlined, label: 'Research'),
            _WorkspaceDestination(route: '/scenarios', icon: Icons.analytics_outlined, label: 'Scenario & regime evidence'),
            _WorkspaceDestination(route: '/backtests', icon: Icons.science_outlined, label: 'PAPER backtests'),
            _WorkspaceDestination(route: '/portfolio', icon: Icons.account_balance_wallet_outlined, label: 'PAPER portfolio'),
            _WorkspaceDestination(route: '/orders', icon: Icons.receipt_long_outlined, label: 'PAPER orders'),
            _WorkspaceDestination(route: '/risk', icon: Icons.shield_outlined, label: 'Risk & approvals'),
            _WorkspaceDestination(route: '/news', icon: Icons.newspaper_outlined, label: 'News evidence'),
            _WorkspaceDestination(route: '/notifications', icon: Icons.notifications_outlined, label: 'Notifications'),
            _WorkspaceDestination(route: '/audit', icon: Icons.history_outlined, label: 'Audit trail'),
            _WorkspaceDestination(route: '/settings', icon: Icons.settings_outlined, label: 'Trading settings'),
            _WorkspaceDestination(route: '/operations/health', icon: Icons.health_and_safety_outlined, label: 'System health'),
          ],
        ),
      ),
    );
    if (route == null || !mounted) return;
    _navigatorKey.currentState?.pushNamed(route);
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

class _WorkspaceDestination extends StatelessWidget {
  const _WorkspaceDestination({required this.route, required this.icon, required this.label});

  final String route;
  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) => ListTile(
        key: ValueKey('workspace-nav-$route'),
        leading: Icon(icon),
        title: Text(label),
        minVerticalPadding: 12,
        onTap: () => Navigator.of(context).pop(route),
      );
}
