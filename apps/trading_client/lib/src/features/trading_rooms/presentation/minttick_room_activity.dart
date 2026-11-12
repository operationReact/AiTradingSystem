
import 'package:flutter/material.dart';
import '../data/customer_paper_trading_repository.dart';

/// Dense real Paper-room position / fill lists. Never fabricates P&L or
/// maps missing broker positions onto PAPER data.
class MintTickRoomActivity extends StatefulWidget {
  const MintTickRoomActivity({
    required this.repository, required this.roomId,
    required this.ordersView, super.key,
  });
  final HttpCustomerPaperTradingRepository repository;
  final String roomId;
  final bool ordersView;

  @override
  State<MintTickRoomActivity> createState() => _MintTickRoomActivityState();
}

class _MintTickRoomActivityState extends State<MintTickRoomActivity> {
  bool _loading = true;
  String? _error;
  List<CustomerPaperOrder> _orders = const [];
  List<CustomerPaperPosition> _positions = const [];

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    setState(() { _loading = true; _error = null; });
    try {
      final all = await Future.wait<Object>([
        widget.repository.orders(widget.roomId),
        widget.repository.positions(widget.roomId),
      ]);
      if (!mounted) return;
      setState(() {
        _orders = all[0] as List<CustomerPaperOrder>;
        _positions = all[1] as List<CustomerPaperPosition>;
      });
    } catch (_) {
      if (mounted) setState(() {
        _orders = const []; _positions = const [];
        _error = 'Unable to verify this room activity. Retry when connected.';
      });
    } finally {
      if (mounted) { setState(() => _loading = false); }
    }
  }

  @override
  Widget build(BuildContext context) {
    final styles = Theme.of(context).textTheme;
    final colors = Theme.of(context).colorScheme;
    final activePositions = _positions.where((item) => item.quantity > 0).toList();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Expanded(child: Text(widget.ordersView ? 'Paper fill history' : 'Open Paper positions',
          style: styles.titleSmall?.copyWith(fontWeight: FontWeight.w700))),
        IconButton(onPressed: _loading ? null : _reload,
          tooltip: 'Refresh Paper activity',
          icon: const Icon(Icons.refresh_rounded, size: 19)),
      ]),
      if (_loading) const LinearProgressIndicator(minHeight: 2),
      if (_error != null) Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Text(_error!, style: styles.bodySmall?.copyWith(color: colors.error))),
      if (!_loading && _error == null) ...[
        if (widget.ordersView && _orders.isEmpty ||
            !widget.ordersView && activePositions.isEmpty)
          Padding(padding: const EdgeInsets.symmetric(vertical: 16),
            child: Text(widget.ordersView
              ? 'No Paper fills recorded.' : 'No open Paper positions.',
              style: styles.bodySmall?.copyWith(color: colors.onSurfaceVariant))),
        if (widget.ordersView)
          for (final order in _orders.take(40))
            _CompactActivityRow(
              title: '${order.side} · ${order.tradingSymbol}',
              subtitle: '${order.quantity} shares · ${order.exchange}',
              trailing: '₹${order.fillPrice}',
            )
        else
          for (final position in activePositions.take(50))
            _CompactActivityRow(
              title: 'Instrument #${position.instrumentId}',
              subtitle: '${position.quantity} shares · average cost',
              trailing: '₹${(position.avgCostPaise / 100).toStringAsFixed(2)}',
            ),
      ],
      const SizedBox(height: 8),
      Text('PAPER is simulated. These rows are from this room only.',
        style: styles.labelSmall?.copyWith(color: colors.onSurfaceVariant)),
    ]);
  }
}

class _CompactActivityRow extends StatelessWidget {
  const _CompactActivityRow({
    required this.title, required this.subtitle, required this.trailing,
  });
  final String title, subtitle, trailing;

  @override
  Widget build(BuildContext context) {
    final type = Theme.of(context).textTheme;
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 9, horizontal: 2),
      decoration: BoxDecoration(border: Border(
        bottom: BorderSide(color: scheme.outlineVariant, width: .6))),
      child: Row(children: [
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, maxLines: 1, overflow: TextOverflow.ellipsis,
            style: type.bodyMedium?.copyWith(fontWeight: FontWeight.w600)),
          Text(subtitle, style: type.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
        ])),
        const SizedBox(width: 8),
        Text(trailing, style: type.bodySmall?.copyWith(fontWeight: FontWeight.w700)),
      ]),
    );
  }
}
