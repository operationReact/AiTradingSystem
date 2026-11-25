import '../../../data/authenticated_api_client.dart';

/// Virtual PAPER portfolio across funded rooms (contract `mind.paper-portfolio.v1`).
class MindPaperPosition {
  const MindPaperPosition({required this.roomId, required this.instrumentId, required this.tradingSymbol,
      required this.quantity, required this.costBasisPaise, required this.markState,
      this.markPrice, this.markAt, this.marketValuePaise, this.unrealizedPnlPaise});
  final String roomId;
  final int instrumentId;
  final String tradingSymbol;
  final int quantity;
  final int costBasisPaise;
  final String markState; // FRESH | STALE | MISSING
  final num? markPrice;
  final DateTime? markAt;
  final int? marketValuePaise;
  final int? unrealizedPnlPaise;
}

class MindPaperRoomSummary {
  const MindPaperRoomSummary({required this.roomId, required this.name, required this.initialCashPaise,
      required this.availableCashPaise, required this.realizedPnlPaise, required this.paused,
      required this.openPositions});
  final String roomId;
  final String name;
  final int initialCashPaise;
  final int availableCashPaise;
  final int realizedPnlPaise;
  final bool paused;
  final int openPositions;
}

class MindPaperPortfolio {
  const MindPaperPortfolio({required this.generatedAt, required this.fundedRooms,
      required this.initialCashPaise, required this.availableCashPaise, required this.realizedPnlPaise,
      required this.costBasisPaise, required this.staleOrMissingMarks, required this.rooms,
      required this.positions, required this.policy, this.marketValuePaise, this.unrealizedPnlPaise,
      this.grossExposurePercent});
  final DateTime generatedAt;
  final int fundedRooms;
  final int initialCashPaise;
  final int availableCashPaise;
  final int realizedPnlPaise;
  final int costBasisPaise;
  final int? marketValuePaise;
  final int? unrealizedPnlPaise;
  final double? grossExposurePercent;
  final int staleOrMissingMarks;
  final List<MindPaperRoomSummary> rooms;
  final List<MindPaperPosition> positions;
  /// Ordered label -> enforced value.
  final List<(String, String)> policy;

  /// Virtual equity only when every position has a mark.
  int? get equityPaise => marketValuePaise == null ? null : availableCashPaise + marketValuePaise!;

  factory MindPaperPortfolio.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['contractVersion'] != 'mind.paper-portfolio.v1' ||
        raw['liveMoney'] != false) {
      throw const FormatException('Portfolio must be the explicit virtual PAPER contract');
    }
    int req(Object? v) => v is int ? v : throw const FormatException('Invalid portfolio amount');
    final at = DateTime.tryParse('${raw['generatedAt']}');
    final policy = raw['riskPolicy'];
    if (at == null || policy is! Map<String, dynamic>) throw const FormatException('Invalid portfolio');
    const labels = {
      'perTradeRiskBudget': 'Per-trade risk budget',
      'maxSingleOrderExposure': 'Max single-order exposure',
      'maxQuoteAge': 'Quote freshness',
      'maxSignalQuoteDivergence': 'Signal vs quote divergence',
      'sessionGate': 'Session gate',
      'sizing': 'Position sizing',
      'exits': 'Exits',
      'liveTrading': 'LIVE trading',
    };
    return MindPaperPortfolio(
      generatedAt: at,
      fundedRooms: req(raw['fundedRooms']),
      initialCashPaise: req(raw['initialCashPaise']),
      availableCashPaise: req(raw['availableCashPaise']),
      realizedPnlPaise: req(raw['realizedPnlPaise']),
      costBasisPaise: req(raw['costBasisPaise']),
      marketValuePaise: raw['marketValuePaise'] as int?,
      unrealizedPnlPaise: raw['unrealizedPnlPaise'] as int?,
      grossExposurePercent: (raw['grossExposurePercent'] as num?)?.toDouble(),
      staleOrMissingMarks: req(raw['staleOrMissingMarks']),
      rooms: [
        for (final r in (raw['rooms'] as List? ?? const []))
          if (r is Map<String, dynamic>)
            MindPaperRoomSummary(roomId: '${r['roomId']}', name: '${r['name']}',
                initialCashPaise: req(r['initialCashPaise']), availableCashPaise: req(r['availableCashPaise']),
                realizedPnlPaise: req(r['realizedPnlPaise']), paused: r['paused'] == true,
                openPositions: req(r['openPositions'])),
      ],
      positions: [
        for (final p in (raw['positions'] as List? ?? const []))
          if (p is Map<String, dynamic>)
            MindPaperPosition(roomId: '${p['roomId']}', instrumentId: req(p['instrumentId']),
                tradingSymbol: '${p['tradingSymbol']}', quantity: req(p['quantity']),
                costBasisPaise: req(p['costBasisPaise']),
                markState: const {'FRESH', 'STALE', 'MISSING'}.contains(p['markState'])
                    ? p['markState'] as String : throw const FormatException('Invalid mark state'),
                markPrice: p['markPrice'] as num?,
                markAt: p['markAt'] is String ? DateTime.tryParse(p['markAt'] as String) : null,
                marketValuePaise: p['marketValuePaise'] as int?,
                unrealizedPnlPaise: p['unrealizedPnlPaise'] as int?),
      ],
      policy: [for (final e in labels.entries) if (policy[e.key] is String) (e.value, policy[e.key] as String)],
    );
  }
}

abstract interface class MindPaperPortfolioRepository {
  Future<MindPaperPortfolio> load();
  /// Real PAPER room pause/resume (existing wallet endpoints). Never touches LIVE.
  Future<void> setPaused(String roomId, bool paused);
}

class HttpMindPaperPortfolioRepository implements MindPaperPortfolioRepository {
  HttpMindPaperPortfolioRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MindPaperPortfolio> load() async =>
      MindPaperPortfolio.fromJson(await api.getJson('/api/customer/mind/v1/paper-portfolio'));

  @override
  Future<void> setPaused(String roomId, bool paused) async {
    if (!RegExp(r'^[0-9a-fA-F-]{36}$').hasMatch(roomId)) throw ArgumentError.value(roomId, 'roomId');
    await api.postJson('/api/customer/trading-rooms/$roomId/paper/${paused ? 'pause' : 'resume'}');
  }
}

String formatPaise(int paise) {
  final negative = paise < 0;
  final abs = paise.abs();
  final rupees = (abs ~/ 100).toString().replaceAllMapped(RegExp(r'\B(?=(\d{3})+(?!\d))'), (_) => ',');
  return '${negative ? '-' : ''}₹$rupees.${(abs % 100).toString().padLeft(2, '0')}';
}
