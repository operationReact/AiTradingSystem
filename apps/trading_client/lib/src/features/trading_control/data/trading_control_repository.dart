import '../../../data/authenticated_api_client.dart';

enum TradingMode { paper, live }
enum OrderExecutionMode { manual, automated }

class TradingControlState {
  const TradingControlState({
    required this.tradingMode,
    required this.executionMode,
    required this.liveAvailable,
  });

  final TradingMode tradingMode;
  final OrderExecutionMode executionMode;
  final bool liveAvailable;

  factory TradingControlState.fromJson(Map<String, dynamic> json) {
    return TradingControlState(
      tradingMode: switch ('${json['tradingMode']}'.toUpperCase()) {
        'LIVE' => TradingMode.live,
        _ => TradingMode.paper,
      },
      executionMode: switch ('${json['executionMode']}'.toUpperCase()) {
        'AUTOMATED' => OrderExecutionMode.automated,
        _ => OrderExecutionMode.manual,
      },
      liveAvailable: json['liveAvailable'] == true,
    );
  }
}

abstract interface class TradingControlRepository {
  Future<TradingControlState> load();
  Future<TradingControlState> update({
    TradingMode? tradingMode,
    OrderExecutionMode? executionMode,
  });
}

class HttpTradingControlRepository implements TradingControlRepository {
  HttpTradingControlRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<TradingControlState> load() async {
    final payload = await api.getJson('/api/system/trading-control');
    return _parse(payload);
  }

  @override
  Future<TradingControlState> update({
    TradingMode? tradingMode,
    OrderExecutionMode? executionMode,
  }) async {
    final payload = await api.putJson(
      '/api/system/trading-control',
      body: {
        if (tradingMode != null)
          'tradingMode': tradingMode == TradingMode.paper ? 'PAPER' : 'LIVE',
        if (executionMode != null)
          'executionMode': executionMode == OrderExecutionMode.manual ? 'MANUAL' : 'AUTOMATED',
      },
    );
    return _parse(payload);
  }

  TradingControlState _parse(Object? payload) {
    if (payload is! Map<String, dynamic>) {
      throw const ApiTransportFailure('INVALID_TRADING_CONTROL_RESPONSE', null);
    }
    return TradingControlState.fromJson(payload);
  }
}
