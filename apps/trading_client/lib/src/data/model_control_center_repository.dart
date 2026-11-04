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
    required this.id, required this.label, required this.fingerprint,
    required this.artifactFingerprint, required this.createdAt,
  });
  final String id, label, fingerprint, artifactFingerprint;
  final DateTime? createdAt;

  factory ModelVersionEvidence.fromJson(Map<String, dynamic> json) =>
      ModelVersionEvidence(
        id: _value(json, 'versionId'),
        label: _value(json, 'versionLabel'),
        fingerprint: _value(json, 'versionFingerprint'),
        artifactFingerprint: _value(json, 'artifactFingerprint'),
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

class EffectiveModelEmergencyStatus {
  const EffectiveModelEmergencyStatus({
    required this.versionId, required this.allowed,
    required this.modelVersionDisabled, required this.modelFamilyDisabled,
    required this.staticFallbackEnabled, required this.canAuthorizeCapital,
  });
  final String versionId;
  final bool allowed, modelVersionDisabled, modelFamilyDisabled;
  final bool staticFallbackEnabled, canAuthorizeCapital;

  factory EffectiveModelEmergencyStatus.fromJson(Map<String, dynamic> json) =>
      EffectiveModelEmergencyStatus(
        versionId: _value(json, 'versionId'),
        allowed: json['allowed'] == true,
        modelVersionDisabled: json['modelVersionDisabled'] == true,
        modelFamilyDisabled: json['modelFamilyDisabled'] == true,
        staticFallbackEnabled: json['staticFallbackEnabled'] == true,
        canAuthorizeCapital: json['canAuthorizeCapital'] == true,
      );
}

class DisabledModelReceipt {
  const DisabledModelReceipt({
    required this.active, required this.reason,
    required this.stateFingerprint, required this.canAuthorizeCapital,
  });
  final bool active, canAuthorizeCapital;
  final String reason, stateFingerprint;

  factory DisabledModelReceipt.fromJson(Map<String, dynamic> json) =>
      DisabledModelReceipt(
        active: json['active'] == true,
        reason: _value(json, 'reason'),
        stateFingerprint: _value(json, 'stateFingerprint'),
        canAuthorizeCapital: json['canAuthorizeCapital'] == true,
      );
}

abstract interface class ModelControlCenterRepository {
  Future<List<RegisteredModelEvidence>> models();
  Future<EffectiveModelEmergencyStatus> effectiveModelStatus(String versionId);
  Future<DisabledModelReceipt> disableModelVersion(String versionId, String reason);

  Future<List<ModelVersionEvidence>> versions(String modelKey);
  Future<List<MatchedSessionEvidence>> recentSessions();
  Future<MatchedSessionEvidence> session(String sessionId);
  Future<List<MatchedCaseEvidence>> recentCases(String sessionId);
}

class HttpModelControlCenterRepository implements ModelControlCenterRepository {
  const HttpModelControlCenterRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<EffectiveModelEmergencyStatus> effectiveModelStatus(String versionId) async {
    final id = Uri.encodeComponent(versionId);
    final response = await api.getJson(
      '/api/admin/model-governance/emergency-controls/models/$id/effective',
    );
    return EffectiveModelEmergencyStatus.fromJson(_record(response));
  }

  @override
  Future<DisabledModelReceipt> disableModelVersion(
      String versionId, String reason) async {
    final id = Uri.encodeComponent(versionId);
    final cleaned = reason.trim();
    if (cleaned.isEmpty || cleaned.length > 512) {
      throw ArgumentError.value(reason, 'reason', 'A bounded audit reason is required');
    }
    final response = await api.putJson(
      '/api/admin/model-governance/emergency-controls/models/$id',
      body: {'disabled': true, 'reason': cleaned},
    );
    final receipt = DisabledModelReceipt.fromJson(_record(response));
    if (!receipt.active || receipt.canAuthorizeCapital) {
      throw const ApiTransportFailure('INVALID_MODEL_DISABLE_RECEIPT', null);
    }
    return receipt;
  }

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
  Future<MatchedSessionEvidence> session(String sessionId) async {
    final id = Uri.encodeComponent(sessionId);
    final response = await api.getJson(
      '/api/admin/model-governance/paper-shadow/sessions/$id',
    );
    return MatchedSessionEvidence.fromJson(_record(response));
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
