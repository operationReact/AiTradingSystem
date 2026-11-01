import 'package:flutter/material.dart';

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
      appBar: AppBar(title: const Text('Portfolio')),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _refresh,
          child: ListView(
            key: const ValueKey('paper-portfolio-list'),
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
            children: [
              Container(
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(24),
                  gradient: LinearGradient(
                    colors: [theme.colorScheme.primaryContainer, theme.colorScheme.surfaceContainerHighest],
                  ),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Semantics(
                      header: true,
                      child: Text('PAPER portfolio', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w900)),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Practice with simulated money while tracking positions, cash, exposure, and profit or loss in one place.',
                      style: theme.textTheme.bodyLarge?.copyWith(color: theme.colorScheme.onSurfaceVariant, height: 1.45),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              if (_loading && _portfolio == null)
                const Center(
                  key: ValueKey('paper-portfolio-loading'),
                  child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator()),
                )
              else if (_failure != null && _portfolio == null)
                _errorCard(_failure!)
              else if (_portfolio != null) ...[
                if (_failure != null) _staleCard(),
                _summary(_portfolio!),
                const SizedBox(height: 16),
                Semantics(header: true, child: Text('Open positions', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800))),
                const SizedBox(height: 8),
                if (_portfolio!.positions.isEmpty)
                  const Card(
                    key: ValueKey('paper-portfolio-empty-positions'),
                    child: Padding(padding: EdgeInsets.all(16), child: Text('No PAPER positions are open yet.')),
                  )
                else
                  ..._portfolio!.positions.map(_positionCard),
              ] else
                const Card(
                  key: ValueKey('paper-portfolio-empty'),
                  child: Padding(padding: EdgeInsets.all(16), child: Text('Your PAPER portfolio is not available yet.')),
                ),
            ],
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: const ValueKey('paper-portfolio-refresh'),
        onPressed: _loading ? null : _refresh,
        icon: const Icon(Icons.refresh_rounded),
        label: const Text('Refresh'),
      ),
    );
  }

  Widget _errorCard(ApiFailure failure) => Card(
        key: const ValueKey('paper-portfolio-error'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Portfolio unavailable', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
              const SizedBox(height: 8),
              Text(_failureMessage(failure)),
              const SizedBox(height: 12),
              FilledButton(onPressed: _refresh, child: const Text('Try again')),
            ],
          ),
        ),
      );

  Widget _staleCard() => const Card(
        key: ValueKey('paper-portfolio-stale'),
        child: Padding(
          padding: EdgeInsets.all(16),
          child: Text('Could not refresh. Showing your last loaded PAPER portfolio.'),
        ),
      );

  Widget _summary(PaperPortfolio portfolio) {
    final safeMode = portfolio.mode == 'PAPER' && !portfolio.liveTradingEnabled;
    return Card(
      key: const ValueKey('paper-portfolio-summary'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: Text('Account overview', style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800))),
                Chip(label: Text(safeMode ? 'PAPER' : portfolio.mode)),
              ],
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 24,
              runSpacing: 16,
              children: [
                _metric('Equity', portfolio.equity),
                _metric('Available cash', portfolio.cash),
                _metric('Invested', portfolio.invested),
                _metric('Market value', portfolio.marketValue),
                _metric('Realized P&L', portfolio.realizedPnl),
                _metric('Unrealized P&L', portfolio.unrealizedPnl),
                _metric('Today P&L', portfolio.realizedPnlToday),
                _metric('Exposure', '${portfolio.exposurePercent}%'),
                _metric('Open positions', portfolio.openPositions),
              ],
            ),
            if (portfolio.newOrdersHalted) ...[
              const SizedBox(height: 14),
              const Text('New PAPER orders are currently paused.', style: TextStyle(fontWeight: FontWeight.w700)),
            ],
            const SizedBox(height: 12),
            Text('Updated ${portfolio.asOf?.toLocal().toString() ?? 'recently'}', style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
      ),
    );
  }

  Widget _metric(String label, Object? value) => SizedBox(
        width: 150,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: Theme.of(context).textTheme.labelMedium),
            const SizedBox(height: 4),
            Text(value?.toString() ?? '—', style: const TextStyle(fontWeight: FontWeight.w800)),
          ],
        ),
      );

  Widget _positionCard(PaperPosition position) => Card(
        key: ValueKey('paper-position-${position.instrumentId ?? position.symbol}'),
        child: ListTile(
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
