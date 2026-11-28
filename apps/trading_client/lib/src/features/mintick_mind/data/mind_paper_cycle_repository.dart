import 'dart:math';

import '../../../data/authenticated_api_client.dart';
import '../../trading_rooms/data/customer_market_repository.dart';
import '../../trading_rooms/data/customer_room_repository.dart';

/// Server outcome of one owner-initiated Mind PAPER cycle. Virtual money only.
enum MindPaperOutcome {
  filled('FILLED', 'PAPER FILL'),
  noEntry('NO_ENTRY', 'NO ENTRY'),
  dataBlocked('DATA_BLOCKED', 'DATA BLOCKED'),
  quoteUnavailable('QUOTE_UNAVAILABLE', 'NO FRESH QUOTE'),
  rejectedByGate('REJECTED_BY_GATE', 'REJECTED BY GATE'),
  failedUnknown('FAILED_UNKNOWN', 'UNKNOWN · NOT RETRIED');

  const MindPaperOutcome(this.code, this.label);
  final String code;
  final String label;

  static MindPaperOutcome parse(Object? raw) => values.firstWhere((v) => v.code == raw,
      orElse: () => throw const FormatException('Unknown PAPER cycle outcome'));
}

class MindPaperStage {
  const MindPaperStage(this.code, this.result, this.detail);
  final String code;
  final String result;
  final String detail;
}

class MindPaperCycle {
  const MindPaperCycle({
    required this.id, required this.instrumentId, required this.tradingSymbol,
    required this.interval, required this.strategyCode, required this.outcome,
    required this.reasons, required this.stages, required this.createdAt,
    required this.dataLabel, this.signalAction, this.side, this.quantity,
    this.limitPrice, this.quoteLtp, this.stopPrice, this.targetPrice,
    this.candleTime, this.orderId,
  });
  final String id;
  final int instrumentId;
  final String tradingSymbol;
  final String interval;
  final String strategyCode;
  final MindPaperOutcome outcome;
  final List<String> reasons;
  final List<MindPaperStage> stages;
  final DateTime createdAt;
  final String dataLabel;
  final String? signalAction;
  final String? side;
  final int? quantity;
  final num? limitPrice;
  final num? quoteLtp;
  final num? stopPrice;
  final num? targetPrice;
  final DateTime? candleTime;
  final String? orderId;

  static MindPaperCycle parse(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['liveOrder'] != false) {
      // Any payload that is not explicitly non-LIVE is rejected.
      throw const FormatException('PAPER cycle must be explicitly non-LIVE');
    }
    final id = raw['id'], instrument = raw['instrumentId'], created = DateTime.tryParse('${raw['createdAt']}');
    if (id is! String || instrument is! int || created == null || raw['strategyCode'] is! String ||
        raw['interval'] is! String || raw['dataLabel'] is! String) {
      throw const FormatException('Invalid PAPER cycle');
    }
    final outcome = MindPaperOutcome.parse(raw['outcome']);
    if (outcome == MindPaperOutcome.filled && (raw['orderId'] is! String || raw['quantity'] is! int)) {
      throw const FormatException('Fill without order');
    }
    return MindPaperCycle(
      id: id, instrumentId: instrument,
      tradingSymbol: raw['tradingSymbol'] is String ? raw['tradingSymbol'] as String : '#$instrument',
      interval: raw['interval'] as String, strategyCode: raw['strategyCode'] as String,
      outcome: outcome,
      reasons: [for (final r in (raw['reasons'] is List ? raw['reasons'] as List : const [])) if (r is String) r],
      stages: [
        for (final s in (raw['stages'] is List ? raw['stages'] as List : const []))
          if (s is Map<String, dynamic>) MindPaperStage('${s['code']}', '${s['result']}', '${s['detail'] ?? ''}'),
      ],
      createdAt: created, dataLabel: raw['dataLabel'] as String,
      signalAction: raw['signalAction'] as String?, side: raw['side'] as String?,
      quantity: raw['quantity'] as int?, limitPrice: raw['limitPrice'] as num?,
      quoteLtp: raw['quoteLtp'] as num?, stopPrice: raw['stopPrice'] as num?,
      targetPrice: raw['targetPrice'] as num?,
      candleTime: raw['candleTime'] is String ? DateTime.tryParse(raw['candleTime'] as String) : null,
      orderId: raw['orderId'] as String?,
    );
  }
}

class MindPaperHolding {
  const MindPaperHolding(this.instrumentId, this.quantity, this.avgCostPaise, this.markPrice, this.unrealizedPnlPaise);
  final int instrumentId;
  final int quantity;
  final int avgCostPaise;
  final num? markPrice;
  final int? unrealizedPnlPaise;
}

class MindPaperCycleResult {
  const MindPaperCycleResult({required this.cycle, required this.availableCashPaise,
      required this.realizedPnlPaise, required this.holdings, this.fillPrice});
  final MindPaperCycle cycle;
  final int availableCashPaise;
  final int realizedPnlPaise;
  final List<MindPaperHolding> holdings;
  final num? fillPrice;

  static MindPaperCycleResult parse(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['availableCashPaise'] is! int || raw['realizedPnlPaise'] is! int) {
      throw const FormatException('Invalid PAPER cycle result');
    }
    final order = raw['order'];
    return MindPaperCycleResult(
      cycle: MindPaperCycle.parse(raw['cycle']),
      availableCashPaise: raw['availableCashPaise'] as int,
      realizedPnlPaise: raw['realizedPnlPaise'] as int,
      fillPrice: order is Map<String, dynamic> ? order['fillPrice'] as num? : null,
      holdings: [
        for (final h in (raw['holdings'] is List ? raw['holdings'] as List : const []))
          if (h is Map<String, dynamic> && h['instrumentId'] is int && h['quantity'] is int && h['avgCostPaise'] is int)
            MindPaperHolding(h['instrumentId'] as int, h['quantity'] as int, h['avgCostPaise'] as int,
                h['markPrice'] as num?, h['unrealizedPnlPaise'] as int?),
      ],
    );
  }
}

class MindPaperRoom {
  const MindPaperRoom(this.id, this.name);
  final String id;
  final String name;
}

class MindPaperInstrument {
  const MindPaperInstrument(this.instrumentId, this.label);
  final int instrumentId;
  final String label;
}

abstract interface class MindPaperCycleRepository {
  /// Funded PAPER rooms only.
  Future<List<MindPaperRoom>> rooms();
  /// The owner's watchlist; the cycle never picks instruments on its own.
  Future<List<MindPaperInstrument>> instruments();
  Future<MindPaperCycleResult> run({required String roomId, required int instrumentId,
      required String interval, required String strategyCode});
  Future<List<MindPaperCycle>> recent(String roomId);
}

/// Optional owner-confirmed bridge from a fresh PAPER signal to the guarded LIVE
/// cash-equity order coordinator.
abstract interface class MindLiveStrategyProposalRepository {
  Future<List<MindPaperRoom>> liveIntradayRooms();
  Future<MindLiveStrategyProposal> createLiveProposal({
    required String liveRoomId,
    required String sourcePaperRoomId,
    required String sourceCycleId,
  });
  Future<Object?> submitLiveProposal({
    required String liveRoomId,
    required String proposalId,
  });
}

class MindLiveStrategyProposal {
  const MindLiveStrategyProposal({
    required this.id,
    required this.selectedBroker,
    required this.strategyCode,
    required this.tradingSymbol,
    required this.side,
    required this.quantity,
    required this.limitPrice,
    required this.stopPrice,
    required this.targetPrice,
    required this.expiresAt,
    required this.status,
  });

  final String id;
  final String selectedBroker;
  final String strategyCode;
  final String tradingSymbol;
  final String side;
  final int quantity;
  final num limitPrice;
  final num stopPrice;
  final num targetPrice;
  final DateTime expiresAt;
  final String status;

  static MindLiveStrategyProposal parse(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['id'] is! String ||
        raw['selectedBroker'] is! String ||
        raw['strategyCode'] is! String ||
        raw['tradingSymbol'] is! String ||
        raw['side'] is! String ||
        raw['quantity'] is! int ||
        raw['limitPrice'] is! num ||
        raw['stopPrice'] is! num ||
        raw['targetPrice'] is! num ||
        raw['expiresAt'] is! String ||
        raw['status'] is! String) {
      throw const FormatException('Invalid LIVE strategy proposal');
    }
    final expiresAt = DateTime.tryParse(raw['expiresAt'] as String);
    if (expiresAt == null ||
        !const ['ANGEL_ONE', 'UPSTOX'].contains(raw['selectedBroker']) ||
        !const ['BUY', 'SELL'].contains(raw['side']) ||
        !const ['PENDING', 'EXPIRED', 'SUBMITTED'].contains(raw['status'])) {
      throw const FormatException('Inconsistent LIVE strategy proposal');
    }
    return MindLiveStrategyProposal(
      id: raw['id'] as String,
      selectedBroker: raw['selectedBroker'] as String,
      strategyCode: raw['strategyCode'] as String,
      tradingSymbol: raw['tradingSymbol'] as String,
      side: raw['side'] as String,
      quantity: raw['quantity'] as int,
      limitPrice: raw['limitPrice'] as num,
      stopPrice: raw['stopPrice'] as num,
      targetPrice: raw['targetPrice'] as num,
      expiresAt: expiresAt,
      status: raw['status'] as String,
    );
  }
}

class HttpMindPaperCycleRepository implements MindPaperCycleRepository, MindLiveStrategyProposalRepository {
  HttpMindPaperCycleRepository(this.api, {Random? random}) : _random = random ?? Random.secure();
  final AuthenticatedApiClient api;
  final Random _random;
  static const intervals = ['ONE_MINUTE', 'THREE_MINUTE', 'FIVE_MINUTE',
    'FIFTEEN_MINUTE', 'ONE_HOUR', 'ONE_DAY'];

  @override
  Future<List<MindPaperRoom>> rooms() async => [
        for (final room in await HttpCustomerRoomRepository(api).list())
          if (room.mode == 'PAPER' &&
              room.status == 'PAPER_FUNDED' &&
              room.focus == 'INTRADAY' &&
              (room.marketScope == 'EQUITY' || room.marketScope == 'ALL'))
            MindPaperRoom(room.id, room.name),
      ];

  @override
  Future<List<MindPaperRoom>> liveIntradayRooms() async => [
        for (final room in await HttpCustomerRoomRepository(api).list())
          if (room.mode == 'LIVE' &&
              room.focus == 'INTRADAY' &&
              (room.marketScope == 'EQUITY' || room.marketScope == 'ALL'))
            MindPaperRoom(room.id, room.name),
      ];

  @override
  Future<MindLiveStrategyProposal> createLiveProposal({
    required String liveRoomId,
    required String sourcePaperRoomId,
    required String sourceCycleId,
  }) async {
    _room(liveRoomId);
    _room(sourcePaperRoomId);
    _room(sourceCycleId);
    return MindLiveStrategyProposal.parse(await api.postJson(
      '/api/customer/trading-rooms/$liveRoomId/live/equity/strategy-proposals',
      body: {'sourcePaperRoomId': sourcePaperRoomId, 'sourceCycleId': sourceCycleId},
    ));
  }

  @override
  Future<Object?> submitLiveProposal({
    required String liveRoomId,
    required String proposalId,
  }) async {
    _room(liveRoomId);
    _room(proposalId);
    return api.postJson(
      '/api/customer/trading-rooms/$liveRoomId/live/equity/strategy-proposals/$proposalId/submit',
      headers: {'X-Confirm-Live-Equity-Order': 'CONFIRM_LIVE_INTRADAY_EQUITY'},
    );
  }

  @override
  Future<List<MindPaperInstrument>> instruments() async {
    final raw = await api.getJson('/api/customer/watchlist');
    if (raw is! Map<String, dynamic>) throw const FormatException('Invalid watchlist');
    return [
      for (final item in CustomerWatchlist.fromJson(raw).items)
        MindPaperInstrument(item.instrumentId, '${item.tradingSymbol} · ${item.exchange}'),
    ];
  }

  @override
  Future<MindPaperCycleResult> run({required String roomId, required int instrumentId,
      required String interval, required String strategyCode}) async {
    _room(roomId);
    final intraday = const ['OPENING_RANGE_BREAKOUT_V1', 'VWAP_MOMENTUM_V1',
      'MA_RECLAIM_V1', 'VOLUME_BREAKOUT_V1', 'RSI_REVERSAL_V1'].contains(strategyCode);
    final allowedIntervals = intraday
        ? const ['ONE_MINUTE', 'THREE_MINUTE', 'FIVE_MINUTE'] : intervals;
    if (!allowedIntervals.contains(interval) ||
        !const ['SMA_TREND_VOLUME_V1', 'OPENING_RANGE_BREAKOUT_V1', 'VWAP_MOMENTUM_V1',
          'MA_RECLAIM_V1', 'VOLUME_BREAKOUT_V1', 'RSI_REVERSAL_V1']
            .contains(strategyCode) ||
        instrumentId < 1) {
      throw ArgumentError('Invalid PAPER cycle request');
    }
    final key = 'mindcycle-${List.generate(20, (_) => _random.nextInt(36).toRadixString(36)).join()}';
    return MindPaperCycleResult.parse(await api.postJson('/api/customer/trading-rooms/$roomId/mind/paper-cycles',
        headers: {'Idempotency-Key': key},
        body: {'instrumentId': instrumentId, 'interval': interval, 'strategyCode': strategyCode}));
  }

  @override
  Future<List<MindPaperCycle>> recent(String roomId) async {
    _room(roomId);
    final raw = await api.getJson('/api/customer/trading-rooms/$roomId/mind/paper-cycles');
    if (raw is! List) throw const FormatException('Invalid PAPER cycle history');
    return [for (final c in raw) MindPaperCycle.parse(c)];
  }

  static void _room(String id) {
    if (!RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(id)) throw ArgumentError.value(id, 'roomId');
  }
}
