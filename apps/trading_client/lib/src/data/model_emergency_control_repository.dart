import 'authenticated_api_client.dart';

/// De-risk-only controls. Restoring a model requires a separate governed workflow.
/// Java governance and risk remain authoritative; no LIVE or capital endpoint exists.
class ModelEmergencyControl {
  const ModelEmergencyControl({
    required this.type,
    required this.scopeKey,
    required this.active,
    required this.reason,
    required this.stateFingerprint,
    this.updatedAt,
  });

  final String type;
  final String scopeKey;
  final bool active;
  final String reason;
  final String stateFingerprint;
  final DateTime? updatedAt;

  factory ModelEmergencyControl.fromJson(Map<String, dynamic> json) => ModelEmergencyControl(
        type: json['controlType']?.toString() ?? '',
        scopeKey: json['scopeKey']?.toString() ?? '',
        active: json['active'] == true,
        reason: json['reason']?.toString() ?? '',
        stateFingerprint: json['stateFingerprint']?.toString() ?? '',
        updatedAt: DateTime.tryParse(json['updatedAt']?.toString() ?? ''),
      );
}

abstract class ModelEmergencyControlRepository {
  Future<List<ModelEmergencyControl>> list();
  Future<ModelEmergencyControl> disableVersion(String versionId, String reason);
  Future<ModelEmergencyControl> disableFamily(String family, String reason);
  Future<ModelEmergencyControl> enableStaticFallback(String reason);
}

class HttpModelEmergencyControlRepository implements ModelEmergencyControlRepository {
  const HttpModelEmergencyControlRepository(this.api);

  final AuthenticatedApiClient api;
  static const String _root = '/api/admin/model-governance/emergency-controls';

  @override
  Future<List<ModelEmergencyControl>> list() async {
    final data = await api.getJson(_root);
    if (data is! List) throw const FormatException('Invalid model control collection');
    return data.map((row) => ModelEmergencyControl.fromJson(_map(row)))
        .toList(growable: false);
  }

  @override
  Future<ModelEmergencyControl> disableVersion(String versionId, String reason) {
    if (!RegExp(r'^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$').hasMatch(versionId)) {
      throw const FormatException('A registered model version UUID is required');
    }
    return _mutate('$_root/models/${Uri.encodeComponent(versionId)}',
        <String, Object>{'disabled': true, 'reason': _reason(reason)});
  }

  @override
  Future<ModelEmergencyControl> disableFamily(String family, String reason) {
    if (!RegExp(r'^[A-Z0-9][A-Z0-9_]{1,39}$').hasMatch(family)) {
      throw const FormatException('An exact registered model family is required');
    }
    return _mutate('$_root/families/${Uri.encodeComponent(family)}',
        <String, Object>{'disabled': true, 'reason': _reason(reason)});
  }

  @override
  Future<ModelEmergencyControl> enableStaticFallback(String reason) =>
      _mutate('$_root/static-fallback',
          <String, Object>{'enabled': true, 'reason': _reason(reason)});

  Future<ModelEmergencyControl> _mutate(String path, Map<String, Object> body) async {
    final data = await api.putJson(path, body: body);
    return ModelEmergencyControl.fromJson(_map(data));
  }

  static String _reason(String reason) {
    final trimmed = reason.trim();
    if (trimmed.length < 10 || trimmed.length > 512) {
      throw const FormatException('An audit reason between 10 and 512 characters is required');
    }
    return trimmed;
  }

  static Map<String, dynamic> _map(Object? data) {
    if (data is! Map<String, dynamic>) {
      throw const FormatException('Invalid Java emergency-control evidence');
    }
    return data;
  }
}
