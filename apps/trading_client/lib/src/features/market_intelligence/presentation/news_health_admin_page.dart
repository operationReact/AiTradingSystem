import 'package:flutter/material.dart';

import '../data/news_health_repository.dart';

/// Sanitized ADMIN read-only readiness view. Credentials and raw provider
/// failures must never enter the Flutter model. No mutation actions exist here.
class NewsHealthAdminPage extends StatefulWidget {
  const NewsHealthAdminPage({required this.repository, super.key});

  final NewsHealthRepository repository;

  @override
  State<NewsHealthAdminPage> createState() => _NewsHealthAdminPageState();
}

class _NewsHealthAdminPageState extends State<NewsHealthAdminPage> {
  late Future<NewsHealthSnapshot> _snapshot;

  @override
  void initState() {
    super.initState();
    _snapshot = widget.repository.fetch();
  }

  void _refresh() => setState(() => _snapshot = widget.repository.fetch());

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    return Scaffold(
      appBar: AppBar(
        title: const Text('News pipeline'),
        actions: [
          IconButton(
            key: const ValueKey('news-health-refresh'),
            tooltip: 'Refresh source health',
            onPressed: _refresh,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: FutureBuilder<NewsHealthSnapshot>(
        future: _snapshot,
        builder: (context, data) {
          if (data.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (data.hasError || !data.hasData) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Icon(Icons.lock_outline_rounded, size: 28, color: muted),
                  const SizedBox(height: 12),
                  const Text(
                    'News pipeline health is unavailable. ADMIN authorization and a connected backend are required.',
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton(onPressed: _refresh, child: const Text('Retry')),
                ]),
              ),
            );
          }
          final snapshot = data.data!;
          return ListView(
            padding: const EdgeInsets.fromLTRB(14, 12, 14, 32),
            children: [
              Text('Read-only diagnostics · ${_ist(snapshot.observedAt)}',
                style: theme.textTheme.bodySmall?.copyWith(color: muted)),
              const SizedBox(height: 12),
              Text('Pipeline', style: theme.textTheme.titleSmall),
              const SizedBox(height: 6),
              _metricRow('RSS ingestion', snapshot.ingestionEnabled),
              _metricRow('Canonical ledger', snapshot.eventLedgerEnabled),
              _metricRow('Analyzer bridge', snapshot.analyzerBridgeEnabled),
              _metricRow('Gemini scheduler', snapshot.analysisSchedulerEnabled),
              _metricRow('Mintick Mind context', snapshot.mindContextEnabled),
              const SizedBox(height: 12),
              Text('Stored evidence', style: theme.textTheme.titleSmall),
              const SizedBox(height: 4),
              _countRow('Receipts', snapshot.receiptCount),
              _countRow('Canonical events', snapshot.eventCount),
              _countRow('Event versions', snapshot.eventVersionCount),
              const SizedBox(height: 14),
              Text('Analysis provider', style: theme.textTheme.titleSmall),
              const SizedBox(height: 4),
              Text('Selected: ${snapshot.selectedProvider} · Model: ${snapshot.configuredGeminiModel}',
                style: theme.textTheme.bodySmall),
              _metricRow('Gemini credential configured', snapshot.geminiKeyConfigured),
              _metricRow('Real provider call verified', snapshot.realProviderCallVerified),
              const SizedBox(height: 14),
              Text('Publisher sources', style: theme.textTheme.titleSmall),
              const SizedBox(height: 8),
              if (snapshot.sources.isEmpty)
                Text('No sources configured.', style: theme.textTheme.bodySmall),
              for (final source in snapshot.sources) ...[
                _sourceRow(source, theme),
                const SizedBox(height: 8),
              ],
            ],
          );
        },
      ),
    );
  }

  Widget _metricRow(String title, bool flag) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(children: [
        Expanded(child: Text(title, style: Theme.of(context).textTheme.bodyMedium)),
        Text(flag ? 'ENABLED' : 'OFF',
          style: Theme.of(context).textTheme.labelSmall?.copyWith(
            fontWeight: FontWeight.w600,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          )),
      ]),
    );
  }

  Widget _countRow(String title, int value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(children: [
        Expanded(child: Text(title)),
        Text('$value', style: const TextStyle(fontWeight: FontWeight.w600)),
      ]),
    );
  }

  Widget _sourceRow(NewsHealthSource source, ThemeData theme) {
    final muted = theme.colorScheme.onSurfaceVariant;
    final freshness = source.secondsSinceLastRun == null
        ? 'Never run'
        : '${source.secondsSinceLastRun}s since last run';
    return Container(
      key: ValueKey('news-source-${source.sourceId}'),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainer,
        borderRadius: BorderRadius.circular(9),
        border: Border.all(color: theme.colorScheme.outlineVariant),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text(source.publisher,
            style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600))),
          Text(source.latestStatus,
            style: theme.textTheme.labelSmall?.copyWith(color: muted)),
        ]),
        const SizedBox(height: 4),
        Text('Rights: ${source.rightsState} · $freshness',
          style: theme.textTheme.bodySmall?.copyWith(color: muted)),
        Text(
          'Ingest ${_yes(source.canIngest)} · Retain ${_yes(source.canRetain)} · '
          'Display ${_yes(source.canDisplay)} · Model ${_yes(source.canTransferToModel)}',
          style: theme.textTheme.labelSmall?.copyWith(color: muted)),
        Text('Last run: ${source.lastFetchedItems} fetched / ${source.lastInsertedItems} inserted',
          style: theme.textTheme.labelSmall?.copyWith(color: muted)),
        if (source.lastErrorCode != null)
          Text('Error code: ${source.lastErrorCode}',
            style: theme.textTheme.labelSmall?.copyWith(color: muted)),
      ]),
    );
  }

  static String _yes(bool granted) => granted ? 'allowed' : 'blocked';

  static String _ist(DateTime value) {
    final t = value.toUtc().add(const Duration(hours: 5, minutes: 30));
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(t.day)}/${two(t.month)}/${t.year} '
        '${two(t.hour)}:${two(t.minute)} IST';
  }
}
