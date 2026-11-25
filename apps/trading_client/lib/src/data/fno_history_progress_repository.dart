import 'authenticated_api_client.dart';

/// Only current planned job batches. This is not full NSE_FO market coverage.
/// No market prices, contract identities, tokens or unsupported "5%" estimates.
abstract interface class FnoHistoryProgressRepository {
  Future<FnoHistoryProgress> load();
}

class HttpFnoHistoryProgressRepository implements FnoHistoryProgressRepository {
  const HttpFnoHistoryProgressRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<FnoHistoryProgress> load() async {
    final raw = await api.getJson('/api/customer/fno-history/progress');
    return FnoHistoryProgress.parse(raw);
  }
}

class FnoHistoryProgress {
  const FnoHistoryProgress({
    required this.state,
    required this.evidence,
    required this.generatedAt,
    required this.total,
    required this.completed,
    required this.percent,
    required this.rows,
    required this.counts,
  });

  final String state;
  final String evidence;
  final DateTime? generatedAt;
  final int? total;
  final int? completed;
  final double? percent;
  final int? rows;
  final Map<String, int>? counts;

  static const _states = {
    'NOT_CONFIGURED', 'UNAVAILABLE', 'STALE', 'PREPARATION_ONLY',
  };
  static const _names = {
    'PENDING', 'ACTIVE', 'RETRY', 'DONE', 'EMPTY', 'FAILED', 'BLOCKED',
  };

  bool get hasPlannedBatches => total != null && total! > 0;

  static FnoHistoryProgress parse(Object? value) {
    if (value is! Map<String, dynamic> ||
        value['schema'] != 'minttick.fno.history.progress.v1' ||
        value['source'] != 'upstox-expired-nse-fo' ||
        value['planScope'] != 'CURRENT_JOURNAL_BATCHES_ONLY' ||
        value['retentionRightsVerified'] != false ||
        value['storageActive'] != false ||
        !_states.contains(value['state']) ||
        value['evidence'] is! String) {
      throw const FormatException('Untrusted F&O checkpoint metadata');
    }

    final state = value['state'] as String;
    final evidence = value['evidence'] as String;
    final rawTime = value['generatedAt'];
    final generated = rawTime is String ? DateTime.tryParse(rawTime) : null;
    final unconfigured = state == 'NOT_CONFIGURED' || state == 'UNAVAILABLE';
    if (unconfigured) {
      if (generated != null ||
          value['completionPercent'] != null ||
          value['totalBatches'] != null ||
          value['completedBatches'] != null ||
          value['rowsWritten'] != null) {
        throw const FormatException('Missing journal cannot have completion');
      }
      return FnoHistoryProgress(
        state: state, evidence: evidence, generatedAt: null,
        total: null, completed: null, percent: null, rows: null,
        counts: null,
      );
    }

    if (generated == null ||
        generated.toUtc().isAfter(
            DateTime.now().toUtc().add(const Duration(minutes: 5)))) {
      throw const FormatException('Invalid checkpoint timestamp');
    }

    final rawCounts = value['counts'];
    if (rawCounts is! Map<String, dynamic> ||
        rawCounts.keys.toSet().difference(_names).isNotEmpty ||
        rawCounts.length != _names.length) {
      throw const FormatException('Invalid F&O checkpoint state counts');
    }
    final counts = <String, int>{};
    for (final key in _names) {
      final n = rawCounts[key];
      if (n is! int || n < 0 || n > 1000000000) {
        throw const FormatException('Invalid F&O checkpoint counter');
      }
      counts[key] = n;
    }

    final total = value['totalBatches'];
    final completed = value['completedBatches'];
    final percent = value['completionPercent'];
    final rows = value['rowsWritten'];

    if (total == null) {
      if (completed != null || percent != null || rows != null ||
          counts.values.any((c) => c != 0)) {
        throw const FormatException('Unknown plan cannot claim completion');
      }
      return FnoHistoryProgress(
        state: state, evidence: evidence, generatedAt: generated,
        total: null, completed: null, percent: null, rows: null,
        counts: Map.unmodifiable(counts),
      );
    }
    if (total is! int || total <= 0 || total > 1000000000 ||
        completed is! int || completed < 0 || completed > total ||
        rows is! int || rows < 0 ||
        percent is! num || !percent.toDouble().isFinite ||
        percent < 0 || percent > 100 ||
        counts.values.fold<int>(0, (sum, v) => sum + v) != total ||
        counts['DONE']! + counts['EMPTY']! != completed ||
        (percent.toDouble() -
          ((completed * 10000 / total).round() / 100)).abs() > 0.011) {
      throw const FormatException('Inconsistent F&O checkpoint plan');
    }
    return FnoHistoryProgress(
      state: state, evidence: evidence, generatedAt: generated,
      total: total, completed: completed,
      percent: percent.toDouble(), rows: rows,
      counts: Map.unmodifiable(counts),
    );
  }
}
