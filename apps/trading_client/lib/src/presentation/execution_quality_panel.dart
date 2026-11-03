import 'package:flutter/material.dart';

import '../data/paper_trading_repository.dart';
import 'execution_quality_card.dart';

extension PaperOrderDetailCapability on PaperTradingRepository {
  Future<PaperOrderResult> fetchOrderDetail(String orderId) {
    final repository = this;
    if (repository is PaperOrderDetailRepository) {
      return repository.fetchOrderDetail(orderId);
    }
    throw StateError('PAPER order detail capability is unavailable');
  }
}

class ExecutionQualityPanel extends StatelessWidget {
  const ExecutionQualityPanel({
    required this.detail,
    super.key,
  });

  final PaperOrderResult detail;

  @override
  Widget build(BuildContext context) {
    final order = detail.order;
    final exactEvidence = detail.executionQuality;
    if (exactEvidence != null) {
      return ExecutionQualityCard(
        key: ValueKey('execution-quality-${order.id}'),
        quality: exactEvidence,
      );
    }

    final theme = Theme.of(context);
    final fill = detail.fill;
    final fillFraction = order.quantity <= 0 ? 0.0 : order.filledQuantity / order.quantity;

    return Container(
      key: ValueKey('execution-quality-${order.id}'),
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(18),
        color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.55),
        border: Border.all(color: theme.colorScheme.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.speed_rounded, size: 18, color: theme.colorScheme.primary),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  'Execution quality',
                  style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800),
                ),
              ),
              _StateChip(label: _fillState(order, fill)),
            ],
          ),
          const SizedBox(height: 14),
          _metricGrid(
            context,
            [
              ('Filled', '${order.filledQuantity}/${order.quantity}'),
              ('Fill rate', '${(fillFraction * 100).clamp(0, 100).toStringAsFixed(0)}%'),
              ('Average fill', _number(order.averageFillPrice ?? fill?.fillPrice)),
              ('Entry/reference', _number(order.entryPrice)),
            ],
          ),
          const SizedBox(height: 14),
          if (fill != null) ...[
            _metricGrid(
              context,
              [
                ('Persisted fees', _inr(fill.feeAmount)),
                ('Persisted slippage', _inr(fill.slippageAmount)),
                ('Gross amount', _inr(fill.grossAmount)),
                ('Net amount', _inr(fill.netAmount)),
              ],
            ),
            if (fill.realizedPnl != null) ...[
              const SizedBox(height: 10),
              Text(
                'Realized P&L ${_inr(fill.realizedPnl)}',
                key: ValueKey('execution-quality-realized-pnl-${order.id}'),
                style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w700),
              ),
            ],
          ] else ...[
            Text(
              'No persisted fill exists for this order yet. Realized execution cost is unavailable.',
              key: ValueKey('execution-quality-no-fill-${order.id}'),
              style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ],
          const SizedBox(height: 14),
          Container(
            key: ValueKey('execution-quality-tca-unavailable-${order.id}'),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(14),
              color: theme.colorScheme.surfaceContainerLow,
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.info_outline_rounded, size: 18, color: theme.colorScheme.onSurfaceVariant),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Expected-vs-realized TCA is not persisted for this ledger order yet. '
                    'The values above are only the backend’s stored realized fill amounts; no expected cost or cost-model version is inferred.',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                      height: 1.4,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _metricGrid(BuildContext context, List<(String, String)> values) {
    final theme = Theme.of(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth >= 520 ? (constraints.maxWidth - 12) / 2 : constraints.maxWidth;
        return Wrap(
          spacing: 12,
          runSpacing: 10,
          children: values
              .map(
                (value) => SizedBox(
                  width: width,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(12),
                      color: theme.colorScheme.surface,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          value.$1,
                          style: theme.textTheme.labelMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                        ),
                        const SizedBox(height: 2),
                        Text(value.$2, style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w800)),
                      ],
                    ),
                  ),
                ),
              )
              .toList(growable: false),
        );
      },
    );
  }

  String _fillState(PaperOrder order, PaperFill? fill) {
    if (fill == null || order.filledQuantity <= 0) return 'UNFILLED';
    if (order.quantity > 0 && order.filledQuantity < order.quantity) return 'PARTIAL';
    return 'FILLED';
  }

  String _number(num? value) => value == null ? '—' : value.toStringAsFixed(2);

  String _inr(num? value) => value == null ? '—' : '₹${value.toStringAsFixed(2)}';
}

class _StateChip extends StatelessWidget {
  const _StateChip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(999),
          color: Theme.of(context).colorScheme.primaryContainer,
        ),
        child: Text(label, style: Theme.of(context).textTheme.labelSmall?.copyWith(fontWeight: FontWeight.w900)),
      );
}
