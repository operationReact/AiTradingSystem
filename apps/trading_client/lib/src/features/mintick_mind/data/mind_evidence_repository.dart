import '../../../data/authenticated_api_client.dart';

/// Read-only persisted research provenance; never a trading recommendation.
class MindEvidenceRecord {
  const MindEvidenceRecord({
    required this.id, required this.kind, required this.source,
    required this.referenceCode, required this.observedAt,
    required this.usability, this.instrument, this.strategy,
    this.dataCutoffAt, this.fingerprint,
  });

  final String id;
  final String kind;
  final String source;
  final String? instrument;
  final String? strategy;
  final String referenceCode;
  final DateTime observedAt;
  final DateTime? dataCutoffAt;
  final String? fingerprint;
  final String usability;

  factory MindEvidenceRecord.fromJson(Map<String, dynamic> raw,
      DateTime generatedAt) {
    final kind = raw['kind'];
    final source = raw['source'];
    final permitted = (kind == 'RESEARCH_CANDIDATE' &&
            source == 'PERSISTED_RESEARCH_SCAN_CANDIDATE' &&
            raw['usability'] == 'RESEARCH_ONLY') ||
        (kind == 'RESEARCH_DIAGNOSTIC' &&
            source == 'PERSISTED_RESEARCH_SCAN_DIAGNOSTIC' &&
            raw['usability'] == 'BLOCKER_ONLY');
    final at = raw['observedAt'] is String
        ? DateTime.tryParse(raw['observedAt'] as String) : null;
    final cutoff = raw['dataCutoffAt'] is String
        ? DateTime.tryParse(raw['dataCutoffAt'] as String) : null;
    final id = raw['id'], ref = raw['referenceCode'];
    if (!permitted || raw['executable'] != false ||
        raw['knownAtStatus'] != 'NOT_INDEPENDENTLY_VERIFIED' ||
        id is! String || id.isEmpty || ref is! String || ref.isEmpty ||
        at == null || at.isAfter(generatedAt) ||
        (cutoff != null && cutoff.isAfter(generatedAt))) {
      throw const FormatException('Unsafe Mind evidence entry');
    }
    if (kind == 'RESEARCH_CANDIDATE' &&
        (cutoff == null || raw['fingerprint'] is! String ||
        (raw['fingerprint'] as String).isEmpty ||
        raw['instrument'] is! String ||
        (raw['instrument'] as String).isEmpty ||
        raw['strategy'] is! String ||
        (raw['strategy'] as String).isEmpty)) {
      throw const FormatException('Candidate provenance incomplete');
    }
    if (kind == 'RESEARCH_DIAGNOSTIC' &&
        (raw['fingerprint'] != null || cutoff != null)) {
      throw const FormatException('Diagnostic is not a market observation');
    }
    return MindEvidenceRecord(
      id: id, kind: kind as String, source: source as String,
      instrument: raw['instrument'] as String?,
      strategy: raw['strategy'] as String?,
      referenceCode: ref, observedAt: at, dataCutoffAt: cutoff,
      fingerprint: raw['fingerprint'] as String?,
      usability: raw['usability'] as String,
    );
  }
}

class MindEvidenceLedger {
  const MindEvidenceLedger({
    required this.generatedAt, required this.sourceStatus,
    required this.records, required this.blockingReasons,
    required this.rejectedCandidates,
    this.scanRunId, this.scanCompletedAt,
  });

  final DateTime generatedAt;
  final String sourceStatus;
  final String? scanRunId;
  final DateTime? scanCompletedAt;
  final List<MindEvidenceRecord> records;
  final List<String> blockingReasons;
  final int rejectedCandidates;

  factory MindEvidenceLedger.fromJson(Object? value) {
    if (value is! Map<String, dynamic> ||
        value['contractVersion'] != 'mind.evidence.v1' ||
        value['executionEnabled'] != false ||
        value['verifiedSignal'] != false ||
        value['decisionPosture'] != 'ABSTAIN') {
      throw const FormatException('Unsafe Mind ledger contract');
    }
    final status = value['sourceStatus'];
    final generated = value['generatedAt'] is String
        ? DateTime.tryParse(value['generatedAt'] as String) : null;
    if (status is! String ||
        !const {'RECORDED','STALE','PARTIAL','FAILED','UNAVAILABLE'}
            .contains(status) || generated == null ||
        value['entries'] is! List || value['blockingReasons'] is! List ||
        value['rejectedCandidates'] is! int ||
        (value['rejectedCandidates'] as int) < 0) {
      throw const FormatException('Invalid Mind ledger header');
    }
    final rawRun = value['scanRunId'];
    final rawCompleted = value['scanCompletedAt'];
    final completed = rawCompleted is String
        ? DateTime.tryParse(rawCompleted) : null;
    if ((status == 'UNAVAILABLE' &&
            (rawRun != null || rawCompleted != null || value['source'] != null)) ||
        (status != 'UNAVAILABLE' &&
            (rawRun is! String || rawRun.isEmpty ||
            value['source'] != 'PERSISTED_RESEARCH_SCAN' ||
            completed == null || completed.isAfter(generated)))) {
      throw const FormatException('Missing ledger run provenance');
    }
    final reasons = (value['blockingReasons'] as List)
        .map((x) => x is String && x.isNotEmpty
            ? x : throw const FormatException('Invalid blocker'))
        .toList(growable: false);
    final entries = (value['entries'] as List)
        .map((entry) => entry is Map<String, dynamic>
            ? MindEvidenceRecord.fromJson(entry, generated)
            : throw const FormatException('Invalid Mind ledger entry'))
        .toList(growable: false);
    if (entries.length > 30 ||
        ((status == 'FAILED' || status == 'PARTIAL') &&
         entries.any((e) => e.kind != 'RESEARCH_DIAGNOSTIC')) ||
        (status == 'UNAVAILABLE' && entries.isNotEmpty)) {
      throw const FormatException('Unexpected ledger evidence');
    }
    return MindEvidenceLedger(
      generatedAt: generated,
      sourceStatus: status,
      scanRunId: rawRun as String?,
      scanCompletedAt: completed,
      records: entries,
      blockingReasons: reasons,
      rejectedCandidates: value['rejectedCandidates'] as int,
    );
  }
}

abstract interface class MindEvidenceRepository {
  Future<MindEvidenceLedger> load({int limit = 20});
}

class HttpMindEvidenceRepository implements MindEvidenceRepository {
  const HttpMindEvidenceRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MindEvidenceLedger> load({int limit = 20}) async {
    if (limit < 1 || limit > 30) throw RangeError.range(limit, 1, 30);
    return MindEvidenceLedger.fromJson(await api.getJson(
      '/api/customer/mind/v1/evidence', query: {'limit': '$limit'}));
  }
}
