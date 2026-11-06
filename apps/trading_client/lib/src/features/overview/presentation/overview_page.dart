import 'package:flutter/material.dart';

import '../../../brand/minttick_brand.dart';
import '../../../core/session/operator_session.dart';
import '../../../core/widgets/data_state_panel.dart';
import '../../../core/widgets/responsive_layout.dart';
import '../../../data/health_repository.dart';

class OverviewPage extends StatelessWidget {
  const OverviewPage({
    required this.session,
    required this.dashboardRepository,
    super.key,
  });

  final OperatorSession session;
  final DashboardRepository dashboardRepository;

  static const _modules = <_HomeModule>[
    _HomeModule(icon: Icons.candlestick_chart_rounded, title: 'Markets', description: 'Search stocks and explore recent price action.', route: '/market'),
    _HomeModule(icon: Icons.auto_awesome_rounded, title: 'AI Research', description: 'Discover ranked opportunities with entry, stop and target levels.', route: '/research'),
    _HomeModule(icon: Icons.account_balance_wallet_rounded, title: 'Portfolio', description: 'Track PAPER cash, positions and profit or loss.', route: '/portfolio'),
    _HomeModule(icon: Icons.shield_rounded, title: 'Risk controls', description: 'Review trading limits and PAPER safety controls.', route: '/risk'),
  ];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final width = constraints.maxWidth;
            final layout = ResponsiveLayout.classify(width);
            final mobile = layout == ResponsiveClass.mobile;
            final padding = ResponsiveLayout.horizontalPadding(width);
            final sectionGap = ResponsiveLayout.sectionGap(width);
            return SingleChildScrollView(
              padding: EdgeInsets.fromLTRB(padding, ResponsiveLayout.topPadding(width), padding, 24),
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 1180),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const _ProductHeader(),
                      SizedBox(height: mobile ? 14 : 20),
                      _AiPulseHero(layout: layout),
                      SizedBox(height: sectionGap),
                      _AccountPanel(session: session, repository: dashboardRepository),
                      SizedBox(height: mobile ? 16 : 24),
                      Semantics(
                        header: true,
                        child: Text('Quick access', style: (mobile ? theme.textTheme.titleMedium : theme.textTheme.titleLarge)?.copyWith(fontWeight: FontWeight.w700)),
                      ),
                      const SizedBox(height: 4),
                      if (!mobile)
                        Text(
                          'Move from market context to AI ideas and PAPER trading without exposing platform plumbing.',
                          style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
                        ),
                      SizedBox(height: mobile ? 8 : 16),
                      GridView.builder(
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        itemCount: _modules.length,
                        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                          crossAxisCount: mobile ? 1 : ResponsiveLayout.workspaceColumns(width),
                          mainAxisSpacing: mobile ? 8 : 12,
                          crossAxisSpacing: mobile ? 8 : 12,
                          childAspectRatio: switch (layout) {
                            ResponsiveClass.mobile => width >= 360 ? 4.3 : 3.75,
                            ResponsiveClass.adaptive => 1.45,
                            ResponsiveClass.desktop => 1.2,
                          },
                        ),
                        itemBuilder: (context, index) => _HomeModuleCard(module: _modules[index]),
                      ),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

class _ProductHeader extends StatelessWidget {
  const _ProductHeader();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final mobile = MediaQuery.sizeOf(context).width < ResponsiveLayout.mobileBreakpoint;
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Semantics(
                header: true,
                child: MintTickLockup(
                  markWidth: mobile ? 38 : 44,
                  fontSize: mobile ? 20 : 23,
                ),
              ),
              const SizedBox(height: 4),
              Text('AI-powered PAPER trading', style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
                fontSize: 12.5,
              )),
            ],
          ),
        ),
        IconButton(
          tooltip: 'Settings',
          onPressed: () => Navigator.of(context).pushNamed('/settings'),
          icon: const Icon(Icons.tune_rounded),
        ),
      ],
    );
  }
}

class _AiPulseHero extends StatelessWidget {
  const _AiPulseHero({required this.layout});
  final ResponsiveClass layout;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final mobile = layout == ResponsiveClass.mobile;
    return Container(
      key: const ValueKey('minttick-home-introduction'),
      width: double.infinity,
      padding: EdgeInsets.symmetric(
        horizontal: mobile ? 16 : 24,
        vertical: mobile ? 11 : 24,
      ),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: scheme.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('MARKETS  /  AI RESEARCH  /  PAPER',
              style: theme.textTheme.labelSmall?.copyWith(
                letterSpacing: 0.8,
                color: scheme.primary,
                fontWeight: FontWeight.w700,
              )),
          const SizedBox(height: 8),
          Semantics(
            header: true,
            child: Text(
              'Make informed trading decisions.',
              style: (mobile ? theme.textTheme.headlineSmall : theme.textTheme.headlineMedium)
                  ?.copyWith(fontWeight: FontWeight.w800, height: 1.14),
            ),
          ),
          const SizedBox(height: 7),
          Text(
            'Explore Indian equities, evaluate research, and test strategies with virtual funds.',
            style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

class _AccountPanel extends StatefulWidget {
  const _AccountPanel({required this.session, required this.repository});
  final OperatorSession session;
  final DashboardRepository repository;

  @override
  State<_AccountPanel> createState() => _AccountPanelState();
}

class _AccountPanelState extends State<_AccountPanel> {
  DashboardSummary? _summary;
  String? _error;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    if (widget.session.isAuthenticated) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _refresh();
      });
    }
  }

  DataStateKind get _state {
    final summary = _summary;
    if (_loading) return DataStateKind.loading;
    if (_error != null && summary == null) return DataStateKind.error;
    if (_error != null && summary != null) return DataStateKind.stale;
    if (summary == null) return DataStateKind.empty;
    switch (summary.systemStatus.trim().toUpperCase()) {
      case 'OK': return DataStateKind.ready;
      case 'STALE': return DataStateKind.stale;
      case 'HALTED':
      case 'RECONCILIATION_FAILED':
      case 'DEGRADED': return DataStateKind.degraded;
      default: return DataStateKind.unknown;
    }
  }

  Future<void> _refresh() async {
    if (!widget.session.isAuthenticated) return;
    setState(() { _loading = true; _error = null; });
    try {
      final summary = await widget.repository.fetchSummary();
      if (!mounted) return;
      setState(() => _summary = summary);
    } on DashboardAuthenticationRequired {
      if (!mounted) return;
      widget.session.clear();
      setState(() => _error = 'Your session has expired. Sign in again.');
    } on DashboardHttpFailure catch (failure) {
      if (!mounted) return;
      if (failure.statusCode == 401 || failure.statusCode == 403) {
        widget.session.clear();
        setState(() => _error = 'Your session has expired. Sign in again.');
      } else {
        setState(() => _error = 'Account data is temporarily unavailable.');
      }
    } on DashboardFailure {
      if (!mounted) return;
      setState(() => _error = 'Unable to reach the trading service.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _disconnect() {
    widget.session.clear();
    setState(() { _summary = null; _error = null; _loading = false; });
  }

  DataStatePanel _statePanel() {
    final state = _state;
    final (title, message) = switch (state) {
      DataStateKind.empty => ('Portfolio not loaded', 'Sign in to load your PAPER account.'),
      DataStateKind.loading => ('Loading your account', 'Fetching the latest PAPER portfolio and trading status.'),
      DataStateKind.ready => ('PAPER account connected', 'Your latest account values are shown below.'),
      DataStateKind.stale => ('Last-known state only', _error ?? 'Refresh to get the latest values.'),
      DataStateKind.degraded => ('Trading controls need attention', 'New PAPER actions may be paused until the account state is healthy.'),
      DataStateKind.unknown => ('Operating state UNKNOWN', 'Trading readiness cannot be confirmed right now.'),
      DataStateKind.error => ('Account unavailable', _error ?? 'Unable to load your PAPER account.'),
    };
    return DataStatePanel(key: ValueKey('dashboard-state-${state.name}'), kind: state, title: title, message: message);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final summary = _summary;
    final signedIn = widget.session.isAuthenticated;
    final mobile = MediaQuery.sizeOf(context).width < ResponsiveLayout.mobileBreakpoint;
    return Card(
      child: Padding(
        padding: EdgeInsets.all(mobile ? 12 : 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(signedIn ? Icons.verified_user_rounded : Icons.lock_person_rounded, color: theme.colorScheme.primary, size: mobile ? 21 : 24),
                const SizedBox(width: 9),
                Expanded(
                  child: Semantics(
                    header: true,
                    child: Text(signedIn ? 'PAPER account' : 'Sign in required', style: (mobile ? theme.textTheme.titleMedium : theme.textTheme.titleLarge)?.copyWith(fontWeight: FontWeight.w900)),
                  ),
                ),
                if (summary != null) const Chip(label: Text('PAPER')),
              ],
            ),
            SizedBox(height: mobile ? 6 : 8),
            if (!signedIn)
              Container(
                key: const ValueKey('dashboard-sign-in-required'),
                width: double.infinity,
                padding: EdgeInsets.all(mobile ? 12 : 18),
                decoration: BoxDecoration(borderRadius: BorderRadius.circular(mobile ? 14 : 18), color: theme.colorScheme.primaryContainer.withValues(alpha: 0.32)),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Secure sign-in is required', style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w800)),
                    const SizedBox(height: 4),
                    Text(
                      mobile ? 'Sign in for your PAPER portfolio, AI ideas, and trading controls.' : 'Sign in to view your PAPER portfolio, AI opportunities, and account-specific trading controls.',
                      style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant, height: mobile ? 1.3 : 1.45),
                    ),
                    SizedBox(height: mobile ? 9 : 14),
                    SizedBox(
                      width: mobile ? double.infinity : null,
                      child: FilledButton.icon(
                        key: const ValueKey('dashboard-secure-sign-in'),
                        onPressed: () => Navigator.of(context).pushNamed('/sign-in'),
                        icon: const Icon(Icons.lock_open_rounded, size: 19),
                        label: const Text('Secure sign in'),
                      ),
                    ),
                  ],
                ),
              )
            else ...[
              _statePanel(),
              if (summary != null) ...[const SizedBox(height: 10), _SummaryGrid(summary: summary)],
              const SizedBox(height: 10),
              Wrap(
                spacing: 8,
                children: [
                  OutlinedButton.icon(key: const ValueKey('dashboard-refresh'), onPressed: _loading ? null : _refresh, icon: const Icon(Icons.refresh_rounded), label: const Text('Refresh')),
                  TextButton.icon(key: const ValueKey('dashboard-disconnect'), onPressed: _disconnect, icon: const Icon(Icons.logout_rounded), label: const Text('Sign out')),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _SummaryGrid extends StatelessWidget {
  const _SummaryGrid({required this.summary});
  final DashboardSummary summary;
  String _money(num value) => '₹${value.toStringAsFixed(2)}';

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final columns = ResponsiveLayout.metricColumns(width);
    final mobile = width < ResponsiveLayout.mobileBreakpoint;
    final items = <(String, String)>[
      ('Equity', _money(summary.equity)), ('Available cash', _money(summary.availableCash)), ('Today P&L', _money(summary.dailyPnl)), ('Open positions', '${summary.openPositions}'), ('Market value', _money(summary.marketValue)), ('Unrealized P&L', _money(summary.unrealizedPnl)), ('Exposure', '${summary.exposurePercent.toStringAsFixed(2)}%'),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (summary.newOrdersHalted) ...[const Chip(label: Text('ORDERS HALTED')), const SizedBox(height: 8)],
        GridView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          itemCount: items.length,
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: columns,
            mainAxisSpacing: mobile ? 7 : 10,
            crossAxisSpacing: mobile ? 7 : 10,
            childAspectRatio: columns == 1 ? 4.2 : mobile ? 2.6 : 2.3,
          ),
          itemBuilder: (context, index) {
            final item = items[index];
            return Container(
              padding: EdgeInsets.all(mobile ? 10 : 14),
              decoration: BoxDecoration(color: Theme.of(context).colorScheme.surfaceContainerHighest.withValues(alpha: 0.55), borderRadius: BorderRadius.circular(mobile ? 13 : 16)),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(item.$1, style: Theme.of(context).textTheme.labelSmall),
                  const SizedBox(height: 2),
                  Text(item.$2, style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w900)),
                ],
              ),
            );
          },
        ),
      ],
    );
  }
}

class _HomeModuleCard extends StatelessWidget {
  const _HomeModuleCard({required this.module});
  final _HomeModule module;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final mobile = MediaQuery.sizeOf(context).width < ResponsiveLayout.mobileBreakpoint;
    return Semantics(
      button: true,
      label: '${module.title}. ${module.description}',
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: ValueKey('workspace-${module.route}'),
          onTap: () => Navigator.of(context).pushNamed(module.route),
          child: mobile
              ? Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
                  child: Row(
                    children: [
                      SizedBox(
                        width: 36,
                        height: 36,
                        child: Icon(module.icon, color: scheme.primary, size: 23),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(module.title, style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                            const SizedBox(height: 3),
                            Text(
                              module.description,
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 8),
                      Icon(Icons.chevron_right_rounded, size: 20, color: scheme.onSurfaceVariant),
                    ],
                  ),
                )
              : Padding(
                  padding: const EdgeInsets.all(18),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(children: [
                        Icon(module.icon, color: scheme.primary, size: 27),
                        const Spacer(),
                        const Icon(Icons.arrow_forward_rounded, size: 19),
                      ]),
                      const Spacer(),
                      Text(module.title, style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
                      const SizedBox(height: 3),
                      Text(module.description, maxLines: 3, overflow: TextOverflow.ellipsis),
                    ],
                  ),
                ),
        ),
      ),
    );
  }
}

class _HomeModule {
  const _HomeModule({required this.icon, required this.title, required this.description, required this.route});
  final IconData icon;
  final String title;
  final String description;
  final String route;
}
