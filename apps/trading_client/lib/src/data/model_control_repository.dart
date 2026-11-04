import 'authenticated_api_client.dart';

/// Backend model identity is authoritative; UI never infers LIVE model eligibility.
class RegisteredModel {
  const RegisteredModel({
    required this.modelKey,
    required this.displayName,
    required this.family,
    required this.role,
    required this.provider,
    required this.licenseStatus,
    required this.definitionFingerprint,
    this.description,
  });

  final String modelKey;
  final String displayName;
  final String family;
  final String role;
  final String provider;
  final String licenseStatus;
  final String definitionFingerprint;
  final String? description;

  factory RegisteredModel.fromJson(Map<String, dynamic> json) => RegisteredModel(
        modelKey: json['modelKey']?.toString() ?? '',
        displayName: json['displayName']?.toString() ?? '',
        family: json['family']?.toString() ?? 'UNKNOWN',
        role: json['role']?.toString() ?? 'UNKNOWN',
        provider: json['provider']?.toString() ?? 'UNKNOWN',
        licenseStatus: json['licenseStatus']?.toString() ?? 'UNKNOWN',
        definitionFingerprint: json['definitionFingerprint']?.toString() ?? '',
        description: json['description']?.toString(),
      );
}

class RegisteredModelVersion {
  const RegisteredModelVersion({
    required this.versionId,
    required this.label,
    required this.artifactFingerprint,
    required this.featureSchemaFingerprint,
    required this.trainingDatasetFingerprint,
    required this.codeSha,
    this.trainingCutoff,
  });

  final String versionId;
  final String label;
  final String artifactFingerprint;
  final String featureSchemaFingerprint;
  final String trainingDatasetFingerprint;
  final String codeSha;
  final DateTime? trainingCutoff;

  factory RegisteredModelVersion.fromJson(Map<String, dynamic> json) => RegisteredModelVersion(
        versionId: json['versionId']?.toString() ?? '',
        label: json['versionLabel']?.toString() ?? '',
        artifactFingerprint: json['artifactFingerprint']?.toString() ?? '',
        featureSchemaFingerprint: json['featureSchemaFingerprint']?.toString() ?? '',
        trainingDatasetFingerprint: json['trainingDatasetFingerprint']?.toString() ?? '',
        codeSha: json['codeSha']?.toString() ?? '',
        trainingCutoff: DateTime.tryParse(json['trainingCutoff']?.toString() ?? ''),
      );
}

class EmergencyModelControl {
  const EmergencyModelControl({
    required this.type,
    required this.scope,
    required this.active,
    required this.reason,
    this.updatedAt,
  });

  final String type;
  final String scope;
  final bool active;
  final String reason;
  final DateTime? updatedAt;

  factory EmergencyModelControl.fromJson(Map<String, dynamic> json) => EmergencyModelControl(
        type: json['controlType']?.toString() ?? 'UNKNOWN',
        scope: json['scopeKey']?.toString() ?? '',
        active: json['active'] == true,
        reason: json['reason']?.toString() ?? '',
        updatedAt: DateTime.tryParse(json['updatedAt']?.toString() ?? ''),
      );
}

abstract class ModelControlRepository {
  Future<List<RegisteredModel>> models();
  Future<List<RegisteredModelVersion>> versions(String modelKey);
  Future<List<EmergencyModelControl>> emergencyControls();
}

class HttpModelControlRepository implements ModelControlRepository {
  HttpModelControlRepository(this.api);

  final AuthenticatedApiClient api;
  static const _root = '/api/admin/model-governance';

  @override
  Future<List<RegisteredModel>> models() async {
    final json = await api.getJson('$_root/models');
    return _items(json).map(RegisteredModel.fromJson).toList(growable: false);
  }

  @override
  Future<List<RegisteredModelVersion>> versions(String modelKey) async {
    if (!RegExp(r'^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$').hasMatch(modelKey)) {
      throw const FormatException('Invalid registered model key');
    }
    final json = await api.getJson('$_root/models/${Uri.encodeComponent(modelKey)}/versions');
    return _items(json).map(RegisteredModelVersion.fromJson).toList(growable: false);
  }

  @override
  Future<List<EmergencyModelControl>> emergencyControls() async {
    final json = await api.getJson('$_root/emergency-controls');
    return _items(json).map(EmergencyModelControl.fromJson).toList(growable: false);
  }

  static List<Map<String, dynamic>> _items(Object? response) {
    if (response is! List) {
      throw const FormatException('Expected a model-control evidence array');
    }
    return response.map((value) {
      if (value is! Map<String, dynamic>) {
        throw const FormatException('Invalid model-control evidence item');
      }
      return value;
    }).toList(growable: false);
  }
}
