import 'package:flutter/material.dart';

import '../core/widgets/responsive_layout.dart';
import '../data/authenticated_api_client.dart';
import '../data/paper_trading_repository.dart';
import 'customer_lifecycle_state.dart';

class PaperPortfolioPage extends StatefulWidget {
  const PaperPortfolioPage({required this.repository, super.key});

  final PaperTradingRepository repository;

  @override
  State<PaperPortfolioPage> createState() => _PaperPortfolioPageState();
}

class _PaperPortfolioPageState extends State<PaperPortfolioPage> {
  PaperPortfolio? _portfolio;
  ApiFailure? _failure;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _failure = null;
    });
    try {
      final portfolio = await widget.repository.fetchPortfolio();
      if (!mounted) return;
      setState(() => _portfolio = portfolio);
    } on ApiFailure catch (failure) {
      if (!mounted) return;
      setState(() => _failure = failure);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  String _failureMessage(ApiFailure failure) {
    final code = failure.code.toUpperCase();
    if (code.contains('AUTH')) return 'Sign in to view your PAPER portfolio.';
    if (code.contains('NETWORK') || code.contains('TIMEOUT')) return 'We could not reach the trading service. Try again.';
    return 'Your PAPER portfolio is temporarily unavailable.';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('Portfolio'),
        actions: [
          IconButton(
            key: const ValueKey('paper-portfolio-refresh'),
            tooltip: 'Refresh portfolio',
            onPressed: _loading ? null : _refresh,
            icon: _loading
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Icon(Icons.refresh_rounded),
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final width = constraints.maxWidth;
            final mobile = width < ResponsiveLayout.mobileBreakpoint;
            final pagePadding = ResponsiveLayout.horizontalPadding(width);
            return RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                key: const ValueKey('paper-portfolio-list'),
                padding: EdgeInsets.fromLTRB(pagePadding, ResponsiveLayout.topPadding(width), pagePadding, 24),
                children: [
                  Container(
                    padding: EdgeInsets.all(ResponsiveLayout.heroPadding(width)),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(ResponsiveLayout.heroRadius(width)),
                      gradient: LinearGradient(
                        colors: [theme.colorScheme.primaryContainer, theme.colorScheme.surfaceContainerHighest],
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Semantics(
                          header: true,
                          child: Text(
                            'PAPER portfolio',
                            style: (mobile ? theme.textTheme.titleLarge : theme.textTheme.headlineSmall)?.copyWith(fontWeight: FontWeight.w900),
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          'Track simulated cash, positions, exposure, and profit or loss in one place.',
                          style: (mobile ? theme.textTheme.bodyMedium : theme.textTheme.bodyLarge)?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                            height: mobile ? 1.3 : 1.45,
                          ),
                        ),
                      ],
                    ),
                  ),
                  SizedBox(height: mobile ? 10 : 16),
                  if (_loading && _portfolio == null)
                    const Center(
                      key: ValueKey('paper-portfolio-loading'),
                      child: Padding(padding: EdgeInsets.all(24), child: CircularProgressIndicator()),
                    )
                  else if (_failure != null && _portfolio == null)
                    _errorCard(_failure!, compact: mobile)
                  else if (_portfolio != null) ...[
                    if (_failure != null) _staleCard(compact: mobile),
                    _summary(_portfolio!, compact: mobile),
                    SizedBox(height: mobile ? 12 : 16),
                    Semantics(
                      header: true,
                      child: Text('Open positions', style: (mobile ? theme.textTheme.titleMedium : theme.textTheme.titleLarge)?.copyWith(fontWeight: FontWeight.w800)),
                    ),
                    const SizedBox(height: 8),
                    if (_portfolio!.positions.isEmpty)
                      Card(
                        key: const ValueKey('paper-portfolio-empty-positions'),
                        child: Padding(padding: EdgeInsets.all(mobile ? 12 : 16), child: const Text('No PAPER positions are open yet.')),
                      )
                    else
                      ..._portfolio!.positions.map(_positionCard),
                  ] else
                    Card(
                      key: const ValueKey('paper-portfolio-empty'),
                      child: Padding(padding: EdgeInsets.all(mobile ? 12 : 16), child: const Text('Your PAPER portfolio is not available yet.')),
                    ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _errorCard(ApiFailure failure, {required bool compact}) => Card(
        key: const ValueKey('paper-portfolio-error'),
        child: Padding(
          padding: EdgeInsets.all(compact ? 12 : 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Portfolio unavailable', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 6),
              Text(_failureMessage(failure), style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
              const SizedBox(height: 10),
              SizedBox(
                width: compact ? double.infinity : null,
                child: FilledButton(onPressed: _refresh, child: const Text('Try again')),
              ),
            ],
          ),
        ),
      );

  Widget _staleCard({required bool compact}) => Card(
        key: const ValueKey('paper-portfolio-stale'),
        child: Padding(
          padding: EdgeInsets.all(compact ? 12 : 16),
          child: const Text('Could not refresh. Showing your last loaded PAPER portfolio.'),
        ),
      );

  Widget _summary(PaperPortfolio portfolio, {required bool compact}) {
    final safeMode = portfolio.mode == 'PAPER' && !portfolio.liveTradingEnabled;
    return Card(
      key: const ValueKey('paper-portfolio-summary'),
      child: Padding(
        padding: EdgeInsets.all(compact ? 12 : 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: Text('Account overview', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800))),
                Chip(label: Text(safeMode ? 'PAPER' : portfolio.mode)),
              ],
            ),
            SizedBox(height: compact ? 10 : 16),
            Wrap(
              spacing: compact ? 16 : 24,
              runSpacing: compact ? 10 : 16,
              children: [
                _metric('Equity', portfolio.equity, compact: compact),
                _metric('Available cash', portfolio.cash, compact: compact),
                _metric('Invested', portfolio.invested, compact: compact),
                _metric('Market value', portfolio.marketValue, compact: compact),
                _metric('Realized P&L', portfolio.realizedPnl, compact: compact),
                _metric('Unrealized P&L', portfolio.unrealizedPnl, compact: compact),
                _metric('Today P&L', portfolio.realizedPnlToday, compact: compact),
                _metric('Exposure', '${portfolio.exposurePercent}%', compact: compact),
                _metric('Open positions', portfolio.openPositions, compact: compact),
              ],
            ),
            if (portfolio.newOrdersHalted) ...[
              const SizedBox(height: 10),
              const Text('New PAPER orders are currently paused.', style: TextStyle(fontWeight: FontWeight.w700)),
            ],
            const SizedBox(height: 8),
            Text('Updated ${portfolio.asOf?.toLocal().toString() ?? 'recently'}', style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
      ),
    );
  }

  Widget _metric(String label, Object? value, {required bool compact}) => SizedBox(
        width: compact ? 136 : 150,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: Theme.of(context).textTheme.labelMedium),
            const SizedBox(height: 2),
            Text(value?.toString() ?? '—', style: const TextStyle(fontWeight: FontWeight.w800)),
          ],
        ),
      );

  Widget _positionCard(PaperPosition position) => Card(
        key: ValueKey('paper-position-${position.instrumentId ?? position.symbol}'),
        child: ListTile(
          dense: true,
          title: Row(
            children: [
              Expanded(child: Text('${position.exchange} ${position.symbol}')),
              const SizedBox(width: 8),
              const CustomerLifecycleBadge(state: CustomerLifecycleState.openPosition),
            ],
          ),
          subtitle: Text(
            '${position.productType} • ${position.quantity} shares • Avg ${position.averageCost ?? '—'} • Last ${position.markPrice ?? '—'}\n'
            'P&L ${position.unrealizedPnl ?? '—'}',
          ),
          isThreeLine: true,
        ),
      );
}
