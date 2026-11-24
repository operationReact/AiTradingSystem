import '../../../data/authenticated_api_client.dart';

/// Admin-only operational status. These are server observations, not proof
/// of actual Gemini calls or a trading system's readiness to place orders.
class NewsAdminHealthSnapshot {
  const NewsAdminHealthSnapshot({
    required this.observedAt,
    required this.ingestionEnabled,
    required this.eventLedgerEnabled,
    required this.analyzerBridgeEnabled,
    required this.analysisSchedulerEnabled,
    required this.mindContextEnabled,
    required this.model,
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
  final NewsAdminModelStatus model;
  final int receiptCount;
  final int eventCount;
  final int eventVersionCount;
  final List<NewsAdminSourceStatus> sources;

  factory NewsAdminHealthSnapshot.fromJson(Object? raw) {
    final json = _map(raw);
    final observedAt = DateTime.tryParse('${json['observedAt'] ?? ''}');
    final receipt = json['receiptCount'];
    final event = json['eventCount'];
    final version = json['eventVersionCount'];
    final sourceRows = json['sources'];
    if (observedAt == null || receipt is! int || receipt < 0 ||
        event is! int || event < 0 || version is! int || version < 0 ||
        sourceRows is! List ||
        sourceRows.length > 200) {
      throw const FormatException('Invalid news health counters');
    }
    final sources = sourceRows.map(NewsAdminSourceStatus.fromJson).toList(growable: false);
    if (sources.map((source) => source.sourceId).toSet().length != sources.length) {
      throw const FormatException('Duplicate publisher source in news health');
    }
    return NewsAdminHealthSnapshot(
      observedAt: observedAt.toUtc(),
      ingestionEnabled: _boolean(json, 'ingestionEnabled'),
      eventLedgerEnabled: _boolean(json, 'eventLedgerEnabled'),
      analyzerBridgeEnabled: _boolean(json, 'analyzerBridgeEnabled'),
      analysisSchedulerEnabled: _boolean(json, 'analysisSchedulerEnabled'),
      mindContextEnabled: _boolean(json, 'mindContextEnabled'),
      model: NewsAdminModelStatus.fromJson(json['model']),
      receiptCount: receipt,
      eventCount: event,
      eventVersionCount: version,
      sources: List.unmodifiable(sources),
    );
  }
}

class NewsAdminModelStatus {
  const NewsAdminModelStatus({
    required this.selectedProvider,
    required this.configuredGeminiModel,
    required this.geminiKeyConfigured,
    required this.realProviderCallVerified,
  });

  final String selectedProvider;
  final String configuredGeminiModel;
  final bool geminiKeyConfigured;
  final bool realProviderCallVerified;

  factory NewsAdminModelStatus.fromJson(Object? raw) {
    final json = _map(raw);
    return NewsAdminModelStatus(
      selectedProvider: _string(json, 'selectedProvider'),
      configuredGeminiModel: _string(json, 'configuredGeminiModel'),
      geminiKeyConfigured: _boolean(json, 'geminiKeyConfigured'),
      realProviderCallVerified: _boolean(json, 'realProviderCallVerified'),
    );
  }
}

class NewsAdminSourceStatus {
  const NewsAdminSourceStatus({
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

  factory NewsAdminSourceStatus.fromJson(Object? raw) {
    final json = _map(raw);
    final timestamp = json['lastFinishedAt'];
    final lastFinished = timestamp == null ? null : DateTime.tryParse('$timestamp');
    final age = json['secondsSinceLastRun'];
    final fetched = json['lastFetchedItems'];
    final inserted = json['lastInsertedItems'];
    final error = json['lastErrorCode'];
    if ((timestamp != null && lastFinished == null) ||
        (age != null && (age is! int || age < 0)) ||
        fetched is! int || fetched < 0 ||
        inserted is! int || inserted < 0 ||
        (error != null && error is! String)) {
      throw const FormatException('Invalid news source health');
    }
    return NewsAdminSourceStatus(
      sourceId: _string(json, 'sourceId'),
      publisher: _string(json, 'publisher'),
      rightsState: _string(json, 'rightsState'),
      canIngest: _boolean(json, 'canIngest'),
      canRetain: _boolean(json, 'canRetain'),
      canDisplay: _boolean(json, 'canDisplay'),
      canTransferToModel: _boolean(json, 'canTransferToModel'),
      latestStatus: _string(json, 'latestStatus'),
      lastFinishedAt: lastFinished?.toUtc(),
      secondsSinceLastRun: age as int?,
      lastFetchedItems: fetched,
      lastInsertedItems: inserted,
      lastErrorCode: error as String?,
    );
  }
}

Map<String, dynamic> _map(Object? raw) {
  if (raw is! Map<String, dynamic>) {
    throw const FormatException('Expected news health object');
  }
  return raw;
}

String _string(Map<String, dynamic> data, String key) {
  final value = data[key];
  if (value is! String || value.length > 160) {
    throw FormatException('Invalid news health field: $key');
  }
  return value;
}

bool _boolean(Map<String, dynamic> data, String key) {
  final value = data[key];
  if (value is! bool) throw FormatException('Invalid news health field: $key');
  return value;
}

abstract interface class NewsAdminHealthRepository {
  Future<NewsAdminHealthSnapshot> readHealth();
}

class HttpNewsAdminHealthRepository implements NewsAdminHealthRepository {
  const HttpNewsAdminHealthRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<NewsAdminHealthSnapshot> readHealth() async {
    final response = await api.getJson('/api/admin/market-intelligence/health');
    return NewsAdminHealthSnapshot.fromJson(response);
  }
}
