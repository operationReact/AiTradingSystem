import 'package:flutter/material.dart';

class OverviewPage extends StatelessWidget {
  const OverviewPage({super.key});

  static const _modules = <_OverviewModule>[
    _OverviewModule(
      icon: Icons.auto_graph_rounded,
      title: 'Market intelligence',
      description: 'Monitor market data, research signals, and strategy context.',
    ),
    _OverviewModule(
      icon: Icons.radar_rounded,
      title: 'Signals',
      description: 'Review generated candidates and supporting evidence before action.',
    ),
    _OverviewModule(
      icon: Icons.account_balance_wallet_outlined,
      title: 'Portfolio',
      description: 'Track paper positions, exposure, orders, and portfolio state.',
    ),
    _OverviewModule(
      icon: Icons.shield_outlined,
      title: 'Risk controls',
      description: 'Keep risk, approvals, and safety gates visible throughout the workflow.',
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
              padding: EdgeInsets.fromLTRB(
                horizontalPadding,
                24,
                horizontalPadding,
                40,
              ),
              child: Center(
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 1180),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _Header(
                        onOperationsHealth: () => Navigator.of(context)
                            .pushNamed('/operations/health'),
                      ),
                      const SizedBox(height: 44),
                      Container(
                        width: double.infinity,
                        padding: EdgeInsets.all(wide ? 40 : 24),
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(28),
                          gradient: LinearGradient(
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
                            colors: [
                              scheme.primaryContainer,
                              scheme.surfaceContainerHighest,
                            ],
                          ),
                          border: Border.all(
                            color: scheme.outlineVariant.withValues(alpha: 0.7),
                          ),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 12,
                                vertical: 7,
                              ),
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
                              style: (wide
                                      ? theme.textTheme.displaySmall
                                      : theme.textTheme.headlineMedium)
                                  ?.copyWith(
                                fontWeight: FontWeight.w800,
                                height: 1.08,
                              ),
                            ),
                            const SizedBox(height: 14),
                            ConstrainedBox(
                              constraints: const BoxConstraints(maxWidth: 760),
                              child: Text(
                                'AiTradingSystem brings market intelligence, strategy research, paper execution, portfolio visibility, and risk controls into one operating surface.',
                                style: theme.textTheme.bodyLarge?.copyWith(
                                  color: scheme.onSurfaceVariant,
                                  height: 1.55,
                                ),
                              ),
                            ),
                            const SizedBox(height: 26),
                            Wrap(
                              spacing: 12,
                              runSpacing: 12,
                              children: [
                                FilledButton.icon(
                                  onPressed: () {},
                                  icon: const Icon(Icons.dashboard_rounded),
                                  label: const Text('Overview'),
                                ),
                                OutlinedButton.icon(
                                  onPressed: () => Navigator.of(context)
                                      .pushNamed('/operations/health'),
                                  icon: const Icon(Icons.monitor_heart_outlined),
                                  label: const Text('Operations health'),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 36),
                      Text(
                        'Core workspace',
                        style: theme.textTheme.headlineSmall?.copyWith(
                          fontWeight: FontWeight.w750,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Flutter is the production client. Additional workspace modules will be promoted here as their Flutter implementations pass the delivery gates.',
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: scheme.onSurfaceVariant,
                        ),
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
                        itemBuilder: (context, index) =>
                            _ModuleCard(module: _modules[index]),
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
  const _Header({required this.onOperationsHealth});

  final VoidCallback onOperationsHealth;

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
              Text(
                'AiTradingSystem',
                style: theme.textTheme.titleLarge?.copyWith(
                  fontWeight: FontWeight.w800,
                ),
              ),
              Text(
                'Flutter production client',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
        IconButton.filledTonal(
          tooltip: 'Operations health',
          onPressed: onOperationsHealth,
          icon: const Icon(Icons.monitor_heart_outlined),
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
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(module.icon, color: scheme.primary, size: 28),
            const Spacer(),
            Text(
              module.title,
              style: theme.textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 7),
            Text(
              module.description,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant,
                height: 1.4,
              ),
            ),
          ],
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
  });

  final IconData icon;
  final String title;
  final String description;
}
