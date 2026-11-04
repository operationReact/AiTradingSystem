import 'authenticated_api_client.dart';

String _value(Map<String, dynamic> json, String field) =>
    (json[field] ?? '').toString();

DateTime? _date(Map<String, dynamic> json, String field) =>
    DateTime.tryParse(_value(json, field));

List<Map<String, dynamic>> _records(Object? value) {
  if (value is! List) {
    throw const ApiTransportFailure('INVALID_MODEL_GOVERNANCE_RESPONSE', null);
  }
  return value.map((item) {
    if (item is! Map) {
      throw const ApiTransportFailure('INVALID_MODEL_GOVERNANCE_RESPONSE', null);
    }
    return Map<String, dynamic>.from(item);
  }).toList(growable: false);
}

Map<String, dynamic> _record(Object? value) {
  if (value is! Map) {
    throw const ApiTransportFailure('INVALID_MODEL_GOVERNANCE_RESPONSE', null);
  }
  return Map<String, dynamic>.from(value);
}

class RegisteredModelEvidence {
  const RegisteredModelEvidence({
    required this.key, required this.displayName, required this.family,
    required this.role, required this.provider, required this.licenseStatus,
    required this.fingerprint,
  });
  final String key, displayName, family, role, provider, licenseStatus, fingerprint;

  factory RegisteredModelEvidence.fromJson(Map<String, dynamic> json) =>
      RegisteredModelEvidence(
        key: _value(json, 'modelKey'),
        displayName: _value(json, 'displayName'),
        family: _value(json, 'family'),
        role: _value(json, 'role'),
        provider: _value(json, 'provider'),
        licenseStatus: _value(json, 'licenseStatus'),
        fingerprint: _value(json, 'definitionFingerprint'),
      );
}

class ModelVersionEvidence {
  const ModelVersionEvidence({
    required this.id,
    required this.label,
    required this.fingerprint,
    required this.artifactFingerprint,
    required this.featureSchemaFingerprint,
    required this.trainingDatasetFingerprint,
    required this.trainingConfigFingerprint,
    required this.codeSha,
    required this.createdAt,
    this.researchRegistryFingerprint,
    this.trainingCutoff,
  });

  final String id, label, fingerprint, artifactFingerprint;
  final String featureSchemaFingerprint, trainingDatasetFingerprint;
  final String trainingConfigFingerprint, codeSha;
  final String? researchRegistryFingerprint;
  final DateTime? createdAt, trainingCutoff;

  factory ModelVersionEvidence.fromJson(Map<String, dynamic> json) =>
      ModelVersionEvidence(
        id: _value(json, 'versionId'),
        label: _value(json, 'versionLabel'),
        fingerprint: _value(json, 'versionFingerprint'),
        artifactFingerprint: _value(json, 'artifactFingerprint'),
        featureSchemaFingerprint: _value(json, 'featureSchemaFingerprint'),
        trainingDatasetFingerprint: _value(json, 'trainingDatasetFingerprint'),
        trainingConfigFingerprint: _value(json, 'trainingConfigFingerprint'),
        codeSha: _value(json, 'codeSha'),
        researchRegistryFingerprint: json['researchRegistryFingerprint']?.toString(),
        trainingCutoff: _date(json, 'trainingCutoff'),
        createdAt: _date(json, 'createdAt'),
      );
}

class MatchedParticipantEvidence {
  const MatchedParticipantEvidence({
    required this.versionId, required this.role, required this.modelFingerprint,
  });
  final String versionId, role, modelFingerprint;

  factory MatchedParticipantEvidence.fromJson(Map<String, dynamic> json) =>
      MatchedParticipantEvidence(
        versionId: _value(json, 'modelVersionId'),
        role: _value(json, 'role'),
        modelFingerprint: _value(json, 'modelFingerprint'),
      );
}

class MatchedSessionEvidence {
  const MatchedSessionEvidence({
    required this.id, required this.fingerprint, required this.createdAt,
    required this.createdBy, required this.participants, required this.canAuthorizeCapital,
  });
  final String id, fingerprint, createdBy;
  final DateTime? createdAt;
  final List<MatchedParticipantEvidence> participants;
  final bool canAuthorizeCapital;

  factory MatchedSessionEvidence.fromJson(Map<String, dynamic> json) =>
      MatchedSessionEvidence(
        id: _value(json, 'sessionId'),
        fingerprint: _value(json, 'enrollmentFingerprint'),
        createdAt: _date(json, 'createdAt'),
        createdBy: _value(json, 'createdBy'),
        participants: _records(json['models'])
            .map(MatchedParticipantEvidence.fromJson).toList(growable: false),
        canAuthorizeCapital: json['canAuthorizeCapital'] == true,
      );
}

class MatchedCandidateEvidence {
  const MatchedCandidateEvidence({
    required this.versionId, required this.role, required this.action,
    required this.predictedAt,
  });
  final String versionId, role, action;
  final DateTime? predictedAt;

  factory MatchedCandidateEvidence.fromJson(Map<String, dynamic> json) =>
      MatchedCandidateEvidence(
        versionId: _value(json, 'modelVersionId'),
        role: _value(json, 'role'),
        action: _value(json, 'action'),
        predictedAt: _date(json, 'predictedAt'),
      );
}

class MatchedCaseEvidence {
  const MatchedCaseEvidence({
    required this.id, required this.symbol, required this.decisionAt,
    required this.caseFingerprint, required this.candidates,
    required this.verified, required this.canAuthorizeCapital,
  });
  final String id, symbol, caseFingerprint;
  final DateTime? decisionAt;
  final List<MatchedCandidateEvidence> candidates;
  final bool verified, canAuthorizeCapital;

  bool get hasDisagreement {
    final champions = candidates.where((c) => c.role == 'PAPER_CHAMPION');
    if (champions.length != 1) return false;
    return candidates.any((c) =>
        c.role == 'SHADOW_CHALLENGER' && c.action != champions.first.action);
  }

  factory MatchedCaseEvidence.fromJson(Map<String, dynamic> json) =>
      MatchedCaseEvidence(
        id: _value(json, 'caseKey'),
        symbol: _value(json, 'symbol'),
        decisionAt: _date(json, 'decisionAt'),
        caseFingerprint: _value(json, 'caseFingerprint'),
        candidates: _records(json['decisions'])
            .map(MatchedCandidateEvidence.fromJson).toList(growable: false),
        verified: json['independentlyVerifiedRuntime'] == true,
        canAuthorizeCapital: json['canAuthorizeCapital'] == true,
      );
}

/// Java V58 safety state. Administrative evidence only, never capital authority.
class MatchedSessionSafetyState {
  const MatchedSessionSafetyState({
    required this.sessionId, required this.state, required this.revision,
    required this.fingerprint, required this.reason, required this.updatedBy,
    required this.canAuthorizeCapital,
  });
  final String sessionId, state, fingerprint, reason, updatedBy;
  final int revision;
  final bool canAuthorizeCapital;

  factory MatchedSessionSafetyState.fromJson(Map<String, dynamic> json) {
    final state = _value(json, 'state');
    final revision = json['revision'];
    if (!const ['ACTIVE', 'PAUSED', 'QUARANTINED'].contains(state) ||
        revision is! int || revision < 0 ||
        json['canAuthorizeCapital'] != false ||
        _value(json, 'stateFingerprint').length != 64) {
      throw const ApiTransportFailure('INVALID_MATCHED_SAFETY_STATE', null);
    }
    return MatchedSessionSafetyState(
      sessionId: _value(json, 'sessionId'),
      state: state, revision: revision,
      fingerprint: _value(json, 'stateFingerprint'),
      reason: _value(json, 'reason'),
      updatedBy: _value(json, 'updatedBy'),
      canAuthorizeCapital: false,
    );
  }
}

class MatchedSessionSafetyChange {
  const MatchedSessionSafetyChange({
    required this.revision, required this.previousState,
    required this.newState, required this.reason,
    required this.changedBy, required this.fingerprint,
  });
  final int revision;
  final String? previousState;
  final String newState, reason, changedBy, fingerprint;

  factory MatchedSessionSafetyChange.fromJson(Map<String, dynamic> json) {
    final revision = json['revision'];
    if (revision is! int || revision < 0 || json['canAuthorizeCapital'] != false) {
      throw const ApiTransportFailure('INVALID_MATCHED_SAFETY_HISTORY', null);
    }
    return MatchedSessionSafetyChange(
      revision: revision,
      previousState: json['previousState']?.toString(),
      newState: _value(json, 'newState'),
      reason: _value(json, 'reason'),
      changedBy: _value(json, 'changedBy'),
      fingerprint: _value(json, 'stateFingerprint'),
    );
  }
}

class MatchedSessionAggregateEvidence {
  const MatchedSessionAggregateEvidence({
    required this.sessionId,
    required this.enrollmentFingerprint,
    required this.sessionSafetyState,
    required this.sessionStateRevision,
    required this.matchedCases,
    required this.championEnterCases,
    required this.shadowDisagreementCases,
    required this.linkedPaperOrders,
    required this.unlinkedChampionEnters,
    required this.paperFillQuantityMismatchOrders,
    required this.recordedFillQuantity,
    required this.recordedPaperFees,
    required this.recordedPaperSlippage,
    required this.reasonCodes,
  });

  final String sessionId, enrollmentFingerprint, sessionSafetyState;
  final int sessionStateRevision, matchedCases, championEnterCases;
  final int shadowDisagreementCases, linkedPaperOrders, unlinkedChampionEnters;
  final int paperFillQuantityMismatchOrders, recordedFillQuantity;
  final String recordedPaperFees, recordedPaperSlippage;
  final List<String> reasonCodes;

  static int _count(Map<String, dynamic> json, String name) {
    final value = json[name];
    if (value is! int || value < 0) {
      throw const ApiTransportFailure('INVALID_MATCHED_EVIDENCE_COUNT', null);
    }
    return value;
  }

  static String _decimal(Map<String, dynamic> json, String name) {
    final value = json[name];
    if (value is! num || !value.isFinite) {
      throw const ApiTransportFailure('INVALID_MATCHED_EVIDENCE_AMOUNT', null);
    }
    return value.toString();
  }

  factory MatchedSessionAggregateEvidence.fromJson(Map<String, dynamic> json) {
    if (json['contractVersion'] != 'v4-matched-paper-shadow-session-evidence.v1' ||
        json['canAuthorizeCapital'] != false ||
        json['liveOrderExecutionEnabled'] != false ||
        json['originalLicensedSourceCustodyVerified'] != false ||
        json['actualInferenceProducerAttested'] != false ||
        json['paperShadowSoakAccepted'] != false ||
        json['brokerReconciliationAccepted'] != false ||
        json['independentEconomicPerformanceVerified'] != false) {
      throw const ApiTransportFailure('INVALID_MATCHED_EVIDENCE_AUTHORITY', null);
    }
    final state = json['sessionSafetyState'];
    if (state != 'ACTIVE' && state != 'PAUSED' && state != 'QUARANTINED') {
      throw const ApiTransportFailure('INVALID_MATCHED_SESSION_SAFETY', null);
    }
    final reasons = json['reasonCodes'];
    if (reasons is! List || reasons.isEmpty ||
        reasons.any((item) => item is! String || item.isEmpty)) {
      throw const ApiTransportFailure('INVALID_MATCHED_EVIDENCE_REASONS', null);
    }
    return MatchedSessionAggregateEvidence(
      sessionId: _value(json, 'sessionId'),
      enrollmentFingerprint: _value(json, 'enrollmentFingerprint'),
      sessionSafetyState: state as String,
      sessionStateRevision: _count(json, 'sessionStateRevision'),
      matchedCases: _count(json, 'matchedCases'),
      championEnterCases: _count(json, 'championEnterCases'),
      shadowDisagreementCases: _count(json, 'shadowDisagreementCases'),
      linkedPaperOrders: _count(json, 'linkedPaperOrders'),
      unlinkedChampionEnters: _count(json, 'unlinkedChampionEnters'),
      paperFillQuantityMismatchOrders:
          _count(json, 'paperFillQuantityMismatchOrders'),
      recordedFillQuantity: _count(json, 'recordedFillQuantity'),
      recordedPaperFees: _decimal(json, 'recordedPaperFees'),
      recordedPaperSlippage: _decimal(json, 'recordedPaperSlippage'),
      reasonCodes: reasons.cast<String>().toList(growable: false),
    );
  }
}

/// Server-derived research receipt status, never model/market/live acceptance.
class ResearchReceiptModelEvidence {
  const ResearchReceiptModelEvidence({
    required this.versionId, required this.role, required this.family,
    required this.status, required this.reasonCode, required this.identityBound,
  });
  final String versionId, role, family, status, reasonCode;
  final bool identityBound;

  factory ResearchReceiptModelEvidence.fromJson(Map<String, dynamic> json) {
    final status = _value(json, 'status');
    if (!const [
      'NOT_ATTEMPTED', 'UNKNOWN_UNRESOLVED', 'SUCCEEDED',
      'SUCCEEDED_EXPIRED', 'REJECTED', 'TIMEOUT', 'UNKNOWN',
    ].contains(status) ||
        !const ['PAPER_CHAMPION', 'SHADOW_CHALLENGER'].contains(_value(json, 'role')) ||
        json['identityBound'] is! bool ||
        _value(json, 'modelVersionId').isEmpty) {
      throw const ApiTransportFailure('INVALID_RESEARCH_RECEIPT_MODEL', null);
    }
    return ResearchReceiptModelEvidence(
      versionId: _value(json, 'modelVersionId'),
      role: _value(json, 'role'),
      family: _value(json, 'family'),
      status: status, reasonCode: _value(json, 'reasonCode'),
      identityBound: json['identityBound'] as bool,
    );
  }
}

class ResearchReceiptReport {
  const ResearchReceiptReport({
    required this.sessionId, required this.caseKey,
    required this.enrollmentFingerprint, required this.sessionSafetyState,
    required this.sessionSafetyRevision, required this.sourceCaptured,
    required this.featuresFrozen, required this.matchedCaseFinalized,
    required this.expectedModels, required this.successfulReceipts,
    required this.unresolvedModels, required this.models,
  });
  final String sessionId, caseKey, enrollmentFingerprint, sessionSafetyState;
  final int sessionSafetyRevision, expectedModels, successfulReceipts, unresolvedModels;
  final bool sourceCaptured, featuresFrozen, matchedCaseFinalized;
  final List<ResearchReceiptModelEvidence> models;

  factory ResearchReceiptReport.fromJson(Map<String, dynamic> json) {
    if (json['contractVersion'] != 'v4-research-receipt-diagnostics.v1' ||
        json['canAuthorizeCapital'] != false ||
        json['liveExecutionEnabled'] != false ||
        json['independentlyVerifiedRuntime'] != false ||
        json['sourceRightsVerified'] != false ||
        json['paperShadowSoakAccepted'] != false ||
        json['brokerReconciliationAccepted'] != false ||
        json['sourceCaptured'] is! bool ||
        json['featuresFrozen'] is! bool ||
        json['matchedCaseFinalized'] is! bool ||
        !const ['ACTIVE','PAUSED','QUARANTINED'].contains(json['sessionSafetyState'])) {
      throw const ApiTransportFailure('INVALID_RESEARCH_RECEIPT_AUTHORITY', null);
    }
    final models = _records(json['models'])
        .map(ResearchReceiptModelEvidence.fromJson).toList(growable: false);
    final expected = json['expectedModels'];
    final success = json['successfulReceipts'];
    final unresolved = json['unresolvedModels'];
    final revision = json['sessionSafetyRevision'];
    if (expected is! int || expected < 2 || expected > 10 ||
        models.length != expected || revision is! int || revision < 0 ||
        success is! int || success < 0 ||
        unresolved is! int || unresolved < 0 ||
        success + unresolved != expected ||
        _value(json, 'sessionId').isEmpty ||
        _value(json, 'caseKey').isEmpty ||
        _value(json, 'enrollmentFingerprint').length != 64 ||
        (json['featuresFrozen'] == true && json['sourceCaptured'] != true)) {
      throw const ApiTransportFailure('INVALID_RESEARCH_RECEIPT_ROSTER', null);
    }
    return ResearchReceiptReport(
      sessionId: _value(json, 'sessionId'),
      caseKey: _value(json, 'caseKey'),
      enrollmentFingerprint: _value(json, 'enrollmentFingerprint'),
      sessionSafetyState: _value(json, 'sessionSafetyState'),
      sessionSafetyRevision: revision,
      sourceCaptured: json['sourceCaptured'] as bool,
      featuresFrozen: json['featuresFrozen'] as bool,
      matchedCaseFinalized: json['matchedCaseFinalized'] as bool,
      expectedModels: expected, successfulReceipts: success,
      unresolvedModels: unresolved, models: models,
    );
  }
}

abstract interface class ModelControlCenterRepository {
  Future<List<RegisteredModelEvidence>> models();
  Future<List<ModelVersionEvidence>> versions(String modelKey);
  Future<List<MatchedSessionEvidence>> recentSessions();
  Future<MatchedSessionEvidence> session(String sessionId);
  Future<MatchedSessionAggregateEvidence> sessionEvidence(String sessionId);
  Future<ResearchReceiptReport> researchReceipts(String sessionId, String caseKey);
  Future<List<MatchedCaseEvidence>> recentCases(String sessionId);
  Future<MatchedSessionSafetyState> safetyState(String sessionId);
  Future<MatchedSessionSafetyState> transitionSafety(
    String sessionId, String action, String reason);
  Future<List<MatchedSessionSafetyChange>> safetyHistory(String sessionId);

}

class HttpModelControlCenterRepository implements ModelControlCenterRepository {
  const HttpModelControlCenterRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<List<RegisteredModelEvidence>> models() async {
    final response = await api.getJson('/api/admin/model-governance/models');
    return _records(response).map(RegisteredModelEvidence.fromJson)
        .toList(growable: false);
  }

  @override
  Future<List<ModelVersionEvidence>> versions(String modelKey) async {
    final key = Uri.encodeComponent(modelKey);
    final response = await api.getJson(
      '/api/admin/model-governance/models/$key/versions',
    );
    return _records(response).map(ModelVersionEvidence.fromJson)
        .toList(growable: false);
  }

  @override
  Future<List<MatchedSessionEvidence>> recentSessions() async {
    final response = await api.getJson(
      '/api/admin/model-governance/paper-shadow/sessions',
      query: const {'limit': '20'},
    );
    return _records(response).map(MatchedSessionEvidence.fromJson)
        .toList(growable: false);
  }

  @override
  Future<MatchedSessionAggregateEvidence> sessionEvidence(String sessionId) async {
    final id = Uri.encodeComponent(sessionId);
    final response = await api.getJson(
      '/api/admin/model-governance/paper-shadow/sessions/$id/evidence',
    );
    return MatchedSessionAggregateEvidence.fromJson(_record(response));
  }

  @override
  Future<ResearchReceiptReport> researchReceipts(
      String sessionId, String caseKey) async {
    if (!RegExp(r'^[A-Za-z0-9][A-Za-z0-9._-]{2,95}
    final id = Uri.encodeComponent(sessionId);
    final response = await api.getJson(
      '/api/admin/model-governance/paper-shadow/sessions/$id',
    );
    return MatchedSessionEvidence.fromJson(_record(response));
  }

  static const _paperShadowPath =
      '/api/admin/model-governance/paper-shadow/sessions';

  @override
  Future<MatchedSessionSafetyState> safetyState(String sessionId) async {
    final id = Uri.encodeComponent(sessionId);
    final result = await api.getJson('$_paperShadowPath/$id/control');
    return MatchedSessionSafetyState.fromJson(_record(result));
  }

  @override
  Future<List<MatchedSessionSafetyChange>> safetyHistory(String sessionId) async {
    final id = Uri.encodeComponent(sessionId);
    final result = await api.getJson(
      '$_paperShadowPath/$id/control/history',
      query: const {'limit': '20'},
    );
    return _records(result).map(MatchedSessionSafetyChange.fromJson)
        .toList(growable: false);
  }

  @override
  Future<MatchedSessionSafetyState> transitionSafety(
      String sessionId, String action, String reason) async {
    if (!const ['PAUSE', 'RESUME', 'QUARANTINE'].contains(action)) {
      throw ArgumentError.value(action, 'action', 'Invalid safety transition');
    }
    final cleaned = reason.trim();
    if (cleaned.length < 10 || cleaned.length > 512) {
      throw ArgumentError.value(reason, 'reason', '10-512 characters required');
    }
    final id = Uri.encodeComponent(sessionId);
    final result = await api.putJson(
      '$_paperShadowPath/$id/control',
      body: {'action': action, 'reason': cleaned},
    );
    final state = MatchedSessionSafetyState.fromJson(_record(result));
    const outcomes = {'PAUSE': 'PAUSED', 'RESUME': 'ACTIVE',
      'QUARANTINE': 'QUARANTINED'};
    if (state.state != outcomes[action] || state.sessionId != sessionId) {
      throw const ApiTransportFailure('MATCHED_SAFETY_TRANSITION_NOT_CONFIRMED', null);
    }
    return state;
  }

  @override
  Future<List<MatchedCaseEvidence>> recentCases(String sessionId) async {
    final id = Uri.encodeComponent(sessionId);
    final response = await api.getJson(
      '/api/admin/model-governance/paper-shadow/sessions/$id/observations',
      query: const {'limit': '50'},
    );
    return _records(response).map(MatchedCaseEvidence.fromJson)
        .toList(growable: false);
  }
}
).hasMatch(caseKey)) {
      throw ArgumentError.value(caseKey, 'caseKey', 'Invalid research case key');
    }
    final id = Uri.encodeComponent(sessionId);
    final key = Uri.encodeComponent(caseKey);
    final result = await api.getJson(
      '/api/admin/model-governance/paper-shadow/sessions/$id/cases/$key/research-receipts',
    );
    return ResearchReceiptReport.fromJson(_record(result));
  }

  @override
  Future<MatchedSessionEvidence> session(String sessionId) async {
    final id = Uri.encodeComponent(sessionId);
    final response = await api.getJson(
      '/api/admin/model-governance/paper-shadow/sessions/$id',
    );
    return MatchedSessionEvidence.fromJson(_record(response));
  }

  static const _paperShadowPath =
      '/api/admin/model-governance/paper-shadow/sessions';

  @override
  Future<MatchedSessionSafetyState> safetyState(String sessionId) async {
    final id = Uri.encodeComponent(sessionId);
    final result = await api.getJson('$_paperShadowPath/$id/control');
    return MatchedSessionSafetyState.fromJson(_record(result));
  }

  @override
  Future<List<MatchedSessionSafetyChange>> safetyHistory(String sessionId) async {
    final id = Uri.encodeComponent(sessionId);
    final result = await api.getJson(
      '$_paperShadowPath/$id/control/history',
      query: const {'limit': '20'},
    );
    return _records(result).map(MatchedSessionSafetyChange.fromJson)
        .toList(growable: false);
  }

  @override
  Future<MatchedSessionSafetyState> transitionSafety(
      String sessionId, String action, String reason) async {
    if (!const ['PAUSE', 'RESUME', 'QUARANTINE'].contains(action)) {
      throw ArgumentError.value(action, 'action', 'Invalid safety transition');
    }
    final cleaned = reason.trim();
    if (cleaned.length < 10 || cleaned.length > 512) {
      throw ArgumentError.value(reason, 'reason', '10-512 characters required');
    }
    final id = Uri.encodeComponent(sessionId);
    final result = await api.putJson(
      '$_paperShadowPath/$id/control',
      body: {'action': action, 'reason': cleaned},
    );
    final state = MatchedSessionSafetyState.fromJson(_record(result));
    const outcomes = {'PAUSE': 'PAUSED', 'RESUME': 'ACTIVE',
      'QUARANTINE': 'QUARANTINED'};
    if (state.state != outcomes[action] || state.sessionId != sessionId) {
      throw const ApiTransportFailure('MATCHED_SAFETY_TRANSITION_NOT_CONFIRMED', null);
    }
    return state;
  }

  @override
  Future<List<MatchedCaseEvidence>> recentCases(String sessionId) async {
    final id = Uri.encodeComponent(sessionId);
    final response = await api.getJson(
      '/api/admin/model-governance/paper-shadow/sessions/$id/observations',
      query: const {'limit': '50'},
    );
    return _records(response).map(MatchedCaseEvidence.fromJson)
        .toList(growable: false);
  }
}
