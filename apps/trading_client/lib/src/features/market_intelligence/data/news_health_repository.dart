import '../../../data/authenticated_api_client.dart';

/// This is system diagnostics, never a provider credential or trading signal.
class NewsHealthSource {
  const NewsHealthSource({
    required this.sourceId,
    required this.publisher,
    required this.rightsState,
    required this.canIngest,
    required this.canRetain,
    required this.canDisplay,
    required this.canTransferToModel,
    required this.latestStatus,
    required this.lastFetchedItems,
    required this.lastInsertedItems,
    this.lastFinishedAt,
    this.secondsSinceLastRun,
    this.lastErrorCode,
  });

  final String sourceId;
  final String publisher;
  final String rightsState;
  final bool canIngest;
  final bool canRetain;
  final bool canDisplay;
  final bool canTransferToModel;
  final String latestStatus;
  final DateTime? lastFinishedAt;
  final int? secondsSinceLastRun;
  final int lastFetchedItems;
  final int lastInsertedItems;
  final String? lastErrorCode;

  factory NewsHealthSource.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic>) throw const FormatException('Invalid source diagnostics');
    final id = raw['sourceId'];
    final publisher = raw['publisher'];
    final rights = raw['rightsState'];
    final state = raw['latestStatus'];
    final age = raw['secondsSinceLastRun'];
    final finishedRaw = raw['lastFinishedAt'];
    final finished = finishedRaw == null ? null : DateTime.tryParse('$finishedRaw');
    final fetched = raw['lastFetchedItems'];
    final inserted = raw['lastInsertedItems'];
    final error = raw['lastErrorCode'];
    if (id is! String || id.isEmpty ||
        publisher is! String || publisher.isEmpty ||
        rights is! String || rights.isEmpty ||
        state is! String || state.isEmpty ||
        raw['canIngest'] is! bool || raw['canRetain'] is! bool ||
        raw['canDisplay'] is! bool || raw['canTransferToModel'] is! bool ||
        (age != null && (age is! int || age < 0)) ||
        (finishedRaw != null && finished == null) ||
        fetched is! int || fetched < 0 || inserted is! int || inserted < 0 ||
        (error != null && error is! String)) {
      throw const FormatException('Malformed news-source status');
    }
    return NewsHealthSource(
      sourceId: id,
      publisher: publisher,
      rightsState: rights,
      canIngest: raw['canIngest'] as bool,
      canRetain: raw['canRetain'] as bool,
      canDisplay: raw['canDisplay'] as bool,
      canTransferToModel: raw['canTransferToModel'] as bool,
      latestStatus: state,
      lastFinishedAt: finished?.toUtc(),
      secondsSinceLastRun: age as int?,
      lastFetchedItems: fetched,
      lastInsertedItems: inserted,
      lastErrorCode: error as String?,
    );
  }
}

class NewsHealthSnapshot {
  const NewsHealthSnapshot({
    required this.observedAt,
    required this.ingestionEnabled,
    required this.eventLedgerEnabled,
    required this.analyzerBridgeEnabled,
    required this.analysisSchedulerEnabled,
    required this.mindContextEnabled,
    required this.selectedProvider,
    required this.configuredGeminiModel,
    required this.geminiKeyConfigured,
    required this.realProviderCallVerified,
    required this.receiptCount,
    required this.eventCount,
    required this.eventVersionCount,
    required this.sources,
  });

  final DateTime observedAt;
  final bool ingestionEnabled;
  final bool eventLedgerEnabled;
  final bool analyzerBridgeEnabled;
  final bool analysisSchedulerEnabled;
  final bool mindContextEnabled;
  final String selectedProvider;
  final String configuredGeminiModel;
  final bool geminiKeyConfigured;
  final bool realProviderCallVerified;
  final int receiptCount;
  final int eventCount;
  final int eventVersionCount;
  final List<NewsHealthSource> sources;

  factory NewsHealthSnapshot.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic>) throw const FormatException('Invalid news health response');
    final when = DateTime.tryParse('${raw['observedAt'] ?? ''}');
    final model = raw['model'];
    final rows = raw['sources'];
    final receipts = raw['receiptCount'];
    final events = raw['eventCount'];
    final versions = raw['eventVersionCount'];
    const toggles = [
      'ingestionEnabled', 'eventLedgerEnabled', 'analyzerBridgeEnabled',
      'analysisSchedulerEnabled', 'mindContextEnabled',
    ];
    if (when == null || model is! Map<String, dynamic> ||
        rows is! List || rows.length > 100 ||
        receipts is! int || receipts < 0 ||
        events is! int || events < 0 ||
        versions is! int || versions < 0 ||
        toggles.any((key) => raw[key] is! bool) ||
        model['selectedProvider'] is! String ||
        model['configuredGeminiModel'] is! String ||
        model['geminiKeyConfigured'] is! bool ||
        model['realProviderCallVerified'] is! bool) {
      throw const FormatException('Incomplete system-readiness evidence');
    }
    final sources = rows.map(NewsHealthSource.fromJson).toList(growable: false);
    if (sources.map((s) => s.sourceId).toSet().length != sources.length) {
      throw const FormatException('Duplicate news source health entry');
    }
    return NewsHealthSnapshot(
      observedAt: when.toUtc(),
      ingestionEnabled: raw['ingestionEnabled'] as bool,
      eventLedgerEnabled: raw['eventLedgerEnabled'] as bool,
      analyzerBridgeEnabled: raw['analyzerBridgeEnabled'] as bool,
      analysisSchedulerEnabled: raw['analysisSchedulerEnabled'] as bool,
      mindContextEnabled: raw['mindContextEnabled'] as bool,
      selectedProvider: model['selectedProvider'] as String,
      configuredGeminiModel: model['configuredGeminiModel'] as String,
      geminiKeyConfigured: model['geminiKeyConfigured'] as bool,
      realProviderCallVerified: model['realProviderCallVerified'] as bool,
      receiptCount: receipts,
      eventCount: events,
      eventVersionCount: versions,
      sources: List.unmodifiable(sources),
    );
  }
}

abstract interface class NewsHealthRepository {
  Future<NewsHealthSnapshot> fetch();
}

/// ADMIN-only endpoint: the backend enforces role and source permissions.
class HttpNewsHealthRepository implements NewsHealthRepository {
  const HttpNewsHealthRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<NewsHealthSnapshot> fetch() async {
    final raw = await api.getJson('/api/admin/market-intelligence/health');
    return NewsHealthSnapshot.fromJson(raw);
  }
}
