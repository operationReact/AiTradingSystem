import 'package:flutter/material.dart';

import '../../../data/authenticated_api_client.dart';
import '../../../theme/app_theme.dart';
import '../data/paper_runtime_repository.dart';

class PaperRuntimeCard extends StatefulWidget {
  const PaperRuntimeCard({required this.repository, super.key});
  final PaperRuntimeRepository repository;

  @override
  State<PaperRuntimeCard> createState() => _PaperRuntimeCardState();
}

class _PaperRuntimeCardState extends State<PaperRuntimeCard> {
  PaperRuntimeState? _state;
  bool _loading = true;
  bool _saving = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    try {
      final state = await widget.repository.fetch();
      if (!mounted) return;
      setState(() { _state = state; _loading = false; _message = null; });
    } on ApiAuthenticationRequired {
      if (!mounted) return;
      setState(() { _loading = false; _message = 'Sign in to view PAPER automation.'; });
    } on ApiFailure {
      if (!mounted) return;
      setState(() { _loading = false; _message = 'Trading status is temporarily unavailable.'; });
    }
  }

  Future<void> _toggleEmergencyStop() async {
    final state = _state;
    if (state == null || _saving) return;
    final halt = !state.emergencyStopped;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(halt ? 'Stop automated PAPER trading?' : 'Resume automated PAPER trading?'),
        content: Text(halt
            ? 'New automated PAPER actions will pause. Existing positions remain visible and can still be managed manually.'
            : 'Automation can resume, but every trade will still pass the Risk Engine.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          FilledButton(
            key: const ValueKey('paper-runtime-confirm-action'),
            onPressed: () => Navigator.pop(context, true),
            child: Text(halt ? 'Emergency stop' : 'Resume'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _saving = true);
    try {
      final updated = await widget.repository.setEmergencyStop(halted: halt);
      if (!mounted) return;
      setState(() {
        _state = updated;
        _saving = false;
        _message = halt ? 'Automated PAPER trading is paused.' : 'Automated PAPER trading resumed.';
      });
    } on ApiFailure {
      if (!mounted) return;
      setState(() { _saving = false; _message = 'Could not update Emergency Stop.'; });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.extension<TradingColors>()!;
    if (_loading) {
      return const SizedBox(height: 112, child: Center(child: CircularProgressIndicator()));
    }
    final state = _state;
    if (state == null) {
      return Card(
        key: const ValueKey('paper-runtime-sign-in'),
        child: Padding(padding: const EdgeInsets.all(16), child: Text(_message ?? 'Sign in to view trading status.')),
      );
    }

    final running = state.automationRunning;
    final statusText = state.emergencyStopped
        ? 'Automation paused'
        : state.automated
            ? 'Auto Trading: Running'
            : 'Manual Trading';

    return Container(
      key: const ValueKey('paper-runtime-card'),
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(24),
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF061728), Color(0xFF120B2B)],
        ),
        border: Border.all(color: colors.neonCyan.withValues(alpha: 0.38)),
        boxShadow: [BoxShadow(color: colors.neonCyan.withValues(alpha: 0.10), blurRadius: 28)],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(borderRadius: BorderRadius.circular(14), color: colors.neonCyan.withValues(alpha: 0.11)),
              child: Icon(Icons.shield_rounded, color: colors.neonCyan),
            ),
            const SizedBox(width: 12),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('${state.tradingMode} · ${state.automated ? 'AUTOMATED' : 'MANUAL'}', key: const ValueKey('paper-runtime-mode'), style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900)),
              const SizedBox(height: 2),
              Text('Risk Engine: ${state.riskEngineActive ? 'Active' : 'Unavailable'}', style: theme.textTheme.bodySmall?.copyWith(color: colors.neonCyan, fontWeight: FontWeight.w800)),
            ])),
          ]),
          const SizedBox(height: 14),
          Wrap(spacing: 8, runSpacing: 8, children: [
            _Pill(icon: running ? Icons.bolt_rounded : Icons.pause_circle_outline_rounded, label: statusText),
            _Pill(icon: Icons.lock_outline_rounded, label: state.liveAvailable ? 'LIVE available' : 'LIVE locked'),
          ]),
          const SizedBox(height: 14),
          Text(
            state.emergencyStopped
                ? 'Automated PAPER execution is stopped. Risk controls remain active.'
                : state.automated
                    ? 'AI opportunities can enter and exit PAPER positions automatically after mandatory risk checks.'
                    : 'AI can recommend trades; you remain in control of PAPER order submission.',
            style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant, height: 1.4),
          ),
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            child: state.emergencyStopped
                ? OutlinedButton.icon(
                    key: const ValueKey('paper-runtime-resume'),
                    onPressed: _saving ? null : _toggleEmergencyStop,
                    icon: const Icon(Icons.play_arrow_rounded),
                    label: const Text('Resume PAPER automation'),
                  )
                : FilledButton.icon(
                    key: const ValueKey('paper-runtime-emergency-stop'),
                    onPressed: _saving ? null : _toggleEmergencyStop,
                    style: FilledButton.styleFrom(backgroundColor: theme.colorScheme.error, foregroundColor: theme.colorScheme.onError),
                    icon: const Icon(Icons.stop_circle_outlined),
                    label: const Text('Emergency Stop'),
                  ),
          ),
          if (_saving) ...[const SizedBox(height: 10), const LinearProgressIndicator()],
          if (_message != null) ...[const SizedBox(height: 8), Text(_message!, style: theme.textTheme.bodySmall)],
        ],
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  const _Pill({required this.icon, required this.label});
  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).extension<TradingColors>()!;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        color: colors.neonCyan.withValues(alpha: 0.08),
        border: Border.all(color: colors.neonCyan.withValues(alpha: 0.22)),
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 14, color: colors.neonCyan),
        const SizedBox(width: 5),
        Text(label, style: TextStyle(color: colors.neonCyan, fontSize: 11, fontWeight: FontWeight.w800)),
      ]),
    );
  }
}
