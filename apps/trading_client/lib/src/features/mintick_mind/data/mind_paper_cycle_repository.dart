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
  Future<MindPaperCycleResult> run({required String roomId, required int instrumentId, required String interval});
  Future<List<MindPaperCycle>> recent(String roomId);
}

class HttpMindPaperCycleRepository implements MindPaperCycleRepository {
  HttpMindPaperCycleRepository(this.api, {Random? random}) : _random = random ?? Random.secure();
  final AuthenticatedApiClient api;
  final Random _random;
  static const intervals = ['FIFTEEN_MINUTE', 'FIVE_MINUTE', 'ONE_HOUR', 'ONE_DAY'];

  @override
  Future<List<MindPaperRoom>> rooms() async => [
        for (final room in await HttpCustomerRoomRepository(api).list())
          if (room.mode == 'PAPER' && room.status == 'PAPER_FUNDED') MindPaperRoom(room.id, room.name),
      ];

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
  Future<MindPaperCycleResult> run({required String roomId, required int instrumentId, required String interval}) async {
    _room(roomId);
    if (!intervals.contains(interval) || instrumentId < 1) throw ArgumentError('Invalid PAPER cycle request');
    final key = 'mindcycle-${List.generate(20, (_) => _random.nextInt(36).toRadixString(36)).join()}';
    return MindPaperCycleResult.parse(await api.postJson('/api/customer/trading-rooms/$roomId/mind/paper-cycles',
        headers: {'Idempotency-Key': key},
        body: {'instrumentId': instrumentId, 'interval': interval}));
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
