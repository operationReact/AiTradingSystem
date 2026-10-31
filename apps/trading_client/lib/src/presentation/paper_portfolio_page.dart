import 'package:flutter/material.dart';

import '../data/authenticated_api_client.dart';
import '../data/paper_trading_repository.dart';

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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('PAPER portfolio')),
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _refresh,
          child: ListView(
            key: const ValueKey('paper-portfolio-list'),
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
            children: [
              Semantics(
                header: true,
                child: Text('Authoritative PAPER account', style: Theme.of(context).textTheme.headlineSmall),
              ),
              const SizedBox(height: 8),
              const Text('Read-only portfolio evidence from the Spring/PostgreSQL PAPER accounting service. This screen cannot submit LIVE orders.'),
              const SizedBox(height: 16),
              if (_loading && _portfolio == null)
                const Center(
                  key: ValueKey('paper-portfolio-loading'),
                  child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator()),
                )
              else if (_failure != null && _portfolio == null)
                _errorCard(_failure!)
              else if (_portfolio != null) ...[
                if (_failure != null) _staleCard(_failure!),
                _summary(_portfolio!),
                const SizedBox(height: 16),
                Semantics(header: true, child: Text('Positions', style: Theme.of(context).textTheme.titleLarge)),
                const SizedBox(height: 8),
                if (_portfolio!.positions.isEmpty)
                  const Card(
                    key: ValueKey('paper-portfolio-empty-positions'),
                    child: Padding(padding: EdgeInsets.all(16), child: Text('No open PAPER positions are reported by the backend.')),
                  )
                else
                  ..._portfolio!.positions.map(_positionCard),
              ] else
                const Card(
                  key: ValueKey('paper-portfolio-empty'),
                  child: Padding(padding: EdgeInsets.all(16), child: Text('No authoritative PAPER portfolio evidence is available.')),
                ),
            ],
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: const ValueKey('paper-portfolio-refresh'),
        onPressed: _loading ? null : _refresh,
        icon: const Icon(Icons.refresh),
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
              Text('Portfolio unavailable', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Text(failure.code),
              if (failure.correlationId != null) Text('Correlation: ${failure.correlationId}'),
              const SizedBox(height: 12),
              FilledButton(onPressed: _refresh, child: const Text('Retry')),
            ],
          ),
        ),
      );

  Widget _staleCard(ApiFailure failure) => Card(
        key: const ValueKey('paper-portfolio-stale'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Text('Refresh failed (${failure.code}). Values below are last-known evidence and must not be treated as current.'),
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
            Wrap(
              spacing: 12,
              runSpacing: 8,
              children: [
                Chip(label: Text('Mode ${portfolio.mode}')),
                Chip(label: Text(portfolio.liveTradingEnabled ? 'LIVE ENABLED' : 'LIVE DISABLED')),
                Chip(label: Text(portfolio.newOrdersHalted ? 'NEW ORDERS HALTED' : 'NEW ORDERS ALLOWED')),
                Chip(label: Text(portfolio.cashReconciled ? 'CASH RECONCILED' : 'CASH NOT RECONCILED')),
              ],
            ),
            if (!safeMode) ...[
              const SizedBox(height: 12),
              const Text('Fail-safe warning: backend mode evidence is not a confirmed PAPER-only state. Do not infer order eligibility from this screen.'),
            ],
            const SizedBox(height: 16),
            Wrap(
              spacing: 24,
              runSpacing: 16,
              children: [
                _metric('Equity', portfolio.equity),
                _metric('Cash', portfolio.cash),
                _metric('Invested', portfolio.invested),
                _metric('Market value', portfolio.marketValue),
                _metric('Realized P&L', portfolio.realizedPnl),
                _metric('Unrealized P&L', portfolio.unrealizedPnl),
                _metric('Today realized P&L', portfolio.realizedPnlToday),
                _metric('Exposure %', portfolio.exposurePercent),
                _metric('Open positions', portfolio.openPositions),
              ],
            ),
            const SizedBox(height: 12),
            Text('Mark source: ${portfolio.markPriceSource ?? 'UNKNOWN'}'),
            Text('As of: ${portfolio.asOf?.toIso8601String() ?? 'UNKNOWN'}'),
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
            Text(label),
            const SizedBox(height: 4),
            Text(value?.toString() ?? 'UNKNOWN', style: const TextStyle(fontWeight: FontWeight.w700)),
          ],
        ),
      );

  Widget _positionCard(PaperPosition position) => Card(
        key: ValueKey('paper-position-${position.instrumentId ?? position.symbol}'),
        child: ListTile(
          title: Text('${position.exchange} ${position.symbol}'),
          subtitle: Text(
            '${position.productType} • qty ${position.quantity} • avg ${position.averageCost ?? 'UNKNOWN'} • mark ${position.markPrice ?? 'UNKNOWN'}\n'
            'Unrealized P&L ${position.unrealizedPnl ?? 'UNKNOWN'} • opened ${position.openedAt?.toIso8601String() ?? 'UNKNOWN'}',
          ),
          isThreeLine: true,
        ),
      );
}
