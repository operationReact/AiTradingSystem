import 'dart:async';

import 'package:flutter/material.dart';

import '../../../data/authenticated_api_client.dart';
import '../data/news_admin_health_repository.dart';
import '../data/historical_news_coverage_repository.dart';
import 'historical_news_coverage_screen.dart';

/// Diagnostic screen only. This does not expose an enable/approve/execute action.
/// The backend separately requires an ADMIN role on every data request.
class NewsAdminHealthPage extends StatefulWidget {
  const NewsAdminHealthPage({required this.repository, this.coverageRepository, super.key});

  final NewsAdminHealthRepository repository;
  final HistoricalNewsCoverageRepository? coverageRepository;

  @override
  State<NewsAdminHealthPage> createState() => _NewsAdminHealthPageState();
}

class _NewsAdminHealthPageState extends State<NewsAdminHealthPage> {
  NewsAdminHealthSnapshot? _snapshot;
  ApiFailure? _failure;
  bool _loading = false;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _generation++;
    super.dispose();
  }

  Future<void> _refresh() async {
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _failure = null;
    });
    try {
      final status = await widget.repository.readHealth();
      if (!mounted || generation != _generation) return;
      setState(() {
        _snapshot = status;
        _loading = false;
      });
    } on ApiFailure catch (error) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _failure = error;
        _loading = false;
      });
    } catch (_) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _failure = const ApiTransportFailure('INVALID_NEWS_HEALTH_RESPONSE', null);
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('News pipeline health'),
        actions: [
          IconButton(
            key: const ValueKey('news-health-refresh'),
            tooltip: 'Refresh health',
            onPressed: _loading ? null : () => unawaited(_refresh()),
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: SafeArea(
        child: _loading && _snapshot == null
            ? const Center(child: CircularProgressIndicator())
            : RefreshIndicator(
                onRefresh: _refresh,
                child: ListView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 80),
                  children: [
                    if (_failure != null)
                      Card(
                        key: const ValueKey('news-health-error'),
                        child: Padding(
                          padding: const EdgeInsets.all(14),
                          child: Text(
                            _failure!.statusCode == 403
                                ? 'ADMIN permission required. News diagnostics are not available to this account.'
                                : _failure!.statusCode == 401
                                    ? 'An authorized admin session is required.'
                                    : 'News pipeline health is unavailable. No values have been fabricated.',
                          ),
                        ),
                      ),
                    if (_snapshot == null && !_loading)
                      Center(
                        child: OutlinedButton(
                          onPressed: () => unawaited(_refresh()),
                          child: const Text('Retry'),
                        ),
                      ),
                    if (_snapshot != null) ...[
                      Text('As observed · ${_ist(_snapshot!.observedAt)}',
                        style: theme.textTheme.labelSmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant)),
                      if (_failure != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 6),
                          child: Text('Showing the last status loaded in this session.',
                            style: theme.textTheme.bodySmall),
                        ),
                      const SizedBox(height: 12),
                      _metrics(_snapshot!),
                      if (widget.coverageRepository != null && _failure == null)
                        OutlinedButton(
                          key: const ValueKey('news-health-historical-coverage'),
                          onPressed: () => Navigator.of(context).push<void>(MaterialPageRoute<void>(
                            builder: (_) => HistoricalNewsCoverageScreen(
                              repository: widget.coverageRepository!, initialCutoff: _snapshot!.observedAt))),
                          child: const Text('Historical news coverage'),
                        ),
                      const SizedBox(height: 12),
                      Text('Pipeline', style: theme.textTheme.titleSmall),
                      const SizedBox(height: 4),
                      _step('Publisher ingestion', _snapshot!.ingestionEnabled),
                      _step('Immutable event ledger', _snapshot!.eventLedgerEnabled),
                      _step('Analysis bridge', _snapshot!.analyzerBridgeEnabled),
                      _step('Gemini analysis scheduler', _snapshot!.analysisSchedulerEnabled),
                      _step('Mintick Mind news context', _snapshot!.mindContextEnabled),
                      const SizedBox(height: 12),
                      Text('Model provider', style: theme.textTheme.titleSmall),
                      const SizedBox(height: 6),
                      Card(
                        child: Padding(
                          padding: const EdgeInsets.all(12),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _line('Selected provider', _snapshot!.model.selectedProvider),
                              _line('Configured model', _snapshot!.model.configuredGeminiModel),
                              _line('Credential configured', _yesNo(_snapshot!.model.geminiKeyConfigured)),
                              _line('Real provider call verified',
                                _snapshot!.model.realProviderCallVerified ? 'VERIFIED' : 'NOT VERIFIED'),
                              _line('Selected provider configured',
                                _snapshot!.model.selectedProviderConfigured == null ? 'UNAVAILABLE'
                                  : _yesNo(_snapshot!.model.selectedProviderConfigured!)),
                              _line('Recorded Gemini analyses',
                                _snapshot!.model.recordedGeminiAnalysisReceipts?.toString() ?? 'UNAVAILABLE'),
                              _line('Gemini readiness', _geminiReadiness(_snapshot!.model.geminiReadiness)),
                              _line('Last Gemini attempt', _snapshot!.model.lastGeminiAttemptAt == null
                                ? 'UNAVAILABLE' : _ist(_snapshot!.model.lastGeminiAttemptAt!)),
                              _line('Gemini transport result',
                                _geminiAttemptOutcome(_snapshot!.model.lastGeminiAttemptOutcome)),
                              const Text('Recorded analyses do not prove current provider connectivity.'),
                              const Text('Transport observations reset when the backend restarts; '
                                'a completed response does not certify structured analysis.'),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 12),
                      Text('Publisher sources', style: theme.textTheme.titleSmall),
                      const SizedBox(height: 6),
                      if (_snapshot!.sources.isEmpty)
                        const Padding(
                          padding: EdgeInsets.all(12),
                          child: Text('No publisher sources are registered.'),
                        ),
                      ..._snapshot!.sources.map(_sourceCard),
                    ],
                  ],
                ),
              ),
      ),
    );
  }

  Widget _metrics(NewsAdminHealthSnapshot snapshot) => Row(
    children: [
      _metric('Receipts', snapshot.receiptCount),
      const SizedBox(width: 8),
      _metric('Events', snapshot.eventCount),
      const SizedBox(width: 8),
      _metric('Versions', snapshot.eventVersionCount),
    ],
  );

  Widget _metric(String label, int value) => Expanded(
    child: Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: Theme.of(context).textTheme.labelSmall),
            const SizedBox(height: 5),
            Text('$value',
              key: ValueKey('news-health-${label.toLowerCase()}'),
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.w700)),
          ],
        ),
      ),
    ),
  );

  Widget _step(String title, bool enabled) => ListTile(
    dense: true,
    contentPadding: const EdgeInsets.symmetric(horizontal: 4),
    title: Text(title),
    trailing: Text(enabled ? 'ENABLED' : 'OFF',
      style: Theme.of(context).textTheme.labelSmall),
  );

  Widget _sourceCard(NewsAdminSourceStatus source) => Card(
    key: ValueKey('news-health-source-${source.sourceId}'),
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(source.publisher,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              fontWeight: FontWeight.w700)),
          const SizedBox(height: 4),
          Text('${source.sourceId} · ${source.rightsState} · ${source.latestStatus}',
            style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 6),
          Text(
            'Ingest ${_yesNo(source.canIngest)} · Retain ${_yesNo(source.canRetain)}'
            ' · Display ${_yesNo(source.canDisplay)} · Model ${_yesNo(source.canTransferToModel)}',
            style: Theme.of(context).textTheme.labelSmall),
          const SizedBox(height: 6),
          Text(
            'Fetched ${source.lastFetchedItems} · Inserted ${source.lastInsertedItems}'
            ' · Last run ${source.lastFinishedAt == null ? 'never' : _ist(source.lastFinishedAt!)}',
            style: Theme.of(context).textTheme.labelSmall),
          if (source.lastErrorCode != null)
            Text('Last error: ${source.lastErrorCode}',
              style: Theme.of(context).textTheme.labelSmall),
        ],
      ),
    ),
  );

  Widget _line(String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 3),
    child: Row(
      children: [
        Expanded(child: Text(label,
          style: Theme.of(context).textTheme.bodySmall)),
        Flexible(child: Text(value.isEmpty ? 'UNAVAILABLE' : value,
          textAlign: TextAlign.end,
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
            fontWeight: FontWeight.w600))),
      ],
    ),
  );

  static String _geminiReadiness(String? status) => switch (status) {
    'GEMINI_NOT_SELECTED' => 'Not selected',
    'GEMINI_NOT_CONFIGURED' => 'Not configured',
    'CONFIGURED_NO_RECORDED_RECEIPT' => 'Configured; no recorded receipts',
    'RECORDED_ANALYSIS_RECEIPTS_PRESENT' => 'Recorded receipts present',
    _ => 'UNAVAILABLE',
  };

  static String _yesNo(bool enabled) => enabled ? 'YES' : 'NO';

  static String _geminiAttemptOutcome(String? outcome) => switch (outcome) {
    'NO_OBSERVATION' => 'No attempt observed',
    'COMPLETED_WITH_REQUEST_ID' => 'Completed with request ID',
    'COMPLETED_WITHOUT_REQUEST_ID' => 'Completed without request ID',
    'UNCONFIGURED' => 'Not configured',
    'TIMEOUT' => 'Timed out',
    'RATE_LIMITED' => 'Rate limited',
    'HTTP_ERROR' => 'Provider HTTP error',
    'INVALID_RESPONSE' => 'Invalid provider response',
    _ => 'UNAVAILABLE',
  };

  static String _ist(DateTime value) {
    final t = value.toUtc().add(const Duration(hours: 5, minutes: 30));
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(t.day)}/${two(t.month)}/${t.year} '
        '${two(t.hour)}:${two(t.minute)} IST';
  }
}
