import '../../../data/authenticated_api_client.dart';

/// Server engine capability registry (contract `mind.research-engines.v1`). An engine is connected only
/// when the server says so; a name in the UI never makes it available.
class MindEngine {
  const MindEngine({required this.id, required this.name, required this.category, required this.connected,
      required this.enabled, required this.status, required this.capabilities,
      required this.dependencies, required this.missing});
  final String id, name, category, status, missing;
  final bool connected, enabled;
  final List<String> capabilities, dependencies;
}

class MindEngineRegistry {
  const MindEngineRegistry({required this.generatedAt, required this.total, required this.connected, required this.engines});
  final DateTime generatedAt;
  final int total, connected;
  final List<MindEngine> engines;

  factory MindEngineRegistry.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['contractVersion'] != 'mind.research-engines.v1') {
      throw const FormatException('Unsupported engine registry');
    }
    final at = DateTime.tryParse('${raw['generatedAt']}');
    final list = raw['engines'];
    if (at == null || list is! List) throw const FormatException('Invalid engine registry');
    final engines = [
      for (final e in list)
        if (e is Map<String, dynamic>) MindEngine(
          id: '${e['id']}', name: '${e['name']}', category: '${e['category']}',
          connected: e['connected'] == true, enabled: e['enabled'] == true, status: '${e['status']}',
          capabilities: [for (final c in (e['capabilities'] as List? ?? const [])) '$c'],
          dependencies: [for (final d in (e['dependencies'] as List? ?? const [])) '$d'],
          missing: '${e['missing']}'),
    ];
    // A connected or enabled engine must never be presented without its own runtime evidence.
    if (engines.any((e) => (e.connected || e.enabled) && e.status != 'CONNECTED')) {
      throw const FormatException('Engine state is inconsistent');
    }
    return MindEngineRegistry(generatedAt: at, total: engines.length,
        connected: engines.where((e) => e.connected).length, engines: engines);
  }
}

abstract interface class MindResearchEngineRepository {
  Future<MindEngineRegistry> load();
}

class HttpMindResearchEngineRepository implements MindResearchEngineRepository {
  HttpMindResearchEngineRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MindEngineRegistry> load() async =>
      MindEngineRegistry.fromJson(await api.getJson('/api/customer/mind/v1/research-engines'));
}
