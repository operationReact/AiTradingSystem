import 'dart:async';

import 'package:flutter/material.dart';

import '../data/history_monitor_repository.dart';

/// Owner-private operational metadata, never raw Angel One candle payloads.
///
/// A stored candle is not proof the requested exchange sessions are complete.
class HistoricalDataMonitorPage extends StatefulWidget {
  const HistoricalDataMonitorPage({required this.repository, super.key});

  final HistoryMonitorRepository repository;

  @override
  State<HistoricalDataMonitorPage> createState() =>
      _HistoricalDataMonitorPageState();
}

class _HistoricalDataMonitorPageState extends State<HistoricalDataMonitorPage> {
  Timer? _refreshTimer;
  HistoricalProgress? _data;
  bool _loading = true;
  String? _error;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _refresh();
    _refreshTimer = Timer.periodic(
      const Duration(seconds: 30),
      (_) { if (!_loading) _refresh(); },
    );
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    ++_generation;
    super.dispose();
  }

  Future<void> _refresh() async {
    final id = ++_generation;
    if (!mounted) return;
    setState(() { _loading = true; _error = null; });
    try {
      final data = await widget.repository.load();
      if (!mounted || id != _generation) return;
      setState(() => _data = data);
    } catch (_) {
      if (!mounted || id != _generation) return;
      setState(() {
        _data = null; // Never display stale numbers as a live download.
        _error = 'Private scheduler progress could not be verified. '
            'Check EC2, the owner account, and the monitor deployment.';
      });
    } finally {
      if (mounted && id == _generation) setState(() => _loading = false);
    }
  }

  static String _count(int value) {
    final chars = value.toString();
    return chars.replaceAllMapped(
      RegExp(r'\B(?=(\d{3})+(?!\d))'),
      (_) => ',',
    );
  }

  static String _when(DateTime? date) {
    if (date == null) return 'Not available';
    final d = date.toLocal();
    return '${d.day.toString().padLeft(2, '0')}/'
        '${d.month.toString().padLeft(2, '0')}/${d.year} '
        '${d.hour.toString().padLeft(2, '0')}:'
        '${d.minute.toString().padLeft(2, '0')}';
  }

  Widget _metric(String label, String value) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 12),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerLow,
          border: Border.all(color: scheme.outlineVariant),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(value, style: theme.textTheme.titleLarge?.copyWith(
              fontWeight: FontWeight.w800, fontFeatures: const [
                FontFeature.tabularFigures(),
              ],
            )),
            const SizedBox(height: 3),
            Text(label, maxLines: 2, style: theme.textTheme.labelSmall?.copyWith(
              color: scheme.onSurfaceVariant,
            )),
          ],
        ),
      ),
    );
  }

  Widget _sectionTitle(String title, {String? subtitle}) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 20, bottom: 9),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: theme.textTheme.titleMedium?.copyWith(
            fontWeight: FontWeight.w800)),
          if (subtitle != null) ...[
            const SizedBox(height: 3),
            Text(subtitle, style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            )),
          ],
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final data = _data;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Historical Data Monitor'),
        actions: [
          IconButton(
            key: const ValueKey('history-monitor-refresh'),
            tooltip: 'Refresh historical download progress',
            onPressed: _loading ? null : _refresh,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 104),
          children: [
            Text('Private Angel One collection · read-only',
              style: theme.textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant)),
            if (_loading) ...[
              const SizedBox(height: 8),
              const LinearProgressIndicator(minHeight: 2),
            ],
            if (_error != null) ...[
              const SizedBox(height: 18),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Icon(Icons.cloud_off_outlined),
                      const SizedBox(height: 10),
                      Text(_error!, key: const ValueKey('history-monitor-error')),
                      const SizedBox(height: 10),
                      OutlinedButton.icon(
                        key: const ValueKey('history-monitor-retry'),
                        onPressed: _refresh,
                        icon: const Icon(Icons.refresh_rounded),
                        label: const Text('Retry connection'),
                      ),
                    ]),
                ),
              ),
            ] else if (data != null) ...[
              const SizedBox(height: 12),
              _statusHeader(data),
              const SizedBox(height: 12),
              Row(children: [
                _metric('Verified stored candles', _count(data.candles)),
                const SizedBox(width: 8),
                _metric('Stocks with data', _count(data.stocksWithCandles)),
              ]),
              const SizedBox(height: 8),
              Row(children: [
                _metric('Years with records', '${data.yearsWithCandles}/21'),
                const SizedBox(width: 8),
                _metric('Recovery scans pending', _count(data.pendingRecoveryScans)),
              ]),
              _sectionTitle('Collection task',
                subtitle: 'Source, interval and requested historical period'),
              _details(data),
              _sectionTitle('Coverage by year',
                subtitle: 'Stored daily candles only. A year with data is not necessarily complete.'),
              _years(data),
              _sectionTitle('Recent download batches',
                subtitle: 'Runs recorded by the EC2 scheduler, newest first'),
              if (data.recentRuns.isEmpty)
                const _SmallMessage('No scheduler runs recorded in the verified snapshot.')
              else
                for (final run in data.recentRuns)
                  _runRow(run),
              _sectionTitle('Recent provider requests',
                subtitle: 'Exact inclusive dates and Angel One response outcomes'),
              if (data.recentRequests.isEmpty)
                const _SmallMessage('No historical requests found in the verified ledger.')
              else
                for (final request in data.recentRequests)
                  _requestRow(request),
              const SizedBox(height: 16),
              const _SmallMessage(
                'The archive is owner-private. This dashboard does not expose '
                'broker credentials, OHLCV prices, or authorize trades. '
                'Session completeness and historical token identity remain unverified.'),
            ],
          ],
        ),
      ),
    );
  }

  Widget _statusHeader(HistoricalProgress data) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final isRunning = data.status == 'RUNNING';
    final isPaused = data.status == 'PAUSED';
    final caption = switch (data.status) {
      'RUNNING' => 'Collector is processing a batch',
      'PAUSED' => 'Automatic requests temporarily suspended',
      'ERROR' => 'Collector needs attention',
      _ => 'Not currently downloading',
    };
    return Container(
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: colors.surfaceContainerLow,
        border: Border.all(color: colors.outlineVariant),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(children: [
        Icon(isRunning ? Icons.sync_rounded :
             isPaused ? Icons.pause_circle_outline_rounded :
             data.status == 'ERROR' ? Icons.warning_amber_rounded :
             Icons.check_circle_outline_rounded,
          color: isRunning ? colors.primary : colors.onSurfaceVariant),
        const SizedBox(width: 10),
        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(data.status, key: const ValueKey('history-monitor-status'),
              style: theme.textTheme.labelLarge?.copyWith(
                fontWeight: FontWeight.w800)),
            Text(caption, style: theme.textTheme.bodySmall),
            const SizedBox(height: 3),
            Text('Snapshot ${_when(data.generatedAt)} · refreshes every 30s',
              style: theme.textTheme.labelSmall?.copyWith(
                color: colors.onSurfaceVariant)),
          ])),
      ]),
    );
  }

  Widget _details(HistoricalProgress data) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(13),
      decoration: BoxDecoration(
        color: colors.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: colors.outlineVariant),
      ),
      child: Column(children: [
        _kv('Task', data.taskName),
        _kv('Provider', data.source.replaceAll('_', ' ')),
        _kv('Candle interval', 'Daily · 1D (ONE_DAY)'),
        _kv('Scheduler frequency', 'Hourly, off-hours only'),
        _kv('Weekdays (IST)', data.weekdayHours),
        _kv('Weekends (IST)', data.weekendHours),
        _kv('Requested period', '${data.requestedFrom} — ${data.requestedThrough}'),
        _kv('Next planned start', _when(data.nextScheduledAt)),
        if (data.pausedUntil != null)
          _kv('Paused until', _when(data.pausedUntil)),
        _kv('Consecutive zero-candle batches', '${data.zeroCandleStreak}'),
        _kv('Archived date windows', _count(data.archivePages)),
        _kv('Failed date windows', _count(data.failedWindows)),
        _kv('Coverage validation', 'Not yet verified'),
      ]),
    );
  }

  Widget _kv(String name, String value) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        SizedBox(width: 132,
          child: Text(name, style: theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant))),
        const SizedBox(width: 6),
        Expanded(child: Text(value, style: theme.textTheme.bodySmall?.copyWith(
          fontWeight: FontWeight.w700))),
      ]),
    );
  }

  Widget _years(HistoricalProgress data) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final largest = data.years.fold<int>(1, (max, year) =>
      year.candles > max ? year.candles : max);
    return Container(
      decoration: BoxDecoration(
        color: colors.surfaceContainerLow,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: colors.outlineVariant),
      ),
      child: Column(children: [
        for (final year in data.years) ExpansionTile(
          key: ValueKey('history-year-${year.year}'),
          tilePadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 1),
          childrenPadding: const EdgeInsets.fromLTRB(13, 0, 13, 12),
          dense: true,
          title: Row(children: [
            SizedBox(width: 44, child: Text('${year.year}',
              style: theme.textTheme.bodyMedium?.copyWith(
                fontWeight: FontWeight.w700))),
            Expanded(child: ClipRRect(
              borderRadius: BorderRadius.circular(2),
              child: LinearProgressIndicator(
                minHeight: 5,
                // Visual comparison of stored row counts, NOT coverage %
                value: year.candles / largest,
                backgroundColor: colors.surfaceContainerHighest,
              ),
            )),
            const SizedBox(width: 10),
            SizedBox(width: 74, child: Text(_count(year.candles),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall?.copyWith(
                fontWeight: FontWeight.w800))),
          ]),
          subtitle: Text(
            year.candles == 0 ? 'No stored candles' :
              '${year.stocks} stocks · completeness unverified',
            style: theme.textTheme.labelSmall?.copyWith(
              color: colors.onSurfaceVariant),
          ),
          children: [
            _kv('Candles', _count(year.candles)),
            _kv('Stocks', _count(year.stocks)),
            _kv('First stored date', year.firstDate ?? 'No records'),
            _kv('Last stored date', year.lastDate ?? 'No records'),
            _kv('Year status', year.candles == 0 ? 'No data' :
              'Partial / completeness unverified'),
          ],
        ),
      ]),
    );
  }

  Widget _runRow(HistoryRun run) {
    return _compactRecord(
      Icons.task_alt_rounded,
      '${_count(run.newCandles)} new candles · ${run.state}',
      '${_when(run.startedAt)}  ·  ${run.windowsQueried} windows',
    );
  }

  Widget _requestRow(HistoryRequest request) {
    return _compactRecord(
      request.returnedCandles == 0 ? Icons.info_outline_rounded :
        Icons.event_note_rounded,
      '${request.symbol} · ${request.outcome.replaceAll('_', ' ')}',
      '${request.fromDate} → ${request.throughDate}  ·  '
        '${request.returnedCandles == null ? 'Unknown rows' :
        '${_count(request.returnedCandles!)} candles'}',
    );
  }

  Widget _compactRecord(IconData icon, String title, String subtitle) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 7),
      child: Container(
        padding: const EdgeInsets.all(11),
        decoration: BoxDecoration(
          border: Border.all(color: colors.outlineVariant),
          borderRadius: BorderRadius.circular(11),
        ),
        child: Row(children: [
          Icon(icon, size: 18, color: colors.onSurfaceVariant),
          const SizedBox(width: 9),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: theme.textTheme.bodySmall?.copyWith(
                fontWeight: FontWeight.w700)),
              const SizedBox(height: 2),
              Text(subtitle, style: theme.textTheme.labelSmall?.copyWith(
                color: colors.onSurfaceVariant)),
            ])),
        ]),
      ),
    );
  }
}

class _SmallMessage extends StatelessWidget {
  const _SmallMessage(this.message);
  final String message;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Text(message, style: theme.textTheme.bodySmall?.copyWith(
      color: theme.colorScheme.onSurfaceVariant, height: 1.4));
  }
}
