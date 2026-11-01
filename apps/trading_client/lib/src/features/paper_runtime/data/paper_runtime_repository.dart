import '../../../data/authenticated_api_client.dart';

enum PaperExecutionMode { manual, automated }

class PaperRuntimeState {
  const PaperRuntimeState({
    required this.tradingMode,
    required this.executionMode,
    required this.riskEngineActive,
    required this.emergencyStopped,
    required this.liveAvailable,
  });

  final String tradingMode;
  final PaperExecutionMode executionMode;
  final bool riskEngineActive;
  final bool emergencyStopped;
  final bool liveAvailable;

  bool get automated => executionMode == PaperExecutionMode.automated;
  bool get automationRunning => automated && !emergencyStopped;

  factory PaperRuntimeState.fromJson({
    required Map<String, dynamic> control,
    required Map<String, dynamic> risk,
  }) {
    final execution = '${control['executionMode'] ?? control['orderExecutionMode'] ?? 'MANUAL'}'.toUpperCase();
    return PaperRuntimeState(
      tradingMode: '${control['tradingMode'] ?? risk['tradingMode'] ?? 'PAPER'}'.toUpperCase(),
      executionMode: execution == 'AUTOMATED' ? PaperExecutionMode.automated : PaperExecutionMode.manual,
      riskEngineActive: true,
      emergencyStopped: risk['newOrdersHalted'] == true,
      liveAvailable: control['liveAvailable'] == true,
    );
  }
}

abstract interface class PaperRuntimeRepository {
  Future<PaperRuntimeState> fetch();
  Future<PaperRuntimeState> setEmergencyStop({required bool halted});
}

class HttpPaperRuntimeRepository implements PaperRuntimeRepository {
  HttpPaperRuntimeRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<PaperRuntimeState> fetch() async {
    final results = await Future.wait([
      api.getJson('/api/system/trading-control'),
      api.getJson('/api/risk/config'),
    ]);
    if (results[0] is! Map || results[1] is! Map) {
      throw const ApiTransportFailure('INVALID_PAPER_RUNTIME_RESPONSE', null);
    }
    return PaperRuntimeState.fromJson(
      control: Map<String, dynamic>.from(results[0] as Map),
      risk: Map<String, dynamic>.from(results[1] as Map),
    );
  }

  @override
  Future<PaperRuntimeState> setEmergencyStop({required bool halted}) async {
    await api.putJson(
      '/api/risk/emergency-stop',
      body: {
        'halted': halted,
        'reason': halted ? 'Customer emergency stop' : 'Customer resumed PAPER automation',
      },
    );
    return fetch();
  }
}
