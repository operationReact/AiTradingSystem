import 'authenticated_api_client.dart';

class BacktestRunSummary {
  const BacktestRunSummary({
    required this.id,
    required this.instrumentId,
    required this.exchange,
    required this.tradingSymbol,
    required this.interval,
    required this.fromDate,
    required this.toDate,
    required this.initialCapital,
    required this.tradeCount,
    required this.winningTrades,
    required this.losingTrades,
    required this.winRatePercent,
    required this.netPnl,
    required this.finalEquity,
    required this.totalReturnPercent,
    required this.maxDrawdownPercent,
    required this.profitFactor,
    required this.createdAt,
  });

  final String id;
  final int instrumentId;
  final String exchange;
  final String tradingSymbol;
  final String interval;
  final DateTime? fromDate;
  final DateTime? toDate;
  final num initialCapital;
  final int tradeCount;
  final int winningTrades;
  final int losingTrades;
  final num winRatePercent;
  final num netPnl;
  final num finalEquity;
  final num totalReturnPercent;
  final num maxDrawdownPercent;
  final num profitFactor;
  final DateTime? createdAt;

  factory BacktestRunSummary.fromJson(Map<String, dynamic> json) {
    num number(String key) => json[key] is num ? json[key] as num : num.tryParse('${json[key] ?? 0}') ?? 0;
    return BacktestRunSummary(
      id: '${json['id'] ?? ''}',
      instrumentId: number('instrumentId').toInt(),
      exchange: '${json['exchange'] ?? ''}',
      tradingSymbol: '${json['tradingSymbol'] ?? ''}',
      interval: '${json['interval'] ?? ''}',
      fromDate: DateTime.tryParse('${json['fromDate'] ?? ''}'),
      toDate: DateTime.tryParse('${json['toDate'] ?? ''}'),
      initialCapital: number('initialCapital'),
      tradeCount: number('tradeCount').toInt(),
      winningTrades: number('winningTrades').toInt(),
      losingTrades: number('losingTrades').toInt(),
      winRatePercent: number('winRatePercent'),
      netPnl: number('netPnl'),
      finalEquity: number('finalEquity'),
      totalReturnPercent: number('totalReturnPercent'),
      maxDrawdownPercent: number('maxDrawdownPercent'),
      profitFactor: number('profitFactor'),
      createdAt: DateTime.tryParse('${json['createdAt'] ?? ''}'),
    );
  }
}

abstract interface class BacktestRepository {
  Future<List<BacktestRunSummary>> recent({int limit = 20});
  Future<BacktestRunSummary> run({
    required int instrumentId,
    required String interval,
    required DateTime fromDate,
    required DateTime toDate,
    required num initialCapital,
  });
}

class HttpBacktestRepository implements BacktestRepository {
  HttpBacktestRepository(this.api);

  final AuthenticatedApiClient api;

  String _date(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')}';

  @override
  Future<List<BacktestRunSummary>> recent({int limit = 20}) async {
    final decoded = await api.getJson('/api/backtests', query: {'limit': '$limit'});
    if (decoded is! List<dynamic>) {
      throw const ApiTransportFailure('INVALID_BACKTEST_HISTORY_RESPONSE', null);
    }
    return decoded
        .whereType<Map<String, dynamic>>()
        .map(BacktestRunSummary.fromJson)
        .toList(growable: false);
  }

  @override
  Future<BacktestRunSummary> run({
    required int instrumentId,
    required String interval,
    required DateTime fromDate,
    required DateTime toDate,
    required num initialCapital,
  }) async {
    final decoded = await api.postJson(
      '/api/backtests',
      body: {
        'instrumentId': instrumentId,
        'interval': interval,
        'fromDate': _date(fromDate),
        'toDate': _date(toDate),
        'initialCapital': initialCapital,
      },
      timeout: const Duration(seconds: 60),
    );
    if (decoded is! Map<String, dynamic>) {
      throw const ApiTransportFailure('INVALID_BACKTEST_RUN_RESPONSE', null);
    }
    return BacktestRunSummary.fromJson(decoded);
  }
}
