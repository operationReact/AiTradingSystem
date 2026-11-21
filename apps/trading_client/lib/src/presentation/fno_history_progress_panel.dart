import 'dart:async';

import 'package:flutter/material.dart';

import '../data/fno_history_progress_repository.dart';

/// Compact read-only customer-private F&O history status.
/// No action here can start a download, write prices, or authorize trading.
class FnoHistoryProgressPanel extends StatefulWidget {
  const FnoHistoryProgressPanel({required this.repository, super.key});
  final FnoHistoryProgressRepository repository;

  @override
  State<FnoHistoryProgressPanel> createState() =>
      _FnoHistoryProgressPanelState();
}

class _FnoHistoryProgressPanelState extends State<FnoHistoryProgressPanel> {
  Timer? _timer;
  FnoHistoryProgress? _progress;
  bool _loading = false;
  bool _failed = false;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 30), (_) {
      if (!_loading) _refresh();
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _generation++;
    super.dispose();
  }

  Future<void> _refresh() async {
    final request = ++_generation;
    if (!mounted) return;
    setState(() {
      _loading = true;
      _failed = false;
    });
    try {
      final result = await widget.repository.load();
      if (!mounted || request != _generation) return;
      setState(() => _progress = result);
    } catch (_) {
      if (!mounted || request != _generation) return;
      setState(() {
        _progress = null;
        _failed = true;
      });
    } finally {
      if (mounted && request == _generation) {
        setState(() => _loading = false);
      }
    }
  }

  String _message(FnoHistoryProgress? data) {
    if (_failed) {
      return 'Unable to retrieve authenticated F&O checkpoint status.';
    }
    if (data == null) {
      return 'Checking F&O historical collection status.';
    }
    return switch (data.state) {
      'NOT_CONFIGURED' =>
        'F&O bulk history is not connected. No checkpoint total is available.',
      'UNAVAILABLE' =>
        'No verified runtime checkpoint is available. Do not infer progress.',
      'STALE' =>
        'The last checkpoint is outdated; current download activity is unknown.',
      _ => data.hasPlannedBatches
          ? 'Checkpoint counts describe planned batches, not full NSE F&O market coverage.'
          : 'No authorized historical batch plan is registered yet.',
    };
  }

  String _status(FnoHistoryProgress? data) {
    if (_failed) return 'UNAVAILABLE';
    if (data == null) return 'CHECKING';
    return data.state.replaceAll('_', ' ');
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final data = _progress;
    return Container(
      key: const ValueKey('fno-history-progress-panel'),
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: colors.surfaceContainerLow,
        border: Border.all(color: colors.outlineVariant),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            const Icon(Icons.query_stats_rounded, size: 19),
            const SizedBox(width: 8),
            Expanded(child: Text('F&O historical data · Upstox',
                style: theme.textTheme.titleSmall?.copyWith(
                  fontWeight: FontWeight.w800))),
            IconButton(
              key: const ValueKey('fno-history-refresh'),
              tooltip: 'Refresh F&O checkpoint status',
              onPressed: _loading ? null : _refresh,
              icon: const Icon(Icons.refresh_rounded, size: 19),
              visualDensity: VisualDensity.compact,
            ),
          ]),
          Text(_status(data), key: const ValueKey('fno-history-status'),
              style: theme.textTheme.labelMedium?.copyWith(
                  color: colors.onSurfaceVariant, fontWeight: FontWeight.bold)),
          const SizedBox(height: 6),
          Text(_message(data), key: const ValueKey('fno-history-message'),
              style: theme.textTheme.bodySmall),
          if (_loading)
            const Padding(
              padding: EdgeInsets.only(top: 8),
              child: LinearProgressIndicator(minHeight: 2),
            ),
          if (data != null && data.hasPlannedBatches && data.percent != null) ...[
            const SizedBox(height: 11),
            Row(children: [
              Text('${data.completed}/${data.total} planned batches',
                  style: theme.textTheme.labelMedium),
              const Spacer(),
              Text('${data.percent!.toStringAsFixed(1)}%',
                  key: const ValueKey('fno-history-percent'),
                  style: theme.textTheme.labelLarge?.copyWith(
                      fontWeight: FontWeight.w800)),
            ]),
            const SizedBox(height: 5),
            LinearProgressIndicator(
              value: data.percent! / 100,
              minHeight: 3,
              backgroundColor: colors.surfaceContainerHighest,
            ),
            const SizedBox(height: 6),
            Text(
              'Pending ${data.counts?['PENDING'] ?? 0} · '
              'Retry ${data.counts?['RETRY'] ?? 0} · '
              'Blocked ${data.counts?['BLOCKED'] ?? 0} · '
              'Failed ${data.counts?['FAILED'] ?? 0}',
              style: theme.textTheme.labelSmall,
            ),
          ],
          const SizedBox(height: 8),
          Text(
            'Bulk storage permission not verified · No collection activated by this screen.',
            key: const ValueKey('fno-history-rights'),
            style: theme.textTheme.labelSmall?.copyWith(
                color: colors.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}
