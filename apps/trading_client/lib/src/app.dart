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
  String _selectedRoute = '/';

  AuthenticatedApiClient get _api => AuthenticatedApiClient(session: widget.session);
  HttpOperationsEvidenceRepository get _operationsEvidence => HttpOperationsEvidenceRepository(_api);

  @override
  void initState() {
    super.initState();
    _selectedRoute = widget.initialRoute;
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: _navigatorKey,
      title: 'AI Trading System',
      debugShowCheckedModeBanner: false,
      themeMode: _themeMode,
      theme: buildTheme(_style, Brightness.light),
      darkTheme: buildTheme(_style, Brightness.dark),
      initialRoute: widget.initialRoute,
      builder: (context, child) => LayoutBuilder(
        builder: (context, constraints) {
          if (constraints.maxWidth >= 900) {
            return Row(
              children: [
                _DesktopProductRail(selectedRoute: _selectedRoute, onNavigate: _navigate),
                Expanded(child: child ?? const SizedBox.shrink()),
              ],
            );
          }
          return Stack(
            children: [
              Positioned.fill(bottom: 72, child: child ?? const SizedBox.shrink()),
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
        '/settings': (_) => SettingsPage(
              designStyle: _style,
              themeMode: _themeMode,
              onDesignStyleChanged: _setDesignStyle,
              onThemeModeChanged: _setThemeMode,
            ),
        '/operations': (_) => _healthPage(),
        '/operations/health': (_) => _healthPage(),
      },
      onUnknownRoute: (_) => MaterialPageRoute<void>(builder: (_) => _overviewPage()),
    );
  }

  void _navigate(String route) {
    setState(() => _selectedRoute = route);
    _navigatorKey.currentState?.pushNamedAndRemoveUntil(route, (candidate) => false);
  }

  Future<void> _openMoreNavigation() async {
    final navContext = _navigatorKey.currentContext;
    if (navContext == null) return;
    final route = await showModalBottomSheet<String>(
      context: navContext,
      showDragHandle: true,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (context) => const _MoreWorkspaceSheet(),
    );
    if (route != null && mounted) _navigate(route);
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
      (Icons.radar_outlined, Icons.radar_rounded, 'Research'),
      (Icons.account_balance_wallet_outlined, Icons.account_balance_wallet_rounded, 'Portfolio'),
      (Icons.grid_view_outlined, Icons.grid_view_rounded, 'More'),
    ];

    return Material(
      key: const ValueKey('product-mobile-navigation'),
      color: theme.brightness == Brightness.dark ? const Color(0xF2071020) : theme.colorScheme.surfaceContainer,
      elevation: 0,
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: 72,
          child: Row(
            children: List.generate(items.length, (index) {
              final item = items[index];
              final selected = _index == index;
              return Expanded(
                child: InkWell(
                  key: ValueKey('mobile-nav-${item.$3.toLowerCase()}'),
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
                    duration: const Duration(milliseconds: 160),
                    margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 7),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(16),
                      color: selected ? colors.neonCyan.withValues(alpha: 0.10) : Colors.transparent,
                      border: selected ? Border.all(color: colors.neonCyan.withValues(alpha: 0.24)) : null,
                    ),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          selected ? item.$2 : item.$1,
                          size: 21,
                          color: selected ? colors.neonCyan : theme.colorScheme.onSurfaceVariant,
                        ),
                        const SizedBox(height: 4),
                        Text(
                          item.$3,
                          maxLines: 1,
                          style: TextStyle(
                            fontSize: 10.5,
                            fontWeight: selected ? FontWeight.w800 : FontWeight.w600,
                            color: selected ? colors.neonCyan : theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
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
    final neon = theme.brightness == Brightness.dark;
    return Container(
      width: 224,
      margin: const EdgeInsets.all(16),
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 14),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(18),
        gradient: neon
            ? const LinearGradient(colors: [Color(0xF0051326), Color(0xF009051C)])
            : LinearGradient(colors: [theme.colorScheme.surfaceContainer, theme.colorScheme.surface]),
        border: Border.all(color: neon ? colors.neonCyan.withValues(alpha: 0.58) : theme.colorScheme.outlineVariant),
        boxShadow: neon
            ? [
                BoxShadow(color: colors.neonCyan.withValues(alpha: 0.18), blurRadius: 28),
                BoxShadow(color: colors.neonMagenta.withValues(alpha: 0.10), blurRadius: 42),
              ]
            : null,
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
          _RailAction(label: 'Control Center', icon: Icons.space_dashboard_rounded, active: selectedRoute == '/', onTap: () => onNavigate('/')),
          _RailAction(label: 'Strategies & Research', icon: Icons.radar_rounded, active: selectedRoute == '/research', onTap: () => onNavigate('/research')),
          _RailAction(label: 'Portfolio & Orders', icon: Icons.account_balance_wallet_rounded, active: selectedRoute == '/portfolio', onTap: () => onNavigate('/portfolio')),
          _RailAction(label: 'Learning & Operations', icon: Icons.monitor_heart_rounded, active: selectedRoute == '/operations/health', onTap: () => onNavigate('/operations/health')),
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
            child: Row(
              children: [
                Icon(icon, size: 19, color: foreground),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(color: foreground, fontSize: 12.5, fontWeight: FontWeight.w700),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _MoreWorkspaceSheet extends StatelessWidget {
  const _MoreWorkspaceSheet();

  static const _destinations = <(String, IconData, String)>[
    ('/instruments', Icons.list_alt_rounded, 'Instruments'),
    ('/scenarios', Icons.analytics_rounded, 'Scenario & regime'),
    ('/backtests', Icons.science_rounded, 'PAPER backtests'),
    ('/orders', Icons.receipt_long_rounded, 'PAPER orders'),
    ('/risk', Icons.shield_rounded, 'Risk & approvals'),
    ('/news', Icons.newspaper_rounded, 'News evidence'),
    ('/notifications', Icons.notifications_rounded, 'Notifications'),
    ('/audit', Icons.history_rounded, 'Audit trail'),
    ('/operations/health', Icons.monitor_heart_rounded, 'System health'),
    ('/settings', Icons.tune_rounded, 'Appearance'),
  ];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.extension<TradingColors>()!;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 2, 20, 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ShaderMask(
            shaderCallback: (bounds) => LinearGradient(colors: [colors.neonCyan, colors.neonViolet, colors.neonMagenta]).createShader(bounds),
            child: const Text('Workspace', style: TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w900)),
          ),
          const SizedBox(height: 6),
          Text('Trading tools and operational controls', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          const SizedBox(height: 18),
          Flexible(
            child: GridView.builder(
              shrinkWrap: true,
              itemCount: _destinations.length,
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 2,
                mainAxisSpacing: 10,
                crossAxisSpacing: 10,
                childAspectRatio: 2.35,
              ),
              itemBuilder: (context, index) {
                final destination = _destinations[index];
                return InkWell(
                  borderRadius: BorderRadius.circular(16),
                  onTap: () => Navigator.of(context).pop(destination.$1),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(16),
                      color: theme.colorScheme.surfaceContainerHigh.withValues(alpha: 0.74),
                      border: Border.all(color: theme.colorScheme.outlineVariant),
                    ),
                    child: Row(
                      children: [
                        Icon(destination.$2, color: colors.neonCyan, size: 20),
                        const SizedBox(width: 9),
                        Expanded(child: Text(destination.$3, maxLines: 2, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700))),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
