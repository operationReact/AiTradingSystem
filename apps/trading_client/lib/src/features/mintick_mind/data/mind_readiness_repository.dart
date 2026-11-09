import '../../../data/authenticated_api_client.dart';

/// Exact server evidence contract. No fixture values or local trading
/// authority are available through this repository.
class MindEvidence {
  const MindEvidence({
    required this.status,
    required this.reasonCode,
    required this.observations,
    this.source,
    this.asOf,
  });

  final String status;
  final String reasonCode;
  final int observations;
  final String? source;
  final DateTime? asOf;

  static MindEvidence parse(Object? raw, {
    required Set<String> allowedStatuses,
    required String sourceName,
    required DateTime generatedAt,
  }) {
    if (raw is! Map<String, dynamic> ||
        raw['status'] is! String ||
        !allowedStatuses.contains(raw['status']) ||
        raw['reasonCode'] is! String ||
        (raw['reasonCode'] as String).isEmpty ||
        raw['observations'] is! int ||
        (raw['observations'] as int) < 0) {
      throw const FormatException('Invalid Mind evidence');
    }
    final status = raw['status'] as String;
    final source = raw['source'];
    final instant = raw['asOf'];
    if (status == 'UNAVAILABLE') {
      if (source != null || instant != null || raw['observations'] != 0) {
        throw const FormatException('Unavailable Mind evidence contains data');
      }
      return MindEvidence(status: status,
        reasonCode: raw['reasonCode'] as String, observations: 0);
    }
    final asOf = instant is String ? DateTime.tryParse(instant) : null;
    if (source != sourceName || asOf == null ||
        asOf.toUtc().isAfter(generatedAt.toUtc())) {
      throw const FormatException('Unverified Mind source/timestamp');
    }
    return MindEvidence(
      status: status,
      source: source as String,
      asOf: asOf,
      observations: raw['observations'] as int,
      reasonCode: raw['reasonCode'] as String,
    );
  }
}

class MindReadiness {
  const MindReadiness({
    required this.generatedAt,
    required this.market,
    required this.research,
  });

  final DateTime generatedAt;
  final MindEvidence market;
  final MindEvidence research;

  factory MindReadiness.fromJson(Object? decoded) {
    if (decoded is! Map<String, dynamic> ||
        decoded['contractVersion'] != 'mind.overview.v1' ||
        decoded['decisionPosture'] != 'ABSTAIN' ||
        decoded['executionEnabled'] != false ||
        decoded['verifiedSignal'] != false) {
      throw const FormatException('Mind overview is not safe for display');
    }
    final rawTime = decoded['generatedAt'];
    final at = rawTime is String ? DateTime.tryParse(rawTime) : null;
    if (at == null) throw const FormatException('Missing Mind evidence timestamp');
    return MindReadiness(
      generatedAt: at,
      market: MindEvidence.parse(
        decoded['market'], generatedAt: at,
        allowedStatuses: const {'STORED', 'STALE', 'UNAVAILABLE'},
        sourceName: 'STORED_LAST_OVERVIEW',
      ),
      research: MindEvidence.parse(
        decoded['research'], generatedAt: at,
        allowedStatuses: const {'RECORDED', 'STALE', 'PARTIAL', 'FAILED', 'UNAVAILABLE'},
        sourceName: 'PERSISTED_RESEARCH_SCAN',
      ),
    );
  }
}

abstract interface class MindReadinessRepository {
  Future<MindReadiness> load();
}

class HttpMindReadinessRepository implements MindReadinessRepository {
  const HttpMindReadinessRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<MindReadiness> load() async {
    final decoded = await api.getJson('/api/customer/mind/v1/overview');
    return MindReadiness.fromJson(decoded);
  }
}
