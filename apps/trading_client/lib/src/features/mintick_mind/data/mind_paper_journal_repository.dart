import '../../../data/authenticated_api_client.dart';
import 'mind_paper_cycle_repository.dart';

/// Closed-trade-only PAPER performance. `sampleTooSmall` is shown, never hidden.
class MindPaperPerformance {
  const MindPaperPerformance({required this.cycles, required this.fills, required this.noEntry,
      required this.blocked, required this.closedTrades, required this.wins, required this.losses,
      required this.realizedPnlPaise, required this.maxDrawdownPaise, required this.sampleTooSmall,
      required this.minimumMeaningfulSample, this.averagePnlPaise, this.winRatePercent});
  final int cycles, fills, noEntry, blocked, closedTrades, wins, losses;
  final int realizedPnlPaise, maxDrawdownPaise, minimumMeaningfulSample;
  final int? averagePnlPaise;
  final double? winRatePercent;
  final bool sampleTooSmall;
}

class MindPaperJournal {
  const MindPaperJournal({required this.generatedAt, required this.cycles, required this.performance});
  final DateTime generatedAt;
  final List<MindPaperCycle> cycles;
  final MindPaperPerformance performance;

  factory MindPaperJournal.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['contractVersion'] != 'mind.paper-journal.v1' ||
        raw['liveMoney'] != false) {
      throw const FormatException('Journal must be the explicit virtual PAPER contract');
    }
    final at = DateTime.tryParse('${raw['generatedAt']}');
    final p = raw['performance'];
    if (at == null || p is! Map<String, dynamic> || raw['cycles'] is! List || p['sampleTooSmall'] is! bool) {
      throw const FormatException('Invalid journal');
    }
    int req(Object? v) => v is int ? v : throw const FormatException('Invalid journal count');
    final perf = MindPaperPerformance(
      cycles: req(p['cycles']), fills: req(p['fills']), noEntry: req(p['noEntry']), blocked: req(p['blocked']),
      closedTrades: req(p['closedTrades']), wins: req(p['wins']), losses: req(p['losses']),
      realizedPnlPaise: req(p['realizedPnlPaise']), maxDrawdownPaise: req(p['maxDrawdownPaise']),
      sampleTooSmall: p['sampleTooSmall'] as bool, minimumMeaningfulSample: req(p['minimumMeaningfulSample']),
      averagePnlPaise: p['averagePnlPaise'] as int?, winRatePercent: (p['winRatePercent'] as num?)?.toDouble(),
    );
    if (perf.wins + perf.losses > perf.closedTrades) throw const FormatException('Journal does not reconcile');
    return MindPaperJournal(
      generatedAt: at,
      cycles: [for (final c in raw['cycles'] as List) MindPaperCycle.parse(c)],
      performance: perf,
    );
  }
}

abstract interface class MindPaperJournalRepository {
  Future<MindPaperJournal> load();
}

class HttpMindPaperJournalRepository implements MindPaperJournalRepository {
  HttpMindPaperJournalRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MindPaperJournal> load() async =>
      MindPaperJournal.fromJson(await api.getJson('/api/customer/mind/v1/paper-journal', query: {'limit': '100'}));
}
