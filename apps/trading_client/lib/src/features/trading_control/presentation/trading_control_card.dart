import 'package:flutter/material.dart';

import '../../../data/authenticated_api_client.dart';
import '../../../theme/app_theme.dart';
import '../data/trading_control_repository.dart';

class TradingControlCard extends StatefulWidget {
  const TradingControlCard({required this.repository, super.key});

  final TradingControlRepository repository;

  @override
  State<TradingControlCard> createState() => _TradingControlCardState();
}

class _TradingControlCardState extends State<TradingControlCard> {
  TradingControlState? _state;
  bool _loading = true;
  bool _saving = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final state = await widget.repository.load();
      if (!mounted) return;
      setState(() {
        _state = state;
        _loading = false;
        _message = null;
      });
    } on ApiAuthenticationRequired {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _message = 'Sign in to manage trading controls.';
      });
    } on ApiFailure {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _message = 'Trading controls are temporarily unavailable.';
      });
    }
  }

  Future<void> _setExecutionMode(OrderExecutionMode mode) async {
    if (_saving || _state == null || _state!.executionMode == mode) return;
    setState(() => _saving = true);
    try {
      final state = await widget.repository.update(executionMode: mode);
      if (!mounted) return;
      setState(() {
        _state = state;
        _saving = false;
        _message = mode == OrderExecutionMode.automated
            ? 'Auto Trading enabled for PAPER. Risk checks remain mandatory.'
            : 'Manual PAPER execution enabled.';
      });
    } on ApiFailure {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _message = 'Could not update execution mode. Try again.';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final tradingColors = theme.extension<TradingColors>()!;

    if (_loading) {
      return const Card(
        key: ValueKey('trading-control-loading'),
        child: Padding(
          padding: EdgeInsets.all(24),
          child: Center(child: CircularProgressIndicator()),
        ),
      );
    }

    final state = _state;
    if (state == null) {
      return Card(
        key: const ValueKey('trading-control-unavailable'),
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Row(
            children: [
              const Icon(Icons.lock_outline_rounded),
              const SizedBox(width: 12),
              Expanded(child: Text(_message ?? 'Trading controls are unavailable.')),
            ],
          ),
        ),
      );
    }

    return Container(
      key: const ValueKey('trading-control-card'),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(22),
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF081526), Color(0xFF100C28)],
        ),
        border: Border.all(color: tradingColors.neonCyan.withValues(alpha: 0.35)),
        boxShadow: [BoxShadow(color: tradingColors.neonCyan.withValues(alpha: 0.10), blurRadius: 28)],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 42,
                height: 42,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(14),
                  color: tradingColors.neonCyan.withValues(alpha: 0.10),
                ),
                child: Icon(Icons.shield_rounded, color: tradingColors.neonCyan),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '${state.tradingMode == TradingMode.paper ? 'PAPER' : 'LIVE'} · ${state.executionMode == OrderExecutionMode.automated ? 'AUTOMATED' : 'MANUAL'}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900),
                    ),
                    const SizedBox(height: 2),
                    Text('Risk Engine: Active', style: theme.textTheme.bodySmall?.copyWith(color: tradingColors.neonCyan, fontWeight: FontWeight.w700)),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          _StatusPill(
            label: state.automatedPaper ? 'Auto Trading: Running' : 'Manual Trading',
            icon: state.automatedPaper ? Icons.bolt_rounded : Icons.touch_app_rounded,
          ),
          const SizedBox(height: 20),
          Text('Trading mode', style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w800)),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: SegmentedButton<TradingMode>(
              key: const ValueKey('trading-mode-control'),
              showSelectedIcon: false,
              segments: const [
                ButtonSegment(value: TradingMode.paper, icon: Icon(Icons.science_outlined), label: Text('Paper')),
                ButtonSegment(value: TradingMode.live, icon: Icon(Icons.lock_outline_rounded), label: Text('Live')),
              ],
              selected: {state.tradingMode},
              onSelectionChanged: null,
            ),
          ),
          const SizedBox(height: 7),
          Text(
            state.liveAvailable
                ? 'Live trading requires an explicit confirmation before activation.'
                : 'Live trading is locked until broker execution and live-safety verification are complete.',
            style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant, height: 1.35),
          ),
          const SizedBox(height: 18),
          Text('Execution mode', style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w800)),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: SegmentedButton<OrderExecutionMode>(
              key: const ValueKey('execution-mode-control'),
              showSelectedIcon: false,
              segments: const [
                ButtonSegment(value: OrderExecutionMode.manual, icon: Icon(Icons.touch_app_outlined), label: Text('Manual')),
                ButtonSegment(value: OrderExecutionMode.automated, icon: Icon(Icons.auto_awesome_outlined), label: Text('Automated')),
              ],
              selected: {state.executionMode},
              onSelectionChanged: _saving
                  ? null
                  : (selection) {
                      if (selection.isNotEmpty) _setExecutionMode(selection.first);
                    },
            ),
          ),
          if (_saving) ...[
            const SizedBox(height: 10),
            const LinearProgressIndicator(),
          ],
          if (_message != null) ...[
            const SizedBox(height: 10),
            Text(_message!, style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant)),
          ],
        ],
      ),
    );
  }
}

class _StatusPill extends StatelessWidget {
  const _StatusPill({required this.label, required this.icon});

  final String label;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).extension<TradingColors>()!;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        color: colors.neonCyan.withValues(alpha: 0.09),
        border: Border.all(color: colors.neonCyan.withValues(alpha: 0.24)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14, color: colors.neonCyan),
          const SizedBox(width: 5),
          Flexible(child: Text(label, style: TextStyle(fontSize: 10.5, fontWeight: FontWeight.w800, color: colors.neonCyan))),
        ],
      ),
    );
  }
}
