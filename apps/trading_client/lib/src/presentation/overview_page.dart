import 'package:flutter/material.dart';

import '../data/health_repository.dart';
import '../data/operator_session.dart';
import 'data_state_panel.dart';
import 'responsive_layout.dart';

class OverviewPage extends StatelessWidget {
  const OverviewPage({
    required this.session,
    required this.dashboardRepository,
    super.key,
  });

  final OperatorSession session;
  final DashboardRepository dashboardRepository;

  static const _modules = <_OverviewModule>[
    _OverviewModule(
      icon: Icons.auto_graph_rounded,
      title: 'Market intelligence',
      description: 'Monitor market context, research inputs, instruments, and data quality.',
      route: '/market',
    ),
    _OverviewModule(
      icon: Icons.radar_rounded,
      title: 'Signals',
      description: 'Review generated candidates and supporting evidence before action.',
      route: '/signals',
    ),
    _OverviewModule(
      icon: Icons.account_balance_wallet_outlined,
      title: 'Portfolio',
      description: 'Track PAPER positions, exposure, orders, and portfolio state.',
      route: '/portfolio',
    ),
    _OverviewModule(
      icon: Icons.shield_outlined,
      title: 'Risk controls',
      description: 'Review risk state, approvals, and safety controls before execution.',
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
            final desktop = layout == ResponsiveClass.desktop;
            final horizontalPadding = ResponsiveLayout.horizontalPadding(constraints.maxWidth);

            return SingleChildScrollView(
              padding: EdgeInsets.fromLTRB(horizontalPadding, 24, horizontalPadding, 40),
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 1180),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const _Header(),
                      const SizedBox(height: 32),
                      Container(
                        width: double.infinity,
                        padding: EdgeInsets.all(ResponsiveLayout.heroPadding(constraints.maxWidth)),
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(28),
                          gradient: LinearGradient(
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
                            colors: [scheme.primaryContainer, scheme.surfaceContainerHighest],
                          ),
                          border: Border.all(color: scheme.outlineVariant.withValues(alpha: 0.7)),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Container(
                              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
                              decoration: BoxDecoration(
                                color: scheme.primary.withValues(alpha: 0.10),
                                borderRadius: BorderRadius.circular(999),
                              ),
                              child: Text(
                                'AI-ASSISTED TRADING RESEARCH',
                                style: theme.textTheme.labelMedium?.copyWith(
                                  color: scheme.primary,
                                  fontWeight: FontWeight.w700,
                                  letterSpacing: 0.7,
                                ),
                              ),
                            ),
                            const SizedBox(height: 20),
                            Semantics(
                              header: true,
                              child: Text(
                                'Research. Validate. Operate with control.',
                                style: (desktop ? theme.textTheme.displaySmall : theme.textTheme.headlineMedium)
                                    ?.copyWith(fontWeight: FontWeight.w800, height: 1.08),
                              ),
                            ),
                            const SizedBox(height: 14),
                            ConstrainedBox(
                              constraints: const BoxConstraints(maxWidth: 760),
                              child: Text(
                                'Market intelligence, signal research, PAPER execution, portfolio visibility, and risk controls in one operating workspace.',
                                style: theme.textTheme.bodyLarge?.copyWith(
                                  color: scheme.onSurfaceVariant,
                                  height: 1.55,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 24),
                      _DashboardPanel(session: session, repository: dashboardRepository),
                      const SizedBox(height: 36),
                      Semantics(
                        header: true,
                        child: Text('Workspace', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700)),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Choose a workspace to review market evidence, trading candidates, portfolio state, or execution controls.',
                        style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
                      ),
                      const SizedBox(height: 20),
                      GridView.builder(
                        key: ValueKey('workspace-grid-${layout.name}'),
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        itemCount: _modules.length,
                        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                          crossAxisCount: ResponsiveLayout.workspaceColumns(constraints.maxWidth),
                          mainAxisSpacing: 16,
                          crossAxisSpacing: 16,
                          childAspectRatio: switch (layout) {
                            ResponsiveClass.mobile => 1.75,
                            ResponsiveClass.adaptive => 1.45,
                            ResponsiveClass.desktop => 1.15,
                          },
                        ),
                        itemBuilder: (context, index) => _ModuleCard(module: _modules[index]),
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

class _DashboardPanel extends StatefulWidget {
  const _DashboardPanel({required this.session, required this.repository});

  final OperatorSession session;
  final DashboardRepository repository;

  @override
  State<_DashboardPanel> createState() => _DashboardPanelState();
}

class _DashboardPanelState extends State<_DashboardPanel> {
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

  DataStateKind get _dataState {
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
        setState(() => _error = 'Dashboard data is temporarily unavailable.');
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
    final state = _dataState;
    final (title, message) = switch (state) {
      DataStateKind.empty => ('No authoritative data loaded', 'Sign in to request current account and trading evidence.'),
      DataStateKind.loading => (
          _summary == null ? 'Loading authoritative state' : 'Refreshing authoritative state',
          _summary == null
              ? 'Waiting for the server response. No inferred trading state is shown.'
              : 'Last successful evidence remains visible while a fresh server response is requested.',
        ),
      DataStateKind.ready => ('Authoritative state available', 'The values below come from the connected trading service.'),
      DataStateKind.stale => ('Last-known state only', _error ?? 'The server marked this evidence as stale. Refresh before relying on it.'),
      DataStateKind.degraded => ('Degraded operating state', 'The server reports a halt, reconciliation failure, or degraded condition. Treat execution readiness as unavailable.'),
      DataStateKind.unknown => ('Operating state UNKNOWN', 'The server status is not recognized as healthy. The client fails closed and does not infer readiness.'),
      DataStateKind.error => ('Authoritative data unavailable', _error ?? 'Unable to load current server evidence.'),
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
    final scheme = theme.colorScheme;
    final summary = _summary;
    final signedIn = widget.session.isAuthenticated;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(22),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(signedIn ? Icons.verified_user_rounded : Icons.person_outline_rounded, color: scheme.primary),
                const SizedBox(width: 10),
                Expanded(
                  child: Semantics(
                    header: true,
                    child: Text(
                      signedIn ? 'Trading account' : 'Sign in required',
                      style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                    ),
                  ),
                ),
                if (summary != null) _StatusChip(label: summary.mode, positive: summary.mode == 'PAPER'),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              signedIn
                  ? 'Your authenticated session loads authoritative portfolio and operating state automatically.'
                  : 'Sign in to view account-scoped portfolio, market, and risk evidence. No developer credentials are accepted here.',
              style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
            ),
            const SizedBox(height: 16),
            if (!signedIn)
              Container(
                key: const ValueKey('dashboard-sign-in-required'),
                width: double.infinity,
                padding: const EdgeInsets.all(18),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(16),
                  color: scheme.primaryContainer.withValues(alpha: 0.35),
                  border: Border.all(color: scheme.outlineVariant),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.lock_person_rounded, color: scheme.primary),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('Secure sign-in is required', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
                          const SizedBox(height: 4),
                          Text(
                            'Production authentication is handled through your account session. The previous operator access-key flow has been removed.',
                            style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant, height: 1.45),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              )
            else ...[
              _statePanel(),
              if (summary != null) ...[
                const SizedBox(height: 16),
                _SummaryGrid(summary: summary),
              ],
              const SizedBox(height: 16),
              Wrap(
                spacing: 10,
                runSpacing: 10,
                children: [
                  OutlinedButton.icon(
                    key: const ValueKey('dashboard-refresh'),
                    onPressed: _loading ? null : _refresh,
                    icon: _loading
                        ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.refresh_rounded),
                    label: Text(_loading ? 'Refreshing' : 'Refresh'),
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
    final scheme = Theme.of(context).colorScheme;
    final viewportWidth = MediaQuery.sizeOf(context).width;
    final columns = ResponsiveLayout.metricColumns(viewportWidth);
    final items = [
      ('Equity', _money(summary.equity)),
      ('Available cash', _money(summary.availableCash)),
      ('Daily P&L', _money(summary.dailyPnl)),
      ('Open positions', '${summary.openPositions}'),
      ('Market value', _money(summary.marketValue)),
      ('Unrealized P&L', _money(summary.unrealizedPnl)),
      ('Exposure', '${summary.exposurePercent.toStringAsFixed(2)}%'),
      ('System', summary.systemStatus),
    ];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _StatusChip(label: summary.systemStatus, positive: summary.systemStatus == 'OK'),
            _StatusChip(label: summary.cashReconciled ? 'RECONCILED' : 'NOT RECONCILED', positive: summary.cashReconciled),
            if (summary.newOrdersHalted) const _StatusChip(label: 'ORDERS HALTED', positive: false),
          ],
        ),
        const SizedBox(height: 16),
        GridView.builder(
          key: ValueKey('summary-grid-${ResponsiveLayout.classify(viewportWidth).name}'),
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
            return Semantics(
              label: '${item.$1}: ${item.$2}',
              child: Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: scheme.surfaceContainerHighest.withValues(alpha: 0.55),
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(item.$1, style: Theme.of(context).textTheme.labelMedium?.copyWith(color: scheme.onSurfaceVariant)),
                    const SizedBox(height: 4),
                    Text(item.$2, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
                  ],
                ),
              ),
            );
          },
        ),
      ],
    );
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.label, required this.positive});

  final String label;
  final bool positive;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Semantics(
      label: label,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: positive ? scheme.primaryContainer : scheme.errorContainer,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Text(
          label,
          style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: positive ? scheme.onPrimaryContainer : scheme.onErrorContainer,
                fontWeight: FontWeight.w700,
              ),
        ),
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Row(
      children: [
        Container(
          width: 42,
          height: 42,
          decoration: BoxDecoration(color: scheme.primaryContainer, borderRadius: BorderRadius.circular(13)),
          child: Icon(Icons.query_stats_rounded, color: scheme.primary),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Semantics(
                header: true,
                child: Text('AiTradingSystem', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
              ),
              Text('Trading research & risk workspace', style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
            ],
          ),
        ),
        PopupMenuButton<String>(
          tooltip: 'More',
          onSelected: (value) {
            if (value == 'health') Navigator.of(context).pushNamed('/operations/health');
          },
          itemBuilder: (_) => const [PopupMenuItem(value: 'health', child: Text('System health'))],
        ),
      ],
    );
  }
}

class _ModuleCard extends StatelessWidget {
  const _ModuleCard({required this.module});

  final _OverviewModule module;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Semantics(
      button: true,
      label: '${module.title}. ${module.description}',
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: ValueKey('workspace-${module.route}'),
          onTap: () => Navigator.of(context).pushNamed(module.route),
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(module.icon, color: scheme.primary, size: 28),
                    const Spacer(),
                    Icon(Icons.arrow_forward_rounded, color: scheme.onSurfaceVariant, size: 20),
                  ],
                ),
                const Spacer(),
                Text(module.title, style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
                const SizedBox(height: 7),
                Text(
                  module.description,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant, height: 1.4),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class WorkspacePage extends StatelessWidget {
  const WorkspacePage({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.emptyTitle,
    required this.emptyBody,
    super.key,
  });

  const WorkspacePage.market({super.key})
      : title = 'Market intelligence',
        subtitle = 'Market context, instruments, research inputs, and data quality',
        icon = Icons.auto_graph_rounded,
        emptyTitle = 'Market evidence will appear here',
        emptyBody = 'Connected market-data and research evidence is shown only when authoritative data is available.';

  const WorkspacePage.signals({super.key})
      : title = 'Signals',
        subtitle = 'Candidate research, provenance, and supporting evidence',
        icon = Icons.radar_rounded,
        emptyTitle = 'No signal selected',
        emptyBody = 'Generated candidates remain research evidence until explicitly reviewed and approved.';

  const WorkspacePage.portfolio({super.key})
      : title = 'Portfolio',
        subtitle = 'PAPER positions, orders, exposure, and accounting state',
        icon = Icons.account_balance_wallet_outlined,
        emptyTitle = 'Portfolio state will appear here',
        emptyBody = 'Positions and accounting are shown from authoritative PAPER execution records.';

  const WorkspacePage.risk({super.key})
      : title = 'Risk controls',
        subtitle = 'Risk policy, approvals, emergency controls, and execution safety',
        icon = Icons.shield_outlined,
        emptyTitle = 'Risk evidence will appear here',
        emptyBody = 'Unknown or unavailable control evidence is never presented as healthy.';

  final String title;
  final String subtitle;
  final IconData icon;
  final String emptyTitle;
  final String emptyBody;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final width = MediaQuery.sizeOf(context).width;
    final padding = ResponsiveLayout.horizontalPadding(width);
    return Scaffold(
      appBar: AppBar(
        title: Semantics(header: true, child: Text(title)),
        actions: [
          IconButton(
            tooltip: 'System health',
            onPressed: () => Navigator.of(context).pushNamed('/operations/health'),
            icon: const Icon(Icons.monitor_heart_outlined),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 1180),
            child: ListView(
              padding: EdgeInsets.fromLTRB(padding, 24, padding, 40),
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(color: scheme.primaryContainer, borderRadius: BorderRadius.circular(16)),
                      child: Icon(icon, color: scheme.primary, size: 28),
                    ),
                    const SizedBox(width: 16),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Semantics(
                            header: true,
                            child: Text(title, style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800)),
                          ),
                          const SizedBox(height: 4),
                          Text(subtitle, style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant)),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 28),
                Card(
                  key: const ValueKey('workspace-state-empty'),
                  child: Padding(
                    padding: const EdgeInsets.all(28),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Icon(Icons.data_usage_rounded, color: scheme.primary),
                        const SizedBox(height: 18),
                        Semantics(
                          header: true,
                          child: Text(emptyTitle, style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
                        ),
                        const SizedBox(height: 8),
                        Text(emptyBody, style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant, height: 1.5)),
                      ],
                    ),
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

class _OverviewModule {
  const _OverviewModule({required this.icon, required this.title, required this.description, required this.route});

  final IconData icon;
  final String title;
  final String description;
  final String route;
}
