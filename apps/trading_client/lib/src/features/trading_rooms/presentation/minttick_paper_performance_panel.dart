import 'package:flutter/material.dart';

import '../data/customer_paper_trading_repository.dart';
import '../data/customer_room_repository.dart';
import 'minttick_customer_widgets.dart';

/// Read-only performance observations from the customer-owned virtual ledger.
/// A 100-fill API window is NOT the complete trade history or an equity curve.
class PaperPerformanceSnapshot {
  const PaperPerformanceSnapshot({
    required this.recentFills,
    required this.openPositions,
    required this.recentFeesPaise,
    required this.recentTurnoverPaise,
  });

  final int recentFills;
  final int openPositions;
  final int recentFeesPaise;
  final int recentTurnoverPaise;

  factory PaperPerformanceSnapshot.from({
    required List<CustomerPaperOrder> fills,
    required List<CustomerPaperPosition> positions,
  }) => PaperPerformanceSnapshot(
    recentFills: fills.length,
    openPositions: positions.where((p) => p.quantity > 0).length,
    recentFeesPaise: fills.fold<int>(0, (sum, fill) => sum + fill.feesPaise),
    recentTurnoverPaise: fills.fold<int>(
      0, (sum, fill) => sum + fill.notionalPaise),
  );
}

String formatVirtualPaise(int paise) {
  final amount = paise.abs();
  final whole = amount ~/ 100;
  final fraction = (amount % 100).toString().padLeft(2, '0');
  return '${paise < 0 ? '−' : ''}₹$whole.$fraction';
}

/// Server-origin realized P&L is authoritative; marks/unrealized/peak equity
/// cannot be derived from order fills and are intentionally not invented.
class MintTickPaperPerformancePanel extends StatefulWidget {
  const MintTickPaperPerformancePanel({
    required this.roomId,
    required this.wallet,
    required this.repository,
    super.key,
  });

  final String roomId;
  final CustomerPaperWallet wallet;
  final HttpCustomerPaperTradingRepository repository;

  @override
  State<MintTickPaperPerformancePanel> createState() =>
      _MintTickPaperPerformancePanelState();
}

class _MintTickPaperPerformancePanelState
    extends State<MintTickPaperPerformancePanel> {
  PaperPerformanceSnapshot? _snapshot;
  bool _loading = false;
  String? _error;
  DateTime? _fetchedAt;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (_loading) return;
    setState(() {
      _loading = true;
      _error = null;
      _snapshot = null;
      _fetchedAt = null;
    });
    try {
      final results = await Future.wait<Object>([
        widget.repository.orders(widget.roomId),
        widget.repository.positions(widget.roomId),
      ]);
      if (!mounted) return;
      setState(() {
        _snapshot = PaperPerformanceSnapshot.from(
          fills: results[0] as List<CustomerPaperOrder>,
          positions: results[1] as List<CustomerPaperPosition>,
        );
        _fetchedAt = DateTime.now();
      });
    } catch (_) {
      if (mounted) {
        setState(() => _error =
          'Paper performance could not be verified. '
          'No totals are shown until the server responds.');
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final styles = Theme.of(context).textTheme;
    final realized = widget.wallet.realizedPnlPaise;
    final initial = widget.wallet.initialCashPaise;
    // Display-only percentage; never used for money/risk authorization.
    final realizedPercent = initial > 0
        ? (realized * 100 / initial).toStringAsFixed(2)
        : '—';
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            const Expanded(child:
              MintTickSectionHeading('Paper performance')),
            IconButton(
              key: const ValueKey('paper-performance-refresh'),
              tooltip: 'Refresh virtual performance',
              onPressed: _loading ? null : _load,
              icon: const Icon(Icons.refresh_rounded),
            ),
          ]),
          const SizedBox(height: 5),
          Text('Simulated trades · Account-specific · No broker capital',
              style: styles.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
          const SizedBox(height: 16),
          const MintTickEyebrow('Realized P&L · Server ledger'),
          const SizedBox(height: 6),
          Text(formatVirtualPaise(realized),
            key: const ValueKey('paper-performance-realized'),
            style: styles.headlineMedium?.copyWith(
              fontWeight: FontWeight.w800,
              color: realized < 0 ? scheme.error : scheme.onSurface)),
          const SizedBox(height: 9),
          _Metric('Realized / opening virtual cash', '$realizedPercent%'),
          _Metric('Starting virtual allocation', formatVirtualPaise(initial)),
          _Metric('Available virtual cash',
            formatVirtualPaise(widget.wallet.availableCashPaise)),
          _Metric('Manual order state',
            widget.wallet.halted ? 'Paused' : 'Enabled'),
          const SizedBox(height: 10),
          Text('Realized P&L does not include open-position value. '
               'It is not a total-return or annualized performance figure.',
            style: styles.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
        ],
      )),
      const SizedBox(height: 12),
      MintTickSurface(child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const MintTickSectionHeading('Recent trading activity'),
          if (_loading) ...[
            const SizedBox(height: 12),
            const LinearProgressIndicator(key: ValueKey('paper-performance-loading')),
          ],
          if (_error != null) ...[
            const SizedBox(height: 12),
            MintTickInfoText(_error!, icon: Icons.error_outline),
          ],
          if (_snapshot != null) ...[
            const SizedBox(height: 12),
            _Metric('Recent confirmed fills (max 100)',
              '${_snapshot!.recentFills}'),
            _Metric('Open virtual positions', '${_snapshot!.openPositions}'),
            _Metric('Recent modeled fees',
              formatVirtualPaise(_snapshot!.recentFeesPaise)),
            _Metric('Recent traded notional',
              formatVirtualPaise(_snapshot!.recentTurnoverPaise)),
            const SizedBox(height: 8),
            Text('Latest read: ${_fetchedAt?.toLocal().toString().split('.').first ?? 'Unknown'}',
              style: styles.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
            const SizedBox(height: 7),
            Text('Totals here cover only the latest 100 returned receipts, '
                 'not lifetime trading activity. Costs are an approximate '
                 '0.05% fill model (min ₹1), excluding full taxes and slippage.',
              style: styles.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
          ],
        ],
      )),
      const SizedBox(height: 12),
      const MintTickInfoText(
        'Unrealized P&L, equity curve, win rate, drawdown and risk-adjusted '
        'returns are unavailable until verified mark-to-market prices and '
        'complete historical equity snapshots are supported. '
        'No estimates are substituted.',
        icon: Icons.insights_outlined,
      ),
    ]);
  }
}

class _Metric extends StatelessWidget {
  const _Metric(this.label, this.value);
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final small = Theme.of(context).textTheme.bodySmall;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(children: [
        Expanded(child: Text(label, style: small)),
        const SizedBox(width: 10),
        Flexible(child: Text(value, textAlign: TextAlign.end,
          style: small?.copyWith(fontWeight: FontWeight.w800))),
      ]),
    );
  }
}
