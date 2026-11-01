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
import 'features/trading_control/data/trading_control_repository.dart';
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
import 'presentation/settings_page.dart';
import 'presentation/sign_in_page.dart';
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
  late final NavigatorObserver _routeObserver;
  DesignStyle _style = DesignStyle.neonFuturisticAi;
  ThemeMode _themeMode = ThemeMode.dark;
  String _selectedRoute = '/';
  String? _pendingObservedRoute;
  bool _routeSyncScheduled = false;

  AuthenticatedApiClient get _api => AuthenticatedApiClient(session: widget.session);
  HttpOperationsEvidenceRepository get _operationsEvidence => HttpOperationsEvidenceRepository(_api);

  @override
  void initState() {
    super.initState();
    _selectedRoute = widget.initialRoute;
    _routeObserver = _ProductRouteObserver(_handleRouteChanged);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: _navigatorKey,
      navigatorObservers: [_routeObserver],
      title: 'AI Trading System',
      debugShowCheckedModeBanner: false,
      themeMode: _themeMode,
      theme: buildTheme(_style, Brightness.light),
      darkTheme: buildTheme(_style, Brightness.dark),
      initialRoute: widget.initialRoute,
      builder: (context, child) => LayoutBuilder(
        builder: (context, constraints) {
          final content = child ?? const SizedBox.shrink();

          // Authentication is a dedicated customer journey. Product navigation
          // appears only after sign-in so the screen stays faithful to the
          // canonical mobile mockup and does not expose unrelated destinations.
          if (_selectedRoute == '/sign-in') {
            return content;
          }

          if (constraints.maxWidth >= 900) {
            return Row(
              children: [
                _DesktopProductRail(selectedRoute: _selectedRoute, onNavigate: _navigate),
                Expanded(child: content),
              ],
            );
          }
          return Stack(
            children: [
              Positioned.fill(bottom: 64, child: content),
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                child: _MobileProductNavigation(
                  selectedRoute: _selectedRoute,
                  onNavigate: _navigate,
                  onMore: _openMoreNavigation,
                ),
              ),
            ],
          );
        },
      ),
      routes: {
        '/': (_) => _overviewPage(),
        '/sign-in': (_) => const SignInPage(),
        '/instruments': (_) => InstrumentsPage(repository: HttpInstrumentRepository(_api)),
        '/market': (_) => MarketDataPage(repository: HttpMarketDataRepository(_api)),
        '/market-data': (_) => MarketDataPage(repository: HttpMarketDataRepository(_api)),
        '/backtests': (_) => BacktestsPage(repository: HttpBacktestRepository(_api)),
        '/signals': (_) => ResearchPage(repository: HttpResearchRepository(_api)),
        '/research': (_) => ResearchPage(repository: HttpResearchRepository(_api)),
        '/scenarios': (_) => ScenarioLookupPage(repository: HttpScenarioEvidenceRepository(_api)),
        '/portfolio': (_) => PaperPortfolioPage(repository: HttpPaperTradingRepository(_api)),
        '/orders': (_) => PaperOrdersPage(
              repository: HttpPaperTradingRepository(_api),
              trailingProtectionPreviewEnabled: true,
            ),
        '/paper-orders': (_) => PaperOrdersPage(
              repository: HttpPaperTradingRepository(_api),
              trailingProtectionPreviewEnabled: true,
            ),
        '/risk': (_) => RiskApprovalsPage(repository: HttpRiskApprovalRepository(_api)),
        '/approvals': (_) => RiskApprovalsPage(repository: HttpRiskApprovalRepository(_api)),
        '/news': (_) => OperationsEvidencePage(repository: _operationsEvidence, section: OperationsEvidenceSection.news),
        '/notifications': (_) => OperationsEvidencePage(repository: _operationsEvidence, section: OperationsEvidenceSection.notifications),
        '/audit': (_) => OperationsEvidencePage(repository: _operationsEvidence, section: OperationsEvidenceSection.audit),
        '/settings': (_) => SettingsPage(
              designStyle: _style,
              themeMode: _themeMode,
              onDesignStyleChanged: _setDesignStyle,
              onThemeModeChanged: _setThemeMode,
              tradingControlRepository: HttpTradingControlRepository(_api),
            ),
        '/operations': (_) => _healthPage(),
        '/operations/health': (_) => _healthPage(),
      },
      onUnknownRoute: (_) => MaterialPageRoute<void>(builder: (_) => _overviewPage()),
    );
  }

  void _handleRouteChanged(String route) {
    _pendingObservedRoute = route;
    if (_routeSyncScheduled) return;

    _routeSyncScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _routeSyncScheduled = false;
      final pendingRoute = _pendingObservedRoute;
      _pendingObservedRoute = null;
      if (!mounted || pendingRoute == null || _selectedRoute == pendingRoute) return;
      setState(() => _selectedRoute = pendingRoute);
    });
  }

  void _navigate(String route) {
    setState(() => _selectedRoute = route);
    _navigatorKey.currentState?.pushNamedAndRemoveUntil(route, (candidate) => false);
  }

  Future<void> _openMoreNavigation() async {
    final navContext = _navigatorKey.currentContext;
    if (navContext == null) return;
    final previousRoute = _selectedRoute;
    setState(() => _selectedRoute = '/more');
    final route = await showModalBottomSheet<String>(
      context: navContext,
      showDragHandle: true,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Theme.of(navContext).colorScheme.surfaceContainer,
      builder: (context) => const _MoreProductSheet(),
    );
    if (!mounted) return;
    if (route != null) {
      _navigate(route);
    } else {
      setState(() => _selectedRoute = previousRoute);
    }
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

class _ProductRouteObserver extends NavigatorObserver {
  _ProductRouteObserver(this.onRouteChanged);

  final ValueChanged<String> onRouteChanged;

  void _emit(Route<dynamic>? route) {
    final name = route?.settings.name;
    if (name != null) onRouteChanged(name);
  }

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) {
    super.didPush(route, previousRoute);
    _emit(route);
  }

  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) {
    super.didPop(route, previousRoute);
    _emit(previousRoute);
  }

  @override
  void didReplace({Route<dynamic>? newRoute, Route<dynamic>? oldRoute}) {
    super.didReplace(newRoute: newRoute, oldRoute: oldRoute);
    _emit(newRoute);
  }
}

class _MobileProductNavigation extends StatelessWidget {
  const _MobileProductNavigation({required this.selectedRoute, required this.onNavigate, required this.onMore});

  final String selectedRoute;
  final ValueChanged<String> onNavigate;
  final VoidCallback onMore;

  int get _index {
    if (selectedRoute == '/') return 0;
    if (selectedRoute == '/market' || selectedRoute == '/market-data' || selectedRoute == '/instruments') return 1;
    if (selectedRoute == '/research' || selectedRoute == '/signals' || selectedRoute == '/backtests' || selectedRoute == '/scenarios') return 2;
    if (selectedRoute == '/portfolio' || selectedRoute == '/orders' || selectedRoute == '/paper-orders') return 3;
    return 4;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.extension<TradingColors>()!;
    final items = <(IconData, IconData, String)>[
      (Icons.space_dashboard_outlined, Icons.space_dashboard_rounded, 'Home'),
      (Icons.candlestick_chart_outlined, Icons.candlestick_chart_rounded, 'Markets'),
      (Icons.auto_awesome_outlined, Icons.auto_awesome_rounded, 'AI'),
      (Icons.account_balance_wallet_outlined, Icons.account_balance_wallet_rounded, 'Portfolio'),
      (Icons.grid_view_outlined, Icons.grid_view_rounded, 'More'),
    ];

    return Material(
      key: const ValueKey('product-mobile-navigation'),
      color: const Color(0xF2071020),
      elevation: 0,
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: 64,
          child: Row(
            children: List.generate(items.length, (index) {
              final item = items[index];
              final selected = _index == index;
              return Expanded(
                child: InkWell(
                  key: ValueKey('mobile-nav-${index == 2 ? 'research' : item.$3.toLowerCase()}'),
                  onTap: () {
                    if (index == 4) {
                      onMore();
                    } else {
                      onNavigate(switch (index) {
                        0 => '/',
                        1 => '/market',
                        2 => '/research',
                        3 => '/portfolio',
                        _ => '/',
                      });
                    }
                  },
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 180),
                    margin: const EdgeInsets.symmetric(horizontal: 3, vertical: 5),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(16),
                      color: selected ? colors.neonCyan.withValues(alpha: 0.11) : Colors.transparent,
                      border: selected ? Border.all(color: colors.neonCyan.withValues(alpha: 0.30)) : null,
                      boxShadow: selected ? [BoxShadow(color: colors.neonCyan.withValues(alpha: 0.10), blurRadius: 14)] : null,
                    ),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(selected ? item.$2 : item.$1, size: 20, color: selected ? colors.neonCyan : theme.colorScheme.onSurfaceVariant),
                        const SizedBox(height: 2),
                        Text(item.$3, maxLines: 1, style: TextStyle(fontSize: 10, fontWeight: selected ? FontWeight.w800 : FontWeight.w600, color: selected ? colors.neonCyan : theme.colorScheme.onSurfaceVariant)),
                      ],
                    ),
                  ),
                ),
              );
            }),
          ),
        ),
      ),
    );
  }
}

class _DesktopProductRail extends StatelessWidget {
  const _DesktopProductRail({required this.selectedRoute, required this.onNavigate});

  final String selectedRoute;
  final ValueChanged<String> onNavigate;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.extension<TradingColors>()!;
    return Container(
      width: 224,
      margin: const EdgeInsets.all(16),
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 14),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        gradient: const LinearGradient(colors: [Color(0xF0051326), Color(0xF009051C)]),
        border: Border.all(color: colors.neonCyan.withValues(alpha: 0.48)),
        boxShadow: [BoxShadow(color: colors.neonCyan.withValues(alpha: 0.14), blurRadius: 30), BoxShadow(color: colors.neonMagenta.withValues(alpha: 0.08), blurRadius: 42)],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ShaderMask(
            shaderCallback: (bounds) => LinearGradient(colors: [colors.neonCyan, colors.neonViolet, colors.neonMagenta]).createShader(bounds),
            child: const Text('ATS', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w900)),
          ),
          const SizedBox(height: 6),
          Text('AI Trading System', style: theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          const SizedBox(height: 22),
          _RailAction(label: 'Home', icon: Icons.space_dashboard_rounded, active: selectedRoute == '/', onTap: () => onNavigate('/')),
          _RailAction(label: 'Markets', icon: Icons.candlestick_chart_rounded, active: selectedRoute == '/market', onTap: () => onNavigate('/market')),
          _RailAction(label: 'AI Research', icon: Icons.auto_awesome_rounded, active: selectedRoute == '/research', onTap: () => onNavigate('/research')),
          _RailAction(label: 'Portfolio', icon: Icons.account_balance_wallet_rounded, active: selectedRoute == '/portfolio', onTap: () => onNavigate('/portfolio')),
          const Spacer(),
          _RailAction(label: 'Settings', icon: Icons.settings_rounded, active: selectedRoute == '/settings', onTap: () => onNavigate('/settings')),
        ],
      ),
    );
  }
}

class _RailAction extends StatelessWidget {
  const _RailAction({required this.label, required this.icon, required this.active, required this.onTap});
  final String label;
  final IconData icon;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.extension<TradingColors>()!;
    final foreground = active ? colors.neonCyan : theme.colorScheme.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Material(
        color: active ? colors.neonCyan.withValues(alpha: 0.07) : Colors.transparent,
        borderRadius: BorderRadius.circular(12),
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 11),
            child: Row(children: [Icon(icon, size: 19, color: foreground), const SizedBox(width: 10), Expanded(child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: foreground, fontSize: 12.5, fontWeight: FontWeight.w700)))]),
          ),
        ),
      ),
    );
  }
}

class _MoreProductSheet extends StatelessWidget {
  const _MoreProductSheet();

  static const _destinations = <(String, IconData, String, String)>[
    ('/orders', Icons.receipt_long_rounded, 'PAPER orders', 'Create and review simulated trades'),
    ('/risk', Icons.shield_rounded, 'Risk controls', 'Trading limits and safety controls'),
    ('/backtests', Icons.science_rounded, 'Backtests', 'Review strategy simulations'),
    ('/notifications', Icons.notifications_rounded, 'Notifications', 'Important account and trading updates'),
    ('/settings', Icons.tune_rounded, 'Settings', 'Trading mode, execution mode and appearance'),
  ];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.extension<TradingColors>()!;
    final maxHeight = MediaQuery.sizeOf(context).height * 0.72;
    return ConstrainedBox(
      constraints: BoxConstraints(maxHeight: maxHeight),
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(14, 0, 14, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ShaderMask(
              shaderCallback: (bounds) => LinearGradient(colors: [colors.neonCyan, colors.neonViolet, colors.neonMagenta]).createShader(bounds),
              child: const Text('More', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w900)),
            ),
            const SizedBox(height: 3),
            Text('Trading tools and settings', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            const SizedBox(height: 10),
            ..._destinations.map(
              (destination) => Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Material(
                  color: theme.colorScheme.surfaceContainerHigh.withValues(alpha: 0.72),
                  borderRadius: BorderRadius.circular(14),
                  child: InkWell(
                    borderRadius: BorderRadius.circular(14),
                    onTap: () => Navigator.of(context).pop(destination.$1),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 9),
                      child: Row(
                        children: [
                          Container(
                            width: 36,
                            height: 36,
                            decoration: BoxDecoration(borderRadius: BorderRadius.circular(11), color: colors.neonCyan.withValues(alpha: 0.10)),
                            child: Icon(destination.$2, color: colors.neonCyan, size: 19),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(destination.$3, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 14)),
                                const SizedBox(height: 1),
                                Text(destination.$4, maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
                              ],
                            ),
                          ),
                          const Icon(Icons.chevron_right_rounded, size: 20),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
