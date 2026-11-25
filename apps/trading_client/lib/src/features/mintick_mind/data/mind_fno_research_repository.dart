import '../../../data/authenticated_api_client.dart';

/// Authenticated, read-only research snapshot. Never order authority.
class MindFnoResearch {
  const MindFnoResearch({
    required this.status, required this.signal, required this.reasonCode,
    required this.evaluatedAt, required this.completedCandles,
    this.strategyId, this.candleSource, this.latestCompletedCloseAt,
    this.expiry, this.strike, this.instrumentKey, this.lotSize,
    this.bid, this.ask, this.ltp, this.openInterest, this.iv, this.delta,
    this.theta, this.gamma, this.vega, this.exchangeAt, this.observedAt,
  });

  final String status, signal, reasonCode;
  final DateTime evaluatedAt;
  final int completedCandles;
  final String? strategyId, candleSource, instrumentKey;
  final DateTime? latestCompletedCloseAt, expiry, exchangeAt, observedAt;
  final num? strike, bid, ask, ltp, iv, delta, theta, gamma, vega;
  final int? openInterest;
  final int? lotSize;
  bool get hasCandidate => status == 'RESEARCH_CANDIDATE';

  factory MindFnoResearch.parse(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['contractVersion'] != 'mind.fno-research.v1' ||
        raw['underlyingKey'] != 'NSE_INDEX|Nifty 50' ||
        raw['executable'] != false || raw['orderPlaced'] != false ||
        raw['status'] is! String ||
        !const {'BLOCKED', 'NO_TRADE', 'RESEARCH_CANDIDATE'}.contains(raw['status']) ||
        raw['signal'] is! String ||
        !const {'NO_TRADE', 'BUY_CALL_RESEARCH', 'BUY_PUT_RESEARCH'}.contains(raw['signal']) ||
        raw['reasonCode'] is! String ||
        (raw['reasonCode'] as String).isEmpty ||
        (raw['reasonCode'] as String).length > 90 ||
        raw['completedCandles'] is! int || (raw['completedCandles'] as int) < 0 ||
        (raw['completedCandles'] as int) > 64 ||
        raw['evaluatedAt'] is! String) {
      throw const FormatException('Unsafe F&O research response');
    }
    final at = DateTime.tryParse(raw['evaluatedAt'] as String);
    if (at == null) throw const FormatException('Missing research time');
    final String status = raw['status'] as String;
    final String signal = raw['signal'] as String;
    final closed = raw['latestCompletedCloseAt'] is String
      ? DateTime.tryParse(raw['latestCompletedCloseAt'] as String) : null;
    if (raw['latestCompletedCloseAt'] != null && closed == null) {
      throw const FormatException('Invalid NIFTY candle timestamp');
    }
    if (status == 'RESEARCH_CANDIDATE') {
      final id = signal == 'BUY_CALL_RESEARCH'
          ? 'strategy.STR-181' : 'strategy.STR-182';
      final key = raw['instrumentKey'];
      final expiry = raw['expiry'] is String ? DateTime.tryParse(raw['expiry'] as String) : null;
      final exchange = raw['exchangeAt'] is String
          ? DateTime.tryParse(raw['exchangeAt'] as String) : null;
      final observed = raw['observedAt'] is String
          ? DateTime.tryParse(raw['observedAt'] as String) : null;
      final bid = raw['bid'], ask = raw['ask'], strike = raw['strike'];
      if ((raw['ltp'] != null && (raw['ltp'] is! num || (raw['ltp'] as num) <= 0)) ||
          (raw['openInterest'] != null && (raw['openInterest'] is! int || (raw['openInterest'] as int) < 0)) ||
          [raw['iv'], raw['delta'], raw['theta'], raw['gamma'], raw['vega']]
              .any((v) => v != null && v is! num) ||
          signal == 'NO_TRADE' || raw['strategyId'] != id ||
          key is! String || !RegExp(r'^NSE_FO\|[A-Za-z0-9._-]{1,64}$').hasMatch(key) ||
          raw['quoteSource'] != 'UPSTOX_V3_EXCHANGE_TIMED' ||
          !const {'ANGEL_ONE_SMARTAPI', 'UPSTOX_ANALYTICS'}
              .contains(raw['candleSource']) ||
          expiry == null || exchange == null || observed == null ||
          exchange.isAfter(at) || observed.isAfter(at) ||
          observed.isBefore(exchange) ||
          at.difference(exchange) > const Duration(seconds: 15) ||
          closed == null || closed.isAfter(at) ||
          raw['lotSize'] is! int || (raw['lotSize'] as int) < 1 ||
          bid is! num || ask is! num || strike is! num ||
          bid <= 0 || ask < bid || strike <= 0) {
        throw const FormatException('Unverified research or F&O quote provenance');
      }
      return MindFnoResearch(
        status: status, signal: signal, reasonCode: raw['reasonCode'] as String,
        evaluatedAt: at, completedCandles: raw['completedCandles'] as int,
        strategyId: id, candleSource: raw['candleSource'] as String,
        latestCompletedCloseAt: closed, expiry: expiry, strike: strike,
        instrumentKey: key, lotSize: raw['lotSize'] as int,
        bid: bid, ask: ask, ltp: raw['ltp'] as num?,
        openInterest: raw['openInterest'] as int?, iv: raw['iv'] as num?,
        delta: raw['delta'] as num?, theta: raw['theta'] as num?,
        gamma: raw['gamma'] as num?, vega: raw['vega'] as num?,
        exchangeAt: exchange, observedAt: observed,
      );
    }
    if (signal != 'NO_TRADE' || raw['strategyId'] != null ||
        raw['instrumentKey'] != null || raw['bid'] != null || raw['ask'] != null) {
      throw const FormatException('Blocked F&O research must abstain');
    }
    return MindFnoResearch(
      status: status, signal: signal, reasonCode: raw['reasonCode'] as String,
      evaluatedAt: at, completedCandles: raw['completedCandles'] as int,
      candleSource: raw['candleSource'] as String?,
      latestCompletedCloseAt: closed,
    );
  }
}

abstract interface class MindFnoResearchRepository {
  Future<MindFnoResearch> evaluate();
}

class HttpMindFnoResearchRepository implements MindFnoResearchRepository {
  const HttpMindFnoResearchRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MindFnoResearch> evaluate() async =>
    MindFnoResearch.parse(await api.getJson('/api/customer/mind/v1/fno-evaluation'));
}
