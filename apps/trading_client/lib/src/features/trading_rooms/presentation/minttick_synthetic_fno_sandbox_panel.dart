import 'dart:math';

import 'package:flutter/material.dart';

import '../../../data/authenticated_api_client.dart';
import '../data/customer_paper_fno_repository.dart';

/// Entirely separate training market. Its prices are invented fixed values
/// and its wallet is NOT the room's exchange-backed PAPER wallet.
class MintTickSyntheticFnoSandboxPanel extends StatefulWidget {
  const MintTickSyntheticFnoSandboxPanel({
    required this.roomId,
    required this.repository,
    this.onFilled,
    super.key,
  });
  final String roomId;
  final CustomerPaperFnoRepository repository;
  final Future<void> Function()? onFilled;

  @override
  State<MintTickSyntheticFnoSandboxPanel> createState() =>
      _MintTickSyntheticFnoSandboxPanelState();
}

class _MintTickSyntheticFnoSandboxPanelState
    extends State<MintTickSyntheticFnoSandboxPanel> {
  CustomerFnoSandboxState? _state;
  bool _loading = true;
  bool _submitting = false;
  String? _error;
  String? _message;
  int _lots = 1;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (mounted) setState(() => _loading = true);
    try {
      final state = await widget.repository.sandboxState(widget.roomId);
      if (!mounted) return;
      setState(() {
        _state = state;
        _error = null;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _state = null;
        _error = 'Synthetic practice is not available on the server. '
            'No virtual order was placed.';
        _loading = false;
      });
    }
  }

  static final _random = Random.secure();
  static String _key() =>
      'sim_${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}_'
      '${_random.nextInt(1 << 31).toRadixString(36)}';

  Future<void> _trade(CustomerFnoSandboxContract contract, String side) async {
    final state = _state;
    if (state == null || !state.ledgerReconciled || _submitting) return;
    setState(() {
      _submitting = true;
      _message = null;
    });
    try {
      final fill = await widget.repository.sandboxSubmit(
        roomId: widget.roomId,
        idempotencyKey: _key(),
        contractCode: contract.code,
        side: side,
        lots: _lots,
      );
      if (!mounted) return;
      setState(() {
        _message = 'Synthetic ${fill.side} ${fill.quantity} units at '
            '₹${fill.fillPrice}. Practice wallet updated.';
      });
      await _load();
      await widget.onFilled?.call();
    } catch (error) {
      if (!mounted) return;
      final definite = error is ApiHttpFailure &&
          error.statusCode != null && error.statusCode! >= 400 &&
          error.statusCode! < 500;
      setState(() => _message = definite
          ? 'Practice order rejected. No fill was recorded.'
          : 'Practice order outcome unknown. Refresh history before any new order.');
      await _load(); // Never automatically repeat an unknown order.
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  int _heldLots(CustomerFnoSandboxState state, String code) {
    var qty = 0;
    for (final pos in state.positions) {
      if (pos.contractCode == code) qty += pos.quantity;
    }
    return qty ~/ 10;
  }

  static String _money(int paise) => '₹${(paise / 100).toStringAsFixed(2)}';

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme;
    final state = _state;
    return Container(
      key: const ValueKey('synthetic-fno-sandbox'),
      margin: const EdgeInsets.only(top: 14),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: colors.surfaceContainerLow,
        border: Border.all(color: colors.outlineVariant),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('Options practice · Synthetic', style: style.titleSmall),
        const SizedBox(height: 5),
        Text('TRAINING ONLY · Invented prices, no NSE/Upstox data, '
            'no broker orders. Separate virtual wallet.',
            style: style.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
        const SizedBox(height: 12),
        if (_loading)
          const Center(child: Padding(
            padding: EdgeInsets.all(12),
            child: CircularProgressIndicator(strokeWidth: 2)))
        else if (_error != null)
          Text(_error!, key: const ValueKey('synthetic-fno-unavailable'),
              style: style.bodySmall?.copyWith(color: colors.error))
        else if (state != null) ...[
          Wrap(spacing: 20, runSpacing: 8, children: [
            _metric('Practice cash', _money(state.availableCashPaise), style),
            _metric('Realized P&L', _money(state.realizedPnlPaise), style),
            _metric('Practice fills', '${state.recordedFills}', style),
          ]),
          const SizedBox(height: 12),
          if (!state.ledgerReconciled)
            Text('Ledger mismatch: all practice orders disabled.',
                key: const ValueKey('synthetic-fno-ledger-blocked'),
                style: style.bodySmall?.copyWith(color: colors.error))
          else ...[
            Row(children: [
              Text('Lots (10 units each)', style: style.bodySmall),
              const Spacer(),
              DropdownButton<int>(
                key: const ValueKey('synthetic-fno-lots'),
                value: _lots,
                items: const [1, 2, 3, 5, 10, 20].map((v) =>
                    DropdownMenuItem(value: v, child: Text('$v'))).toList(),
                onChanged: _submitting
                    ? null : (v) { if (v != null) setState(() => _lots = v); },
              ),
            ]),
            for (final contract in state.contracts)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(contract.label, style: style.bodyMedium),
                  Text('Synthetic BUY ₹${contract.buyPrice} · '
                      'SELL ₹${contract.sellPrice} · '
                      'Held ${_heldLots(state, contract.code)} lots',
                      style: style.bodySmall?.copyWith(color: colors.onSurfaceVariant)),
                  const SizedBox(height: 6),
                  Row(children: [
                    OutlinedButton(
                      key: ValueKey('synthetic-buy-${contract.code}'),
                      onPressed: _submitting ? null : () => _trade(contract, 'BUY'),
                      child: const Text('Practice BUY'),
                    ),
                    const SizedBox(width: 10),
                    OutlinedButton(
                      key: ValueKey('synthetic-sell-${contract.code}'),
                      onPressed: _submitting || _heldLots(state, contract.code) < _lots
                          ? null : () => _trade(contract, 'SELL'),
                      child: const Text('Close SELL'),
                    ),
                  ]),
                ]),
              ),
          ],
          if (_message != null) ...[
            Text(_message!, key: const ValueKey('synthetic-fno-message'),
                style: style.bodySmall),
            const SizedBox(height: 8),
          ],
          Text('Open practice positions: ${state.positions.length}',
              key: const ValueKey('synthetic-fno-positions')),
          for (final p in state.positions)
            Text('${p.contractCode}: ${p.quantity} units · '
                'cost ${_money(p.costBasisPaise)}', style: style.bodySmall),
          const SizedBox(height: 8),
          Text('Practice history', style: style.titleSmall),
          if (state.orders.isEmpty)
            Text('No simulated orders yet.', style: style.bodySmall)
          else
            for (final fill in state.orders.take(5))
              Text('${fill.side} ${fill.contractCode} · ${fill.quantity} units '
                  'at ₹${fill.fillPrice}',
                  style: style.bodySmall),
        ],
        const SizedBox(height: 5),
        TextButton.icon(
          key: const ValueKey('synthetic-fno-refresh'),
          onPressed: _loading || _submitting ? null : _load,
          icon: const Icon(Icons.refresh_rounded, size: 18),
          label: const Text('Refresh practice'),
        ),
      ]),
    );
  }

  static Widget _metric(String name, String value, TextTheme styles) =>
      Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(name, style: styles.labelSmall),
        Text(value, style: styles.titleSmall),
      ]);
}
