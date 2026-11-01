import 'package:flutter/material.dart';

import '../../../data/authenticated_api_client.dart';
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
    if (_saving || _state == null) return;
    setState(() => _saving = true);
    try {
      final state = await widget.repository.update(executionMode: mode);
      if (!mounted) return;
      setState(() {
        _state = state;
        _saving = false;
        _message = mode == OrderExecutionMode.automated
            ? 'Automated PAPER execution enabled. Every order still passes risk checks.'
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
    if (_loading) {
      return const Card(
        key: ValueKey('trading-control-loading'),
        child: Padding(
          padding: EdgeInsets.all(20),
          child: Center(child: CircularProgressIndicator()),
        ),
      );
    }

    if (_state == null) {
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

    final state = _state!;
    return Card(
      key: const ValueKey('trading-control-card'),
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.shield_moon_outlined, color: scheme.primary),
                const SizedBox(width: 10),
                Expanded(
                  child: Text('Trading controls', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
                ),
                Chip(label: Text(state.tradingMode == TradingMode.paper ? 'PAPER' : 'LIVE')),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              'Choose how simulated orders are placed. Risk checks apply in both modes.',
              style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant, height: 1.4),
            ),
            const SizedBox(height: 18),
            Text('Trading mode', style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 8),
            SegmentedButton<TradingMode>(
              key: const ValueKey('trading-mode-control'),
              showSelectedIcon: false,
              segments: const [
                ButtonSegment(value: TradingMode.paper, icon: Icon(Icons.science_outlined), label: Text('Paper')),
                ButtonSegment(value: TradingMode.live, icon: Icon(Icons.lock_outline), label: Text('Live')),
              ],
              selected: {state.tradingMode},
              onSelectionChanged: null,
            ),
            const SizedBox(height: 6),
            Text(
              state.liveAvailable
                  ? 'Live trading is available after confirmation.'
                  : 'Live trading stays locked until broker and safety verification are complete.',
              style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
            ),
            const SizedBox(height: 18),
            Text('Execution', style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 8),
            SegmentedButton<OrderExecutionMode>(
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
      ),
    );
  }
}
