import '../../../data/authenticated_api_client.dart';
import '../../trading_rooms/data/customer_room_repository.dart';
import '../../trading_rooms/data/customer_upstox_fno_live_repository.dart';

/// An authenticated customer-owned F&O LIVE room; no order authority.
class MindFnoLiveRoom {
  const MindFnoLiveRoom(this.id, this.name);
  final String id;
  final String name;
}

/// A one-time server-side snapshot. A proposal is never an executable order.
class MindFnoLiveProposal {
  const MindFnoLiveProposal({
    required this.state,
    required this.reasons,
    required this.evaluatedAt,
    this.strategyId,
    this.signal,
    this.instrumentKey,
    this.expiry,
    this.units,
    this.limitPrice,
    this.premiumInr,
    this.maximumPremiumInr,
    this.stopReference,
    this.targetReference,
    this.exchangeAt,
  });

  final String state;
  final List<String> reasons;
  final DateTime evaluatedAt;
  final String? strategyId, signal, instrumentKey;
  final String? expiry;
  final int? units;
  final num? limitPrice, premiumInr, maximumPremiumInr;
  final num? stopReference, targetReference;
  final DateTime? exchangeAt;

  bool get isRiskPassed => state == 'RISK_PASSED_DRY_RUN';

  factory MindFnoLiveProposal.parse(Object? value) {
    if (value is! Map<String, dynamic> ||
        value['contractVersion'] != 'mind.fno-live-proposal.v1' ||
        value['executionAuthorized'] != false ||
        value['orderPlaced'] != false ||
        !const {'BLOCKED', 'RISK_PASSED_DRY_RUN'}
            .contains(value['state']) ||
        value['roomId'] is! String ||
        value['reasons'] is! List ||
        value['evaluatedAt'] is! String) {
      throw const FormatException('Unsafe F&O LIVE proposal response');
    }
    final at = DateTime.tryParse(value['evaluatedAt'] as String);
    if (at == null) {
      throw const FormatException('Proposal evaluation time missing');
    }
    final reasons = <String>[
      for (final r in (value['reasons'] as List))
        if (r is String && RegExp(r'^[A-Z0-9_]{1,90}$').hasMatch(r)) r,
    ];
    if (reasons.length != (value['reasons'] as List).length) {
      throw const FormatException('Unrecognized LIVE proposal reason');
    }

    if (value['state'] == 'RISK_PASSED_DRY_RUN') {
      final strategy = value['strategyId'];
      final side = value['signal'];
      final key = value['instrumentKey'];
      final observed = value['exchangeAt'];
      final when = observed is String ? DateTime.tryParse(observed) : null;
      final limit = value['limitPrice'];
      final premium = value['premiumInr'];
      final cap = value['maximumPremiumInr'];
      final stop = value['stopReference'];
      final target = value['targetReference'];
      if (reasons.isNotEmpty ||
          !(strategy == 'strategy.STR-181' || strategy == 'strategy.STR-182') ||
          side != (strategy == 'strategy.STR-181'
              ? 'BUY_CALL_RESEARCH' : 'BUY_PUT_RESEARCH') ||
          key is! String ||
          !RegExp(r'^NSE_FO\|[A-Za-z0-9._-]{1,64}$').hasMatch(key) ||
          value['quoteSource'] != 'UPSTOX_V3_EXCHANGE_TIMED' ||
          value['expiry'] is! String ||
          DateTime.tryParse(value['expiry'] as String) == null ||
          value['lots'] != 1 ||
          value['units'] is! int || (value['units'] as int) < 1 ||
          limit is! num || limit <= 0 ||
          premium is! num || premium <= 0 ||
          cap is! num || premium > cap ||
          stop is! num || stop <= 0 || stop >= limit ||
          target is! num || target <= limit ||
          when == null || when.isAfter(at) ||
          at.difference(when) > const Duration(seconds: 15)) {
        throw const FormatException('Unverified or stale F&O proposal');
      }
      return MindFnoLiveProposal(
        state: 'RISK_PASSED_DRY_RUN',
        reasons: const [],
        evaluatedAt: at,
        strategyId: strategy as String,
        signal: side as String,
        instrumentKey: key,
        expiry: value['expiry'] as String,
        units: value['units'] as int,
        limitPrice: limit,
        premiumInr: premium,
        maximumPremiumInr: cap,
        stopReference: stop,
        targetReference: target,
        exchangeAt: when,
      );
    }
    if (reasons.isEmpty || value['instrumentKey'] != null ||
        value['proposalId'] != null || value['limitPrice'] != null) {
      throw const FormatException('Blocked F&O proposal cannot recommend an order');
    }
    return MindFnoLiveProposal(
      state: 'BLOCKED',
      reasons: reasons,
      evaluatedAt: at,
    );
  }
}

abstract interface class MindFnoLiveProposalRepository {
  Future<List<MindFnoLiveRoom>> rooms();
  Future<MindFnoLiveProposal> evaluate(String roomId);

  /// This interface has no broker order submission method.
  Future<List<UpstoxFnoLiveReceipt>> orderHistory(String roomId);
  /// User-invoked broker order-history read; never repeats the broker POST.
  Future<UpstoxFnoLiveReceipt> recheckBrokerOrder(String roomId, String orderId);
}

class HttpMindFnoLiveProposalRepository
    implements MindFnoLiveProposalRepository {
  const HttpMindFnoLiveProposalRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<List<MindFnoLiveRoom>> rooms() async => [
    for (final room in await HttpCustomerRoomRepository(api).list())
      if (room.mode == 'LIVE' &&
          room.marketScope == 'FNO' &&
          const {'Intraday', 'Swing'}.contains(room.focus))
        MindFnoLiveRoom(room.id, room.name),
  ];

  @override
  Future<MindFnoLiveProposal> evaluate(String roomId) async {
    if (!RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(roomId)) {
      throw const FormatException('Invalid LIVE F&O room identifier');
    }
    return MindFnoLiveProposal.parse(
      await api.getJson(
        '/api/customer/trading-rooms/$roomId/mind/fno/live-proposal',
      ),
    );
  }

  @override
  Future<List<UpstoxFnoLiveReceipt>> orderHistory(String roomId) =>
      CustomerUpstoxFnoLiveRepository(api).orders(roomId);

  @override
  Future<UpstoxFnoLiveReceipt> recheckBrokerOrder(
      String roomId, String orderId) =>
      CustomerUpstoxFnoLiveRepository(api).reconcile(roomId, orderId);

}
