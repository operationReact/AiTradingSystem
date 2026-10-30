import 'package:flutter/material.dart';

import '../data/health_repository.dart';
import '../data/operator_session.dart';

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
            final wide = constraints.maxWidth >= 900;
            final horizontalPadding = wide ? 56.0 : 20.0;

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
                        padding: EdgeInsets.all(wide ? 40 : 24),
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
                            Text(
                              'Research. Validate. Operate with control.',
                              style: (wide ? theme.textTheme.displaySmall : theme.textTheme.headlineMedium)
                                  ?.copyWith(fontWeight: FontWeight.w800, height: 1.08),
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
                      Text('Workspace', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700)),
                      const SizedBox(height: 8),
                      Text(
                        'Choose a workspace to review market evidence, trading candidates, portfolio state, or execution controls.',
                        style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
                      ),
                      const SizedBox(height: 20),
                      GridView.builder(
                        shrinkWrap: true,
                        physics: const NeverScrollableScrollPhysics(),
                        itemCount: _modules.length,
                        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                          crossAxisCount: wide ? 4 : (constraints.maxWidth >= 600 ? 2 : 1),
                          mainAxisSpacing: 16,
                          crossAxisSpacing: 16,
                          childAspectRatio: wide ? 1.15 : 1.75,
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

enum _AuthoritativeDataState { empty, loading, ready, stale, degraded, unknown, error }

class _DashboardPanel extends StatefulWidget {
  const _DashboardPanel({required this.session, required this.repository});
  final OperatorSession session;
  final DashboardRepository repository;

  @override
  State<_DashboardPanel> createState() => _DashboardPanelState();
}

class _DashboardPanelState extends State<_DashboardPanel> {
  final _controller = TextEditingController();
  DashboardSummary? _summary;
  String? _error;
  bool _loading = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  _AuthoritativeDataState get _dataState {
    final summary = _summary;
    if (_loading && summary == null) return _AuthoritativeDataState.loading;
    if (_error != null && summary == null) return _AuthoritativeDataState.error;
    if (_error != null && summary != null) return _AuthoritativeDataState.stale;
    if (summary == null) return _AuthoritativeDataState.empty;

    switch (summary.systemStatus.trim().toUpperCase()) {
      case 'OK':
        return _AuthoritativeDataState.ready;
      case 'STALE':
        return _AuthoritativeDataState.stale;
      case 'HALTED':
      case 'RECONCILIATION_FAILED':
      case 'DEGRADED':
        return _AuthoritativeDataState.degraded;
      default:
        return _AuthoritativeDataState.unknown;
    }
  }

  Future<void> _connect() async {
    widget.session.setOperatorKey(_controller.text);
    if (!widget.session.isAuthenticated) {
      setState(() => _error = 'Enter your access key to connect.');
      return;
    }
    await _refresh();
  }

  Future<void> _refresh() async {
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
      setState(() => _error = 'Operator access is required.');
    } on DashboardHttpFailure catch (failure) {
      if (!mounted) return;
      setState(() => _error = failure.statusCode == 401 || failure.statusCode == 403
          ? 'The access key was rejected.'
          : 'Dashboard data is temporarily unavailable.');
    } on DashboardFailure {
      if (!mounted) return;
      setState(() => _error = 'Unable to reach the trading service.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _clear() {
    widget.session.clear();
    _controller.clear();
    setState(() {
      _summary = null;
      _error = null;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final summary = _summary;
    final state = _dataState;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(22),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.lock_outline_rounded, color: scheme.primary),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Operator access',
                    style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
                  ),
                ),
                if (summary != null) _StatusChip(label: summary.mode, positive: summary.mode == 'PAPER'),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              summary == null
                  ? 'Connect to load authoritative portfolio and operating state. Credentials are kept only for this session.'
                  : 'Server state remains authoritative. Last successful evidence stays visible during refresh failures.',
              style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
            ),
            const SizedBox(height: 16),
            _DataStateNotice(state: state, error: _error, refreshing: _loading && summary != null),
            const SizedBox(height: 16),
            if (summary == null) ...[
              TextField(
                key: const ValueKey('dashboard-access-key'),
                controller: _controller,
                obscureText: true,
                enableSuggestions: false,
                autocorrect: false,
                decoration: const InputDecoration(labelText: 'Access key', border: OutlineInputBorder()),
                onSubmitted: (_) => _loading ? null : _connect(),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                key: const ValueKey('dashboard-connect'),
                onPressed: _loading ? null : _connect,
                icon: _loading
                    ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.login_rounded),
                label: const Text('Connect workspace'),
              ),
            ] else ...[
              _SummaryGrid(summary: summary),
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
                    onPressed: _clear,
                    icon: const Icon(Icons.logout_rounded),
                    label: const Text('Disconnect'),
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

class _DataStateNotice extends StatelessWidget {
  const _DataStateNotice({required this.state, this.error, this.refreshing = false});

  final _AuthoritativeDataState state;
  final String? error;
  final bool refreshing;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;

    final (icon, title, body, background, foreground, key) = switch (state) {
      _AuthoritativeDataState.empty => (
          Icons.inbox_outlined,
          'No authoritative data loaded',
          'Connect explicitly to request current server evidence.',
          scheme.surfaceContainerHighest,
          scheme.onSurfaceVariant,
          const ValueKey('dashboard-state-empty'),
        ),
      _AuthoritativeDataState.loading => (
          Icons.sync_rounded,
          'Loading authoritative state',
          'Waiting for the server response. No inferred trading state is shown.',
          scheme.primaryContainer,
          scheme.onPrimaryContainer,
          const ValueKey('dashboard-state-loading'),
        ),
      _AuthoritativeDataState.ready => (
          Icons.verified_outlined,
          'Authoritative state available',
          'The values below come from the connected trading service.',
          scheme.primaryContainer,
          scheme.onPrimaryContainer,
          const ValueKey('dashboard-state-ready'),
        ),
      _AuthoritativeDataState.stale => (
          Icons.history_rounded,
          'Last-known state only',
          error ?? 'The server marked this evidence as stale. Refresh before relying on it.',
          scheme.tertiaryContainer,
          scheme.onTertiaryContainer,
          const ValueKey('dashboard-state-stale'),
        ),
      _AuthoritativeDataState.degraded => (
          Icons.warning_amber_rounded,
          'Degraded operating state',
          'The server reports a halt, reconciliation failure, or degraded condition. Treat execution readiness as unavailable.',
          scheme.errorContainer,
          scheme.onErrorContainer,
          const ValueKey('dashboard-state-degraded'),
        ),
      _AuthoritativeDataState.unknown => (
          Icons.help_outline_rounded,
          'Operating state UNKNOWN',
          'The server status is not recognized as healthy. The client fails closed and does not infer readiness.',
          scheme.errorContainer,
          scheme.onErrorContainer,
          const ValueKey('dashboard-state-unknown'),
        ),
      _AuthoritativeDataState.error => (
          Icons.cloud_off_outlined,
          'Authoritative data unavailable',
          error ?? 'Unable to load current server evidence.',
          scheme.errorContainer,
          scheme.onErrorContainer,
          const ValueKey('dashboard-state-error'),
        ),
    };

    return Semantics(
      key: key,
      container: true,
      liveRegion: true,
      label: '$title. $body',
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(color: background, borderRadius: BorderRadius.circular(14)),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, color: foreground),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: TextStyle(color: foreground, fontWeight: FontWeight.w700)),
                  const SizedBox(height: 4),
                  Text(body, style: TextStyle(color: foreground)),
                  if (refreshing) ...[
                    const SizedBox(height: 10),
                    LinearProgressIndicator(color: foreground),
                  ],
                ],
              ),
            ),
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
    return LayoutBuilder(
      builder: (context, constraints) {
        final columns = constraints.maxWidth >= 900 ? 4 : (constraints.maxWidth >= 560 ? 2 : 1);
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
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _StatusChip(label: summary.systemStatus, positive: summary.systemStatus == 'OK'),
                _StatusChip(
                  label: summary.cashReconciled ? 'RECONCILED' : 'NOT RECONCILED',
                  positive: summary.cashReconciled,
                ),
                if (summary.newOrdersHalted) const _StatusChip(label: 'ORDERS HALTED', positive: false),
              ],
            ),
            const SizedBox(height: 16),
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
                    color: scheme.surfaceContainerHighest.withValues(alpha: 0.55),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(
                        item.$1,
                        style: Theme.of(context).textTheme.labelMedium?.copyWith(color: scheme.onSurfaceVariant),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        item.$2,
                        style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
                      ),
                    ],
                  ),
                );
              },
            ),
          ],
        );
      },
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
    return Container(
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
              Text('AiTradingSystem', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
              Text(
                'Trading research & risk workspace',
                style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
              ),
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
    return Card(
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
    return Scaffold(
      appBar: AppBar(
        title: Text(title),
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
              padding: const EdgeInsets.fromLTRB(24, 24, 24, 40),
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: scheme.primaryContainer,
                        borderRadius: BorderRadius.circular(16),
                      ),
                      child: Icon(icon, color: scheme.primary, size: 28),
                    ),
                    const SizedBox(width: 16),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(title, style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w800)),
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
                        Text(emptyTitle, style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
                        const SizedBox(height: 8),
                        Text(
                          emptyBody,
                          style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant, height: 1.5),
                        ),
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
