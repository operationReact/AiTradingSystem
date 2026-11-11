import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'auth/customer_identity.dart';

import 'brand/minttick_brand.dart';
import 'data/authenticated_api_client.dart';
import 'data/backtest_repository.dart';
import 'data/health_repository.dart';
import 'data/home_quote_repository.dart';
import 'data/instrument_repository.dart';
import 'data/market_data_repository.dart';
import 'data/model_control_center_repository.dart';
import 'data/model_emergency_control_repository.dart';
import 'data/operator_session.dart';
import 'data/operations_evidence_repository.dart';
import 'data/paper_trading_repository.dart';
import 'data/research_repository.dart';
import 'data/research_experiment_job_repository.dart';
import 'data/risk_approval_repository.dart';
import 'data/scenario_evidence_repository.dart';
import 'features/trading_control/data/trading_control_repository.dart';
import 'features/trading_rooms/domain/trading_room_draft.dart';
import 'features/mintick_mind/presentation/mind_workspace_page.dart';
import 'features/mintick_mind/data/mind_readiness_repository.dart';
import 'features/mintick_mind/data/mind_evidence_repository.dart';
import 'features/mintick_mind/data/mind_strategy_replay_repository.dart';
import 'features/mintick_mind/data/mind_ma_walkforward_repository.dart';
import 'features/mintick_mind/data/mind_prospective_cohort_repository.dart';
import 'features/mintick_mind/data/mind_prospective_signals_repository.dart';
import 'features/mintick_mind/data/mind_prospective_outcomes_repository.dart';
import 'features/mintick_mind/data/mind_prospective_evidence_completeness_repository.dart';
import 'features/trading_rooms/domain/trading_workspace_mode.dart';
import 'features/trading_rooms/data/customer_execution_repository.dart';
import 'features/trading_rooms/presentation/minttick_customer_portfolio.dart';
import 'features/trading_rooms/data/customer_room_repository.dart';
import 'features/trading_rooms/data/customer_angel_one_repository.dart';
import 'features/trading_rooms/data/customer_portfolio_repository.dart';
import 'features/trading_rooms/data/customer_market_repository.dart';
import 'features/trading_rooms/data/customer_upstox_repository.dart';
import 'features/trading_rooms/presentation/minttick_upstox_page.dart';
import 'features/trading_rooms/presentation/minttick_broker_hub_page.dart';
import 'features/trading_rooms/presentation/minttick_customer_broker_page.dart';
import 'features/trading_rooms/data/customer_home_summary_repository.dart';
import 'features/trading_rooms/data/customer_home_activity_repository.dart';
import 'features/trading_rooms/presentation/minttick_home_activity_page.dart';
import 'features/trading_rooms/presentation/minttick_home_command_centre.dart';
import 'features/trading_rooms/presentation/minttick_customer_market_page.dart';
import 'features/trading_rooms/presentation/minttick_customer_states.dart';
import 'features/trading_rooms/presentation/minttick_rooms_pages.dart';
import 'presentation/backtests_page.dart';
import 'presentation/instruments_page.dart';
import 'presentation/market_data_page.dart';
import 'presentation/model_control_center_page.dart';
import 'presentation/model_emergency_controls_page.dart';
import 'presentation/operations_evidence_page.dart';
import 'presentation/operations_health_page.dart';
import 'presentation/overview_page.dart';
import 'presentation/paper_orders_page.dart';
import 'presentation/paper_portfolio_page.dart';
import 'presentation/research_page.dart';
import 'presentation/research_experiment_jobs_page.dart';
import 'presentation/risk_approvals_page.dart';
import 'presentation/scenario_lookup_page.dart';
import 'presentation/settings_page.dart';
import 'presentation/historical_data_monitor_page.dart';
import 'data/history_monitor_repository.dart';
import 'presentation/create_account_page.dart';
import 'presentation/cognito_test_page.dart';
import 'presentation/customer_account_page.dart';
import 'presentation/password_recovery_page.dart';
import 'presentation/welcome_page.dart';
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
  late final NavigatorObserver _routeObserver = _MintTickRouteObserver((name) {
    if (!mounted || _selectedRoute == name) return;
    setState(() => _selectedRoute = name);
  });
  late final TradingRoomDraftStore _roomDrafts;
  late final TradingWorkspaceMode _workspaceMode;
  ThemeMode _themeMode = ThemeMode.dark;
  String _selectedRoute = '/';
  bool _exitDialogOpen = false;

  AuthenticatedApiClient get _api => AuthenticatedApiClient(session: widget.session);
  HttpOperationsEvidenceRepository get _operationsEvidence => HttpOperationsEvidenceRepository(_api);

  @override
  void initState() {
    super.initState();
    _roomDrafts = TradingRoomDraftStore(repository: HttpCustomerRoomRepository(_api));
    _workspaceMode = TradingWorkspaceMode();
    _selectedRoute = widget.initialRoute;
  }

  @override
  void dispose() {
    _roomDrafts.dispose();
    _workspaceMode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: _navigatorKey,
      navigatorObservers: [_routeObserver],
      title: MintTickBrand.appName,
      debugShowCheckedModeBanner: false,
      themeMode: _themeMode,
      theme: buildTheme(Brightness.light),
      darkTheme: buildTheme(Brightness.dark),
      initialRoute: widget.initialRoute,
      builder: (context, child) => LayoutBuilder(
        builder: (context, constraints) {
          // Sign-in is a focused account experience. Never overlay the product
          // bottom bar or desktop trading rail on authentication pages.
          if (_selectedRoute == '/auth-test' || _selectedRoute == '/sign-in' || _selectedRoute == '/sign-up' || _selectedRoute == '/welcome' || _selectedRoute == '/welcome-replay' || _selectedRoute == '/forgot-password' || _selectedRoute == '/account' || _selectedRoute.startsWith('/rooms/')) {
            return child ?? const SizedBox.shrink();
          }
          if (constraints.maxWidth >= 900) {
            return Row(
              children: [
                _DesktopProductRail(selectedRoute: _selectedRoute, onNavigate: _navigate, isCustomer: widget.session.isCustomerAuthenticated),
                Expanded(child: child ?? const SizedBox.shrink()),
              ],
            );
          }
          return Stack(
            children: [
              Positioned.fill(bottom: 64, child: child ?? const SizedBox.shrink()),
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                child: _MobileProductNavigation(
                  selectedRoute: _selectedRoute,
                  onNavigate: _navigate,
                  onMore: _openMoreNavigation,
                  isCustomer: widget.session.isCustomerAuthenticated,
                ),
              ),
            ],
          );
        },
      ),
      routes: {
        '/': (_) => _guardedOverviewPage(),
        '/mind': (_) => widget.session.isCustomerAuthenticated
            ? MintickMindWorkspacePage(
                embeddedInProductShell: true,
                readinessRepository: HttpMindReadinessRepository(_api),
                evidenceRepository: HttpMindEvidenceRepository(_api),
                strategyReplayRepository: HttpMindStrategyReplayRepository(_api),
                walkForwardRepository: HttpMindMaWalkForwardRepository(_api),
                prospectiveRepository: HttpMindProspectiveCohortRepository(_api),
                prospectiveSignalsRepository: HttpMindProspectiveSignalsRepository(_api),
                prospectiveOutcomesRepository:
                    HttpMindProspectiveOutcomesRepository(_api),
                prospectiveEvidenceCompletenessRepository:
                    HttpMindProspectiveEvidenceCompletenessRepository(_api),
              )
            : _overviewPage(),
        '/rooms': (_) => widget.session.isCustomerAuthenticated
            ? MintTickTradingRoomsPage(
                store: _roomDrafts,
                mode: _workspaceMode,
                onCreate: _openCreateRoom,
                onOpen: _openRoom,
              )
            : _overviewPage(),
        '/brokers': (_) => widget.session.isCustomerAuthenticated
            ? MintTickBrokerHubPage(
                angelOne: HttpCustomerAngelOneRepository(_api),
                upstox: HttpCustomerUpstoxRepository(_api),
                onAngelOne: () => _navigate('/broker/angel-one'),
                onUpstox: () => _navigate('/broker/upstox'),
              )
            : _overviewPage(),
        '/broker/upstox': (_) => widget.session.isCustomerAuthenticated
            ? MintTickUpstoxPage(
                repository: HttpCustomerUpstoxRepository(_api),
              )
            : _overviewPage(),
        '/broker/angel-one': (_) => widget.session.isCustomerAuthenticated
            ? MintTickCustomerBrokerPage(
                repository: HttpCustomerAngelOneRepository(_api),
                onBack: _backToPreviousScreen,
              )
            : _overviewPage(),
        '/activity': (_) => widget.session.isCustomerAuthenticated
            ? MintTickHomeActivityPage(
                repository: HttpCustomerHomeActivityRepository(_api),
                mode: _workspaceMode,
                onRoom: _openRoomById,
                onRooms: () => _navigate('/rooms'),
              )
            : _overviewPage(),
        '/account': (_) => CustomerAccountPage(
          loadAccount: () async {
            final data = await _api.getJson('/api/auth/cognito/me');
            if (data is! Map<String, dynamic>) {
              throw const FormatException('Invalid account profile');
            }
            return data;
          },
          onSignOut: _signOut,
        ),
        '/auth-test': (_) => const MintTickCognitoTestPage(),
        '/sign-in': (_) => MintTickWelcomePage(onAuthenticated: _onCustomerAuthenticated),
        '/sign-up': (_) => const CreateAccountPage(),
        '/forgot-password': (_) => const PasswordRecoveryPage(),
        '/welcome': (_) => MintTickWelcomePage(onAuthenticated: _onCustomerAuthenticated),
        '/welcome-replay': (_) => const MintTickWelcomePage(replayFromSettings: true),
        '/instruments': (_) => InstrumentsPage(repository: HttpInstrumentRepository(_api)),
        '/market': (_) => widget.session.isCustomerAuthenticated
            ? MintTickCustomerMarketPage(
                repository: HttpCustomerMarketRepository(_api),
                onConnectBroker: () => _navigate('/broker/angel-one'),
              )
            : MarketDataPage(repository: HttpMarketDataRepository(_api), isAuthenticated: widget.session.isAuthenticated),
        '/market-data': (_) => widget.session.isCustomerAuthenticated
            ? MintTickCustomerMarketPage(repository: HttpCustomerMarketRepository(_api))
            : MarketDataPage(repository: HttpMarketDataRepository(_api), isAuthenticated: widget.session.isAuthenticated),
        '/backtests': (_) => BacktestsPage(repository: HttpBacktestRepository(_api)),
        '/signals': (_) => ResearchPage(repository: HttpResearchRepository(_api), isAuthenticated: widget.session.isAuthenticated),
        '/research': (_) => ResearchPage(repository: HttpResearchRepository(_api), isAuthenticated: widget.session.isAuthenticated),
        '/scenarios': (_) => ScenarioLookupPage(repository: HttpScenarioEvidenceRepository(_api)),
        '/portfolio': (_) => widget.session.isCustomerAuthenticated
            ? MintTickCustomerPortfolioPage(
                mode: _workspaceMode,
                rooms: _roomDrafts,
                paperRepository: HttpCustomerRoomRepository(_api),
                brokerRepository: HttpCustomerAngelOneRepository(_api),
                upstoxRepository: HttpCustomerUpstoxRepository(_api),
                portfolioRepository: HttpCustomerPortfolioRepository(_api),
                onRooms: () => _navigate('/rooms'),
                onBroker: () => _navigate('/brokers'),
              )
            : PaperPortfolioPage(repository: HttpPaperTradingRepository(_api), isAuthenticated: widget.session.isAuthenticated),
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
        '/notifications': (_) => widget.session.isCustomerAuthenticated ? _customerState('Notifications') : OperationsEvidencePage(repository: _operationsEvidence, section: OperationsEvidenceSection.notifications),
        '/audit': (_) => OperationsEvidencePage(repository: _operationsEvidence, section: OperationsEvidenceSection.audit),
        '/history-monitor': (_) => widget.session.isCustomerAuthenticated
            ? HistoricalDataMonitorPage(
                repository: HttpHistoryMonitorRepository(_api),
              )
            : _overviewPage(),
        '/settings': (_) => SettingsPage(
              themeMode: _themeMode,
              onThemeModeChanged: _setThemeMode,
              onSignOut: _signOut,
              onHistoryMonitor: widget.session.isCustomerAuthenticated
                  ? () => _navigate('/history-monitor') : null,
              tradingControlRepository: widget.session.isCustomerAuthenticated ? null : HttpTradingControlRepository(_api),
            ),
        '/operations': (_) => _healthPage(),
        '/operations/health': (_) => _healthPage(),
        '/admin/experiments': (_) => ResearchExperimentJobsPage(
              repository: HttpResearchExperimentJobRepository(_api),
            ),
        '/admin/model-emergency': (_) => ModelEmergencyControlsPage(
              repository: HttpModelEmergencyControlRepository(_api),
            ),
        '/admin/models': (_) => ModelControlCenterPage(
              repository: HttpModelControlCenterRepository(_api),
            ),
      },
      onUnknownRoute: (_) => MaterialPageRoute<void>(builder: (_) => _overviewPage()),
    );
  }

  Future<void> _signOut() async {
    // Best-effort server-side disconnect while the customer JWT is still
    // available. A network failure must never block local sign-out; the
    // server also expires all broker sessions automatically.
    if (widget.session.isCustomerAuthenticated) {
      try {
        await _api.postJson(
          '/api/customer/brokers/angel-one/disconnect',
          timeout: const Duration(seconds: 3),
        );
      } catch (_) {
        // Bounded session expiry still applies when offline.
      }
    }
    if (widget.session.isCustomerAuthenticated) {
      try {
        await _api.postJson(
          '/api/customer/brokers/upstox/disconnect',
          timeout: const Duration(seconds: 3),
        );
      } catch (_) {
        // Server session still expires at Upstox's daily cutoff.
      }
    }
    _roomDrafts.clear();
    _workspaceMode.reset();
    widget.session.clear();
    try {
      await SharedPreferencesAsync().setBool('minttick.auth.remember_me.v1', false);
    } catch (_) {
      // Never depend on a stored preference to revoke local access.
    }
    try {
      await MintTickIdentity.current.signOut();
    } catch (_) {
      // User must still return to login. Access JWTs expire on the server.
    }
    if (!mounted) return;
    _resetNavigation('/welcome');
  }

  void _onCustomerAuthenticated() {
    widget.session.markCustomerAuthenticated();
    _resetNavigation('/');
  }

  /// Only authentication boundaries clear history. Normal navigation must
  /// preserve Android's back stack for system gestures and predictive back.
  void _resetNavigation(String route) {
    if (!mounted) return;
    setState(() => _selectedRoute = route);
    _navigatorKey.currentState?.pushNamedAndRemoveUntil(
      route, (candidate) => false,
    );
  }

  void _backToPreviousScreen() {
    final navigator = _navigatorKey.currentState;
    if (navigator != null && navigator.canPop()) {
      navigator.pop();
    } else {
      _navigate('/brokers');
    }
  }

  /// Primary navigation and feature links share one coherent route history.
  /// Re-selecting the same tab returns to its root rather than stacking copies.
  void _navigate(String route) {
    if (!mounted) return;
    final navigator = _navigatorKey.currentState;
    if (navigator == null) return;

    if (route == _selectedRoute) {
      navigator.popUntil((candidate) =>
          candidate.settings.name == route || candidate.isFirst);
      return;
    }
    if (route == '/' && widget.session.isCustomerAuthenticated) {
      // Home is the anchored destination of a signed-in workspace.
      // Returning there must not create duplicate Home routes.
      bool foundHome = false;
      navigator.popUntil((candidate) {
        if (candidate.settings.name == '/') {
          foundHome = true;
          return true;
        }
        return candidate.isFirst;
      });
      if (foundHome) {
        setState(() => _selectedRoute = '/');
        return;
      }
    }
    setState(() => _selectedRoute = route);
    navigator.pushNamed(route);
  }

  /// A root-level gesture should never silently terminate a trading session.
  /// Nested routes and dialogs pop normally; only Home needs this safeguard.
  Widget _guardedOverviewPage() => PopScope<void>(
    key: const ValueKey('minttick-home-exit-guard'),
    canPop: false,
    onPopInvokedWithResult: (didPop, _) {
      if (!didPop) _confirmAppExit();
    },
    child: _overviewPage(),
  );

  Future<void> _confirmAppExit() async {
    if (_exitDialogOpen || !mounted) return;
    final context = _navigatorKey.currentContext;
    if (context == null || !context.mounted) return;
    _exitDialogOpen = true;
    try {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: const Text('Exit MintTick?'),
          content: const Text(
            'Leave the app? Your trading account remains protected. '
            'No live orders will be placed by closing MintTick.',
          ),
          actions: [
            TextButton(
              key: const ValueKey('navigation-stay'),
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('Stay'),
            ),
            FilledButton(
              key: const ValueKey('navigation-exit'),
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('Exit app'),
            ),
          ],
        ),
      );
      if (confirmed == true && mounted && !kIsWeb) {
        await SystemNavigator.pop();
      }
    } finally {
      _exitDialogOpen = false;
    }
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
      builder: (context) => _MoreProductSheet(isCustomer: widget.session.isCustomerAuthenticated),
    );
    if (!mounted) return;
    if (route != null) {
      _navigate(route);
    } else {
      setState(() => _selectedRoute = previousRoute);
    }
  }

  void _setThemeMode(ThemeMode value) {
    if (_themeMode == value) return;
    setState(() => _themeMode = value);
  }

  Future<Map<String, dynamic>> _loadCustomerProfile() async {
    final result = await _api.getJson('/api/auth/cognito/me');
    if (result is! Map<String, dynamic>) {
      throw const FormatException('Invalid customer profile');
    }
    return result;
  }

  Widget _customerState(String section) => MintTickCustomerUnavailablePage(
        section: section,
        drafts: _roomDrafts,
        onRooms: () => _navigate('/rooms'),
        onBroker: () => _navigate('/brokers'),
      );

  Widget _overviewPage() => widget.session.isCustomerAuthenticated
      ? MintTickHomeCommandCentre(
          loadProfile: _loadCustomerProfile,
          summaryRepository: HttpCustomerHomeSummaryRepository(_api),
          drafts: _roomDrafts,
          mode: _workspaceMode,
          onCreateRoom: _openCreateRoom,
          onOpenRoom: _openRoom,
          onRooms: () => _navigate('/rooms'),
          onMarkets: () => _navigate('/market'),
          onMind: () => _navigate('/mind'),
          onPortfolio: () => _navigate('/portfolio'),
          onActivity: () => _navigate('/activity'),
          onBroker: () => _navigate('/brokers'),
          onAccount: () => _navigate('/account'),
          onNotifications: () => _navigate('/notifications'),
        )
      : OverviewPage(
          session: widget.session,
          dashboardRepository: HttpDashboardRepository(session: widget.session),
          marketQuoteRepository: HttpHomeQuoteRepository(_api),
        );

  Future<void> _openCreateRoom() async {
    if (!widget.session.isCustomerAuthenticated) return;
    final navContext = _navigatorKey.currentContext;
    if (navContext == null) return;
    final room = await Navigator.of(navContext).push<TradingRoomDraft>(
      MaterialPageRoute(
        settings: const RouteSettings(name: '/rooms/create'),
        builder: (_) => MintTickRoomCreationPage(initialMode: _workspaceMode.selected),
      ),
    );
    if (!mounted || room == null) return;
    try {
      await _roomDrafts.create(room);
      // A Paper room's planned budget is already confirmed in the creation
      // wizard. Allocate virtual cash only, exactly once per saved Paper room.
      // This does NOT unlock paper order execution or any LIVE money.
      if (room.mode == TradingWorkspaceMode.paper) {
        try {
          final saved = _roomDrafts.drafts.first;
          if (saved.mode != TradingWorkspaceMode.paper) {
            throw const FormatException('Created Paper room mismatch');
          }
          await HttpCustomerRoomRepository(_api).activatePaper(saved.id);
          await _roomDrafts.load();
        } catch (_) {
          // Preserve the saved room; the user can retry funding in Details.
          final current = _navigatorKey.currentContext;
          if (mounted && current != null && current.mounted) {
            ScaffoldMessenger.of(current).showSnackBar(
              const SnackBar(content: Text(
                'Paper Room saved, but virtual funding could not be verified. '
                'Open the room to retry. No real money was used.',
              )),
            );
          }
        }
      }
      if (mounted) _navigate('/rooms');
    } catch (_) {
      // Never add a phantom room when the server write failed.
      final current = _navigatorKey.currentContext;
      if (mounted && current != null && current.mounted) {
        ScaffoldMessenger.of(current).showSnackBar(
          const SnackBar(content: Text(
            'Could not save your Trading Room. Verify the connection and try again.',
          )),
        );
      }
    }
  }

  void _openRoomById(String id) {
    for (final room in _roomDrafts.drafts) {
      if (room.id == id) {
        _openRoom(room);
        return;
      }
    }
    _navigate('/rooms');
  }

  void _openRoom(TradingRoomDraft room) {
    if (!widget.session.isCustomerAuthenticated) return;
    final navContext = _navigatorKey.currentContext;
    if (navContext == null) return;
    Navigator.of(navContext).push<void>(
      MaterialPageRoute(
        settings: const RouteSettings(name: '/rooms/detail'),
        builder: (_) => MintTickRoomDetailPage(
          room: room,
          repository: HttpCustomerRoomRepository(_api),
          executionRepository: HttpCustomerExecutionRepository(_api),
          onBroker: () => _navigate('/brokers'),
          onRoomChanged: _roomDrafts.load,
        ),
      ),
    );
  }

  Widget _healthPage() => OperationsHealthPage(
        key: const ValueKey('operations-health-page'),
        repository: widget.repositoryFactory(),
        session: widget.session,
      );
}

class _MobileProductNavigation extends StatelessWidget {
  const _MobileProductNavigation({required this.selectedRoute, required this.onNavigate, required this.onMore, required this.isCustomer});

  final String selectedRoute;
  final ValueChanged<String> onNavigate;
  final VoidCallback onMore;
  final bool isCustomer;

  int get _index {
    if (isCustomer) {
      if (selectedRoute == '/') return 0;
      if (selectedRoute == '/rooms' || selectedRoute.startsWith('/rooms/')) return 1;
      if (selectedRoute == '/market' || selectedRoute == '/market-data' || selectedRoute == '/instruments') return 2;
      if (selectedRoute == '/mind') return 3;
      if (selectedRoute == '/portfolio' || selectedRoute == '/orders' || selectedRoute == '/paper-orders') return 4;
      return 5;
    }
    if (selectedRoute == '/') return 0;
    if (selectedRoute == '/market' || selectedRoute == '/market-data' || selectedRoute == '/instruments') return 1;
    if (selectedRoute == '/research' || selectedRoute == '/signals' || selectedRoute == '/backtests' || selectedRoute == '/scenarios') return 2;
    if (selectedRoute == '/portfolio' || selectedRoute == '/orders' || selectedRoute == '/paper-orders') return 3;
    return 4;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    // Customer navigation retains every primary destination. MintTick Mind
    // must never be hidden behind More after adding Trading Rooms.
    final items = <(IconData, IconData, String)>[
      (Icons.space_dashboard_outlined, Icons.space_dashboard_rounded, 'Home'),
      if (isCustomer)
        (Icons.layers_outlined, Icons.layers_rounded, 'Rooms'),
      (Icons.candlestick_chart_outlined, Icons.candlestick_chart_rounded, 'Markets'),
      isCustomer
          ? (Icons.psychology_outlined, Icons.psychology_rounded, 'Mind')
          : (Icons.auto_awesome_outlined, Icons.auto_awesome_rounded, 'AI'),
      (Icons.account_balance_wallet_outlined, Icons.account_balance_wallet_rounded, 'Portfolio'),
      (Icons.grid_view_outlined, Icons.grid_view_rounded, 'More'),
    ];

    return Material(
      key: const ValueKey('product-mobile-navigation'),
      color: theme.colorScheme.surface,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(32),
        side: BorderSide(color: theme.colorScheme.outlineVariant),
      ),
      clipBehavior: Clip.antiAlias,
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
                  key: ValueKey('mobile-nav-${!isCustomer && index == 2 ? 'research' : item.$3.toLowerCase()}'),
                  onTap: () {
                    if (index == items.length - 1) {
                      onMore();
                    } else if (isCustomer) {
                      onNavigate(switch (index) {
                        0 => '/',
                        1 => '/rooms',
                        2 => '/market',
                        3 => '/mind',
                        4 => '/portfolio',
                        _ => '/',
                      });
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
                  // Flat selection: green icon, white label; no filled pill,
                  // gradient, halo or glow. Matches the owner-approved reference.
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 1, vertical: 5),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(selected ? item.$2 : item.$1, size: 22, color: selected ? theme.colorScheme.primary : theme.colorScheme.onSurfaceVariant),
                        const SizedBox(height: 2),
                        Text(item.$3, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: isCustomer ? 10.5 : 11.5, fontWeight: selected ? FontWeight.w800 : FontWeight.w600, color: selected ? theme.colorScheme.onSurface : theme.colorScheme.onSurfaceVariant)),
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
  const _DesktopProductRail({required this.selectedRoute, required this.onNavigate, required this.isCustomer});

  final String selectedRoute;
  final ValueChanged<String> onNavigate;
  final bool isCustomer;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      width: 224,
      margin: const EdgeInsets.all(16),
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 14),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(20),
        color: theme.colorScheme.surfaceContainerLow,
        border: Border.all(color: theme.colorScheme.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const MintTickLockup(markWidth: 36, fontSize: 18),
          const SizedBox(height: 8),
          Text(isCustomer ? 'TRADING WORKSPACE' : 'AI TRADING PLATFORM · PAPER', style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onSurfaceVariant, fontSize: 10.5, letterSpacing: 0.7)),
          const SizedBox(height: 22),
          _RailAction(label: 'Home', icon: Icons.space_dashboard_rounded, active: selectedRoute == '/', onTap: () => onNavigate('/')),
          if (isCustomer)
            _RailAction(label: 'Trading Rooms', icon: Icons.layers_rounded, active: selectedRoute == '/rooms', onTap: () => onNavigate('/rooms')),
          _RailAction(label: 'Markets', icon: Icons.candlestick_chart_rounded, active: selectedRoute == '/market', onTap: () => onNavigate('/market')),
          if (isCustomer) _RailAction(label: 'MintTick Mind', icon: Icons.psychology_outlined, active: selectedRoute == '/mind', onTap: () => onNavigate('/mind')),
          if (!isCustomer)
            _RailAction(label: 'AI Research', icon: Icons.auto_awesome_rounded, active: selectedRoute == '/research', onTap: () => onNavigate('/research')),
          _RailAction(label: 'Portfolio', icon: Icons.account_balance_wallet_rounded, active: selectedRoute == '/portfolio', onTap: () => onNavigate('/portfolio')),
          if (isCustomer)
            _RailAction(label: 'Mind activity', icon: Icons.history_rounded, active: selectedRoute == '/activity', onTap: () => onNavigate('/activity')),
          if (isCustomer)
            _RailAction(label: 'Account', icon: Icons.person_outline_rounded, active: selectedRoute == '/account', onTap: () => onNavigate('/account')),
          if (!isCustomer) _RailAction(label: 'Model control', icon: Icons.hub_rounded, active: selectedRoute == '/admin/models', onTap: () => onNavigate('/admin/models')),
          if (!isCustomer) _RailAction(label: 'Experiments', icon: Icons.science_outlined, active: selectedRoute == '/admin/experiments', onTap: () => onNavigate('/admin/experiments')),
          if (!isCustomer) _RailAction(label: 'Emergency controls', icon: Icons.shield_rounded, active: selectedRoute == '/admin/model-emergency', onTap: () => onNavigate('/admin/model-emergency')),
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
  const _MoreProductSheet({required this.isCustomer});

  final bool isCustomer;

  static const _destinations = <(String, IconData, String, String)>[
    ('/orders', Icons.receipt_long_rounded, 'PAPER orders', 'Create and review simulated trades'),
    ('/risk', Icons.shield_rounded, 'Risk controls', 'Trading limits and safety controls'),
    ('/backtests', Icons.science_rounded, 'Backtests', 'Review strategy simulations'),
    ('/admin/models', Icons.hub_rounded, 'Model control', 'ADMIN model registry and PAPER/SHADOW evidence'),
    ('/admin/experiments', Icons.science_outlined, 'Experiments', 'Review immutable research specs and queued jobs'),
    ('/admin/model-emergency', Icons.shield_rounded, 'Emergency model controls', 'Reduce model influence with audited confirmation'),
    ('/notifications', Icons.notifications_rounded, 'Notifications', 'Important account and trading updates'),
    ('/settings', Icons.tune_rounded, 'Settings', 'Trading mode, execution mode and appearance'),
  ];

  static const _customerDestinations = <(String, IconData, String, String)>[
    ('/rooms', Icons.layers_outlined, 'Trading Rooms', 'Your room plans'),
    ('/brokers', Icons.account_balance_outlined, 'Connected brokers', 'Angel One and Upstox'),
    ('/mind', Icons.psychology_outlined, 'MintTick Mind', 'Prototype UI with mock-only strategy data'),
    ('/activity', Icons.history_rounded, 'Mind activity', 'Decisions and audit history'),
    ('/notifications', Icons.notifications_none_rounded, 'Notifications', 'Account updates'),
    ('/account', Icons.person_outline_rounded, 'Account', 'Profile and sign out'),
    ('/settings', Icons.tune_rounded, 'Settings', 'Appearance and preferences'),
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
            Text('More', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800)),
            const SizedBox(height: 3),
            Text('Trading tools and settings', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            const SizedBox(height: 10),
            ...(isCustomer ? _customerDestinations : _destinations).map(
              (destination) => Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: Material(
                  color: theme.colorScheme.surfaceContainerHigh,
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

class _MintTickRouteObserver extends NavigatorObserver {
  _MintTickRouteObserver(this.onRouteChanged);

  final ValueChanged<String> onRouteChanged;

  void _track(Route<dynamic>? route) {
    final name = route?.settings.name;
    if (name == null || name.isEmpty) return;
    WidgetsBinding.instance.addPostFrameCallback((_) => onRouteChanged(name));
  }

  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) {
    super.didPush(route, previousRoute);
    _track(route);
  }

  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previousRoute) {
    super.didPop(route, previousRoute);
    _track(previousRoute);
  }

  @override
  void didReplace({Route<dynamic>? newRoute, Route<dynamic>? oldRoute}) {
    super.didReplace(newRoute: newRoute, oldRoute: oldRoute);
    _track(newRoute);
  }
}
