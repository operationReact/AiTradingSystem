import 'package:flutter/material.dart';

import '../data/execution_quality_presentation.dart';

class ExecutionQualityCard extends StatelessWidget {
  const ExecutionQualityCard({required this.quality, super.key});

  final ExecutionQualityPresentation? quality;

  @override
  Widget build(BuildContext context) {
    final evidence = quality;
    if (evidence == null) {
      return Card(
        key: const ValueKey('execution-quality-unavailable'),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _heading(context),
              const SizedBox(height: 8),
              Text(
                'V3 execution-quality evidence is not available for this PAPER order yet. '
                'No expected-vs-realized metrics are inferred from legacy fills.',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                      height: 1.4,
                    ),
              ),
            ],
          ),
        ),
      );
    }

    final theme = Theme.of(context);
    return Card(
      key: const ValueKey('execution-quality-card'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _heading(context),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _statusChip(context, evidence.stateLabel),
                _statusChip(context, 'PAPER research evidence'),
                if (evidence.costModelVersion != null)
                  _statusChip(context, evidence.costModelVersion!),
              ],
            ),
            const SizedBox(height: 14),
            _MetricRow(
              label: 'Fill completion',
              value: '${(evidence.fillFraction * 100).toStringAsFixed(1)}%',
              key: const ValueKey('execution-quality-fill-completion'),
            ),
            if (evidence.hasExpectedMetrics) ...[
              _MetricRow(
                label: 'Expected total cost',
                value: _bps(evidence.expectedTotalCostBps),
                key: const ValueKey('execution-quality-expected-total'),
              ),
              _MetricRow(
                label: 'Expected slippage',
                value: _bps(evidence.expectedSlippageBps),
                key: const ValueKey('execution-quality-expected-slippage'),
              ),
            ],
            if (evidence.hasRealizedMetrics) ...[
              const Divider(height: 24),
              _MetricRow(
                label: 'Realized total cost',
                value: _bps(evidence.realizedTotalCostBps),
                key: const ValueKey('execution-quality-realized-total'),
              ),
              _MetricRow(
                label: 'Realized slippage',
                value: _bps(evidence.realizedSlippageBps),
                key: const ValueKey('execution-quality-realized-slippage'),
              ),
              _MetricRow(
                label: 'Cost vs plan',
                value: _signedBps(evidence.totalCostDeltaBps),
                key: const ValueKey('execution-quality-cost-delta'),
              ),
              if (evidence.realizedAverageFillPrice != null)
                _MetricRow(
                  label: 'Average fill',
                  value: evidence.realizedAverageFillPrice!.toStringAsFixed(2),
                  key: const ValueKey('execution-quality-average-fill'),
                ),
            ],
            if (evidence.arrivalGapBps != null)
              _MetricRow(
                label: 'Arrival gap',
                value: _signedBps(evidence.arrivalGapBps),
                key: const ValueKey('execution-quality-arrival-gap'),
              ),
            if (evidence.reasonCodes.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text(
                evidence.reasonCodes.map(_friendlyReason).join(' • '),
                key: const ValueKey('execution-quality-reasons'),
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _heading(BuildContext context) => Row(
        children: [
          Icon(
            Icons.speed_rounded,
            size: 20,
            color: Theme.of(context).colorScheme.primary,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'Execution quality',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w800,
                  ),
            ),
          ),
        ],
      );

  Widget _statusChip(BuildContext context, String label) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(999),
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
        ),
        child: Text(
          label,
          style: Theme.of(context).textTheme.labelSmall?.copyWith(
                fontWeight: FontWeight.w700,
              ),
        ),
      );

  static String _bps(double? value) => value == null ? '—' : '${value.toStringAsFixed(2)} bps';

  static String _signedBps(double? value) {
    if (value == null) return '—';
    final prefix = value > 0 ? '+' : '';
    return '$prefix${value.toStringAsFixed(2)} bps';
  }

  static String _friendlyReason(String value) => value
      .toLowerCase()
      .split('_')
      .where((part) => part.isNotEmpty)
      .map((part) => '${part[0].toUpperCase()}${part.substring(1)}')
      .join(' ');
}

class _MetricRow extends StatelessWidget {
  const _MetricRow({required this.label, required this.value, super.key});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: [
            Expanded(
              child: Text(
                label,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
            ),
            const SizedBox(width: 12),
            Text(
              value,
              textAlign: TextAlign.right,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    fontWeight: FontWeight.w800,
                  ),
            ),
          ],
        ),
      );
}
