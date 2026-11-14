import '../../../data/authenticated_api_client.dart';


/// Authoritative *observations*, not a coverage denominator or licence grant.
/// Absent provider registration remains unknown; no fake 0% is generated.
class UpstoxPersistedReceipts {
  const UpstoxPersistedReceipts({
    required this.evidenceState,
    required this.generatedAt,
    required this.jobs,
    required this.chunks,
    required this.attempts,
    required this.archivedVerifiedChunks,
    required this.attestedRetentionChunks,
    required this.archivedVerifiedRecords,
    required this.attestedRetentionRecords,
    required this.failedChunks,
    required this.retryChunks,
    required this.lastCheckpointAt,
    required this.cooldownUntil,
  });

  final String evidenceState;
  final DateTime generatedAt;
  final int? jobs;
  final int? chunks;
  final int? attempts;
  final int? archivedVerifiedChunks;
  final int? attestedRetentionChunks;
  final int? archivedVerifiedRecords;
  final int? attestedRetentionRecords;
  final int? failedChunks;
  final int? retryChunks;
  final DateTime? lastCheckpointAt;
  final DateTime? cooldownUntil;

  static UpstoxPersistedReceipts fromJson(Object? json) {
    if (json is! Map<String, dynamic> ||
        json['contractVersion'] != 'minttick.upstox.persisted-receipts.v1' ||
        json['provider'] != 'UPSTOX' ||
        json['modelTrainingRightsVerified'] != false ||
        json['paperTradingEnabled'] != false ||
        json['receipts'] is! Map<String, dynamic>) {
      throw const FormatException('Invalid Upstox persisted receipt contract');
    }
    final generated = DateTime.tryParse((json['generatedAt'] ?? '').toString());
    if (generated == null) {
      throw const FormatException('Missing persisted receipt timestamp');
    }
    final data = json['receipts'] as Map<String, dynamic>;
    final evidence = data['evidenceState'];
    if (!const {'NO_PROVIDER_RECEIPT', 'PERSISTED_CHECKPOINTS_ONLY'}
        .contains(evidence)) {
      throw const FormatException('Unknown persisted receipt state');
    }
    int? count(String key) {
      final value = data[key];
      if (value == null) return null;
      if (value is! int || value < 0) {
        throw const FormatException('Invalid persisted receipt count');
      }
      return value;
    }
    DateTime? date(String key) {
      final value = data[key];
      if (value == null) return null;
      if (value is! String) throw const FormatException('Invalid receipt time');
      final result = DateTime.tryParse(value);
      if (result == null) throw const FormatException('Invalid receipt time');
      return result;
    }
    final jobs = count('jobs');
    final chunks = count('chunks');
    final attempts = count('attempts');
    final verified = count('archivedVerifiedChunks');
    final attested = count('attestedRetentionChunks');
    final records = count('archivedVerifiedRecords');
    final retained = count('attestedRetentionRecords');
    final failed = count('failedChunks');
    final retry = count('retryChunks');
    if (evidence == 'NO_PROVIDER_RECEIPT' &&
        [jobs, chunks, attempts, verified, attested, records,
          retained, failed, retry].any((n) => n != null)) {
      throw const FormatException('Unknown provider cannot have observed counts');
    }
    if (evidence == 'PERSISTED_CHECKPOINTS_ONLY' &&
        ([jobs, chunks, attempts, verified, attested, records,
          retained, failed, retry].any((n) => n == null) ||
          verified! > chunks! || attested! > verified! ||
          retained! > records! || failed! > chunks! ||
          retry! > chunks!)) {
      throw const FormatException('Inconsistent persisted receipt counts');
    }
    return UpstoxPersistedReceipts(
      evidenceState: evidence as String, generatedAt: generated,
      jobs: jobs, chunks: chunks, attempts: attempts,
      archivedVerifiedChunks: verified, attestedRetentionChunks: attested,
      archivedVerifiedRecords: records, attestedRetentionRecords: retained,
      failedChunks: failed, retryChunks: retry,
      lastCheckpointAt: date('lastCheckpointAt'),
      cooldownUntil: date('cooldownUntil'),
    );
  }
}

/// Authenticated owner-only Spring Boot progress contract. No mock numbers are
/// substituted when the telemetry endpoint is absent or unavailable.
class UpstoxAnalyticsProgress {
  const UpstoxAnalyticsProgress({
    required this.generatedAt,
    required this.tasks,
    required this.contractVersion,
    this.persistedReceipts,
  });

  final UpstoxPersistedReceipts? persistedReceipts;
  final DateTime generatedAt;
  final List<UpstoxAnalyticsTask> tasks;
  final String contractVersion;

  UpstoxAnalyticsTask? find(String code) {
    for (final task in tasks) {
      if (task.code == code) return task;
    }
    return null;
  }

  factory UpstoxAnalyticsProgress.fromJson(Object? json) {
    if (json is! Map<String, dynamic> ||
        json['contractVersion'] != 'minttick.upstox.analytics.progress.v1' ||
        json['source'] != 'UPSTOX_ANALYTICS_READONLY' ||
        json['liveTradingEnabled'] != false ||
        json['paperTradingEnabled'] != false ||
        json['modelTrainingRightsVerified'] != false ||
        json['tasks'] is! List) {
      throw const FormatException('Invalid Upstox Analytics telemetry');
    }
    final generated = DateTime.tryParse((json['generatedAt'] ?? '').toString());
    if (generated == null) {
      throw const FormatException('Missing telemetry timestamp');
    }
    final tasks = (json['tasks'] as List)
        .map((item) => UpstoxAnalyticsTask.fromJson(item, generated))
        .toList(growable: false);
    if (tasks.length > 16 ||
        tasks.map((e) => e.code).toSet().length != tasks.length) {
      throw const FormatException('Invalid telemetry task list');
    }
    return UpstoxAnalyticsProgress(
      contractVersion: json['contractVersion'] as String,
      generatedAt: generated,
      tasks: List.unmodifiable(tasks),
    );
  }
}

class UpstoxAnalyticsTask {
  const UpstoxAnalyticsTask({
    required this.code,
    required this.state,
    required this.reasonCode,
    required this.details,
    required this.stale,
    this.checked,
    this.verified,
    this.issues,
    this.lastAttemptAt,
    this.lastSuccessAt,
  });

  final String code;
  final String state;
  final String? reasonCode;
  final Map<String, dynamic> details;
  final int? checked;
  final int? verified;
  final int? issues;
  final DateTime? lastAttemptAt;
  final DateTime? lastSuccessAt;
  final bool stale;

  static const knownStates = {
    'NOT_STARTED', 'RUNNING', 'VERIFIED', 'PARTIAL', 'FAILED', 'BLOCKED', 'DISABLED'
  };

  factory UpstoxAnalyticsTask.fromJson(Object? json, DateTime captured) {
    if (json is! Map<String, dynamic> ||
        json['code'] is! String ||
        !RegExp(r'^[A-Z_]{4,48}$').hasMatch(json['code'] as String) ||
        json['state'] is! String ||
        !knownStates.contains(json['state']) ||
        json['stale'] is! bool) {
      throw const FormatException('Invalid analytics task');
    }
    int? count(String name) {
      final raw = json[name];
      if (raw == null) return null;
      if (raw is! int || raw < 0) {
        throw const FormatException('Invalid analytics counter');
      }
      return raw;
    }
    DateTime? timestamp(String name) {
      final raw = json[name];
      if (raw == null) return null;
      if (raw is! String) throw const FormatException('Invalid timestamp');
      final value = DateTime.tryParse(raw);
      if (value == null || value.toUtc().isAfter(captured.toUtc().add(
          const Duration(minutes: 2)))) {
        throw const FormatException('Invalid telemetry timestamp');
      }
      return value;
    }
    final details = json['details'];
    if (details is! Map<String, dynamic> ||
        details.length > 24 ||
        (json['reasonCode'] != null && json['reasonCode'] is! String)) {
      throw const FormatException('Invalid analytics details');
    }
    final checked = count('checked');
    final verified = count('verified');
    if (verified != null && checked != null && verified > checked) {
      throw const FormatException('Invalid verified count');
    }
    return UpstoxAnalyticsTask(
      code: json['code'] as String,
      state: json['state'] as String,
      reasonCode: json['reasonCode'] as String?,
      details: Map.unmodifiable(details),
      checked: checked,
      verified: verified,
      issues: count('issues'),
      lastAttemptAt: timestamp('lastAttemptAt'),
      lastSuccessAt: timestamp('lastSuccessAt'),
      stale: json['stale'] as bool,
    );
  }
}

abstract interface class UpstoxAnalyticsProgressRepository {
  Future<UpstoxAnalyticsProgress> load();
}

class HttpUpstoxAnalyticsProgressRepository
    implements UpstoxAnalyticsProgressRepository {
  const HttpUpstoxAnalyticsProgressRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<UpstoxAnalyticsProgress> load() async {
    final raw = await api.getJson('/api/customer/owner-upstox-analytics/v1/progress');
    final data = UpstoxAnalyticsProgress.fromJson(raw);
    try {
      final stored = await api.getJson(
        '/api/customer/owner-upstox-analytics/v1/persisted-receipts');
      return UpstoxAnalyticsProgress(
        contractVersion: data.contractVersion,
        generatedAt: data.generatedAt,
        tasks: data.tasks,
        persistedReceipts: UpstoxPersistedReceipts.fromJson(stored),
      );
    } on ApiHttpFailure catch (ex) {
      // A failed owner authorization is never hidden as an empty dataset.
      if (ex.statusCode == 401 || ex.statusCode == 403) rethrow;
      // A not-yet-published gateway route is explicitly *unobserved* in UI.
      return data;
    } on FormatException {
      return data;
    }
  }
}
