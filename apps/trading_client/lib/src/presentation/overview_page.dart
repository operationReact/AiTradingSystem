import 'package:flutter/material.dart';

class OverviewPage extends StatelessWidget {
  const OverviewPage({super.key});

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
                      const SizedBox(height: 44),
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
                            const SizedBox(height: 26),
                            FilledButton.icon(
                              onPressed: () => Navigator.of(context).pushNamed('/market'),
                              icon: const Icon(Icons.explore_rounded),
                              label: const Text('Enter workspace'),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 36),
                      Text(
                        'Workspace',
                        style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700),
                      ),
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
          decoration: BoxDecoration(
            color: scheme.primaryContainer,
            borderRadius: BorderRadius.circular(13),
          ),
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
          itemBuilder: (_) => const [
            PopupMenuItem(value: 'health', child: Text('System health')),
          ],
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
                  child: Padding(
                    padding: const EdgeInsets.all(28),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Icon(Icons.data_usage_rounded, color: scheme.primary),
                        const SizedBox(height: 18),
                        Text(emptyTitle, style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
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
  const _OverviewModule({
    required this.icon,
    required this.title,
    required this.description,
    required this.route,
  });

  final IconData icon;
  final String title;
  final String description;
  final String route;
}
