import 'package:flutter/material.dart';

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
    _HomeModule(
      icon: Icons.candlestick_chart_rounded,
      title: 'Markets',
      description: 'Search stocks and explore recent price action.',
      route: '/market',
    ),
    _HomeModule(
      icon: Icons.auto_awesome_rounded,
      title: 'AI Research',
      description: 'Discover ranked opportunities with entry, stop and target levels.',
      route: '/research',
    ),
    _HomeModule(
      icon: Icons.account_balance_wallet_rounded,
      title: 'Portfolio',
      description: 'Track PAPER cash, positions and profit or loss.',
      route: '/portfolio',
    ),
    _HomeModule(
      icon: Icons.shield_rounded,
      title: 'Risk controls',
      description: 'Review trading limits and PAPER safety controls.',
      route: '/risk',
    ),
  ];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final layout = ResponsiveLayout.classify(constraints.maxWidth);
            final padding = ResponsiveLayout.horizontalPadding(constraints.maxWidth);
            return SingleChildScrollView(
              padding: EdgeInsets.fromLTRB(padding, 20, padding, 40),
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 1180),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const _ProductHeader(),
                      const SizedBox(height: 22),
                      _AiPulseHero(layout: layout),
                      const SizedBox(height: 18),
                      _AccountPanel(session: session, repository: dashboardRepository),
                      const SizedBox(height: 28),
                      Semantics(
                        header: true,
                        child: Text('Quick access', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900)),
                      ),
                      const SizedBox(height: 6),
                      Text(
                        'Move from market context to AI ideas and PAPER trading without exposing platform plumbing.',
                        style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
                      ),
                      const SizedBox(height: 16),
                      GridView.builder(
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        itemCount: _modules.length,
                        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                          crossAxisCount: ResponsiveLayout.workspaceColumns(constraints.maxWidth),
                          mainAxisSpacing: 12,
                          crossAxisSpacing: 12,
                          childAspectRatio: switch (layout) {
                            ResponsiveClass.mobile => 1.7,
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
    return Row(
      children: [
        Container(
          width: 46,
          height: 46,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(15),
            gradient: LinearGradient(colors: [theme.colorScheme.primary, theme.colorScheme.secondary]),
          ),
          child: const Icon(Icons.query_stats_rounded, color: Colors.white),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Semantics(
                header: true,
                child: Text('AiTradingSystem', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900)),
              ),
              Text('AI-powered PAPER trading', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
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
    return Container(
      width: double.infinity,
      padding: EdgeInsets.all(layout == ResponsiveClass.mobile ? 22 : 30),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(28),
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            scheme.primaryContainer.withValues(alpha: 0.95),
            scheme.secondaryContainer.withValues(alpha: 0.62),
            scheme.surfaceContainerHighest,
          ],
        ),
        border: Border.all(color: scheme.primary.withValues(alpha: 0.32)),
        boxShadow: [BoxShadow(color: scheme.primary.withValues(alpha: 0.10), blurRadius: 34, spreadRadius: 2)],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
            decoration: BoxDecoration(
              color: scheme.primary.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(999),
            ),
            child: Text(
              'AI MARKET PULSE',
              style: theme.textTheme.labelMedium?.copyWith(color: scheme.primary, fontWeight: FontWeight.w900, letterSpacing: 0.8),
            ),
          ),
          const SizedBox(height: 18),
          Semantics(
            header: true,
            child: Text(
              'See the market. Find the setup. Test it safely.',
              style: (layout == ResponsiveClass.desktop ? theme.textTheme.displaySmall : theme.textTheme.headlineMedium)
                  ?.copyWith(fontWeight: FontWeight.w900, height: 1.08),
            ),
          ),
          const SizedBox(height: 12),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 720),
            child: Text(
              'Use AI research to surface potential trades, review the risk, then execute with virtual money in PAPER mode before real-money trading is ever enabled.',
              style: theme.textTheme.bodyLarge?.copyWith(color: scheme.onSurfaceVariant, height: 1.5),
            ),
          ),
          const SizedBox(height: 18),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              FilledButton.icon(
                onPressed: () => Navigator.of(context).pushNamed('/research'),
                icon: const Icon(Icons.auto_awesome_rounded),
                label: const Text('Find AI opportunities'),
              ),
              OutlinedButton.icon(
                onPressed: () => Navigator.of(context).pushNamed('/market'),
                icon: const Icon(Icons.candlestick_chart_rounded),
                label: const Text('Explore markets'),
              ),
            ],
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
      case 'OK':
        return DataStateKind.ready;
      case 'STALE':
        return DataStateKind.stale;
      case 'HALTED':
      case 'RECONCILIATION_FAILED':
      case 'DEGRADED':
        return DataStateKind.degraded;
      default:
        return DataStateKind.unknown;
    }
  }

  Future<void> _refresh() async {
    if (!widget.session.isAuthenticated) return;
    setState(() {
      _loading = true;
      _error = null;
    });
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
    setState(() {
      _summary = null;
      _error = null;
      _loading = false;
    });
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
    return DataStatePanel(
      key: ValueKey('dashboard-state-${state.name}'),
      kind: state,
      title: title,
      message: message,
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final summary = _summary;
    final signedIn = widget.session.isAuthenticated;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(signedIn ? Icons.verified_user_rounded : Icons.lock_person_rounded, color: theme.colorScheme.primary),
                const SizedBox(width: 10),
                Expanded(
                  child: Semantics(
                    header: true,
                    child: Text(signedIn ? 'PAPER account' : 'Sign in required', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900)),
                  ),
                ),
                if (summary != null) const Chip(label: Text('PAPER')),
              ],
            ),
            const SizedBox(height: 8),
            if (!signedIn)
              Container(
                key: const ValueKey('dashboard-sign-in-required'),
                width: double.infinity,
                padding: const EdgeInsets.all(18),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(18),
                  color: theme.colorScheme.primaryContainer.withValues(alpha: 0.32),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Secure sign-in is required', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
                    const SizedBox(height: 6),
                    Text(
                      'Sign in to view your PAPER portfolio, AI opportunities, and account-specific trading controls.',
                      style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant, height: 1.45),
                    ),
                    const SizedBox(height: 14),
                    FilledButton.icon(
                      onPressed: () => Navigator.of(context).pushNamed('/sign-in'),
                      icon: const Icon(Icons.lock_open_rounded),
                      label: const Text('Secure sign in'),
                    ),
                  ],
                ),
              )
            else ...[
              _statePanel(),
              if (summary != null) ...[
                const SizedBox(height: 14),
                _SummaryGrid(summary: summary),
              ],
              const SizedBox(height: 14),
              Wrap(
                spacing: 10,
                children: [
                  OutlinedButton.icon(
                    key: const ValueKey('dashboard-refresh'),
                    onPressed: _loading ? null : _refresh,
                    icon: const Icon(Icons.refresh_rounded),
                    label: const Text('Refresh'),
                  ),
                  TextButton.icon(
                    key: const ValueKey('dashboard-disconnect'),
                    onPressed: _disconnect,
                    icon: const Icon(Icons.logout_rounded),
                    label: const Text('Sign out'),
                  ),
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
    final items = <(String, String)>[
      ('Equity', _money(summary.equity)),
      ('Available cash', _money(summary.availableCash)),
      ('Today P&L', _money(summary.dailyPnl)),
      ('Open positions', '${summary.openPositions}'),
      ('Market value', _money(summary.marketValue)),
      ('Unrealized P&L', _money(summary.unrealizedPnl)),
      ('Exposure', '${summary.exposurePercent.toStringAsFixed(2)}%'),
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (summary.newOrdersHalted) ...[
          const Chip(label: Text('ORDERS HALTED')),
          const SizedBox(height: 10),
        ],
        GridView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          itemCount: items.length,
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: columns,
            mainAxisSpacing: 10,
            crossAxisSpacing: 10,
            childAspectRatio: columns == 1 ? 4.2 : 2.3,
          ),
          itemBuilder: (context, index) {
            final item = items[index];
            return Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainerHighest.withValues(alpha: 0.55),
                borderRadius: BorderRadius.circular(16),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(item.$1, style: Theme.of(context).textTheme.labelMedium),
                  const SizedBox(height: 4),
                  Text(item.$2, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900)),
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
    return Semantics(
      button: true,
      label: '${module.title}. ${module.description}',
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: ValueKey('workspace-${module.route}'),
          onTap: () => Navigator.of(context).pushNamed(module.route),
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(module.icon, color: theme.colorScheme.primary, size: 27),
                    const Spacer(),
                    const Icon(Icons.arrow_forward_rounded, size: 19),
                  ],
                ),
                const Spacer(),
                Text(module.title, style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900)),
                const SizedBox(height: 6),
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
