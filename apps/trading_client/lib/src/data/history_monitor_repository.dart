import 'authenticated_api_client.dart';

/// Only metadata produced by the owner-private EC2 archive exporter.
///
/// A year containing candles is NOT automatically a fully covered year.
abstract interface class HistoryMonitorRepository {
  Future<HistoricalProgress> load();
}

class HttpHistoryMonitorRepository implements HistoryMonitorRepository {
  const HttpHistoryMonitorRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<HistoricalProgress> load() async {
    final raw = await api.getJson('/api/customer/owner-history/v1/progress');
    return HistoricalProgress.parse(raw);
  }
}

class HistoryYear {
  const HistoryYear(this.year, this.candles, this.stocks, this.firstDate,
      this.lastDate, this.completeness);

  final int year;
  final int candles;
  final int stocks;
  final String? firstDate;
  final String? lastDate;
  final String completeness;

  factory HistoryYear.parse(Object? value) {
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Unverified year data');
    }
    final year = _number(value, 'year');
    final candles = _number(value, 'candles');
    final stocks = _number(value, 'stocks');
    final completeness = _string(value, 'completeness');
    if (year < 2006 || year > 2026 || candles < 0 || stocks < 0 ||
        (completeness != 'UNVERIFIED' && completeness != 'NO_DATA')) {
      throw const FormatException('Invalid yearly coverage');
    }
    return HistoryYear(year, candles, stocks,
        value['firstDate'] as String?, value['lastDate'] as String?,
        completeness);
  }
}

class HistoryRun {
  const HistoryRun(this.startedAt, this.finishedAt,
      this.windowsQueried, this.newCandles, this.state);

  final DateTime startedAt;
  final DateTime finishedAt;
  final int windowsQueried;
  final int newCandles;
  final String state;

  factory HistoryRun.parse(Object? value) {
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Invalid historical job');
    }
    final state = _string(value, 'state');
    final first = DateTime.parse(_string(value, 'startedAt'));
    final last = DateTime.parse(_string(value, 'finishedAt'));
    if (!{'COMPLETED', 'FAILED'}.contains(state) ||
        last.isBefore(first)) {
      throw const FormatException('Invalid job state');
    }
    return HistoryRun(first, last, _number(value, 'windowsQueried'),
        _number(value, 'newCandles'), state);
  }
}

class HistoryRequest {
  const HistoryRequest(this.symbol, this.fromDate, this.throughDate,
      this.outcome, this.returnedCandles);
  final String symbol;
  final String fromDate;
  final String throughDate;
  final String outcome;
  final int? returnedCandles;

  factory HistoryRequest.parse(Object? value) {
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Invalid request ledger');
    }
    return HistoryRequest(_string(value, 'symbol'),
        _string(value, 'fromDate'), _string(value, 'throughDate'),
        _string(value, 'outcome'), value['returnedCandles'] as int?);
  }
}

class HistoricalProgress {
  const HistoricalProgress({
    required this.generatedAt,
    required this.status,
    required this.source,
    required this.taskName,
    required this.interval,
    required this.requestedFrom,
    required this.requestedThrough,
    required this.candles,
    required this.stocksWithCandles,
    required this.archivePages,
    required this.failedWindows,
    required this.pendingRecoveryScans,
    required this.years,
    required this.schedulerCadence,
    required this.weekdayHours,
    required this.weekendHours,
    required this.nextScheduledAt,
    required this.pausedUntil,
    required this.zeroCandleStreak,
    required this.recentRuns,
    required this.recentRequests,
  });

  final DateTime generatedAt;
  final String status;
  final String source;
  final String taskName;
  final String interval;
  final String requestedFrom;
  final String requestedThrough;
  final int candles;
  final int stocksWithCandles;
  final int archivePages;
  final int failedWindows;
  final int pendingRecoveryScans;
  final List<HistoryYear> years;
  final String schedulerCadence;
  final String weekdayHours;
  final String weekendHours;
  final DateTime? nextScheduledAt;
  final DateTime? pausedUntil;
  final int zeroCandleStreak;
  final List<HistoryRun> recentRuns;
  final List<HistoryRequest> recentRequests;

  int get yearsWithCandles => years.where((y) => y.candles > 0).length;
  int get missingYears => years.where((y) => y.candles == 0).length;

  static HistoricalProgress parse(Object? raw) {
    if (raw is! Map<String, dynamic> ||
        raw['schema'] != 'minttick.owner.history.progress.v1' ||
        raw['source'] != 'ANGEL_ONE') {
      throw const FormatException('Invalid owner history progress');
    }
    final task = raw['task'];
    final scheduler = raw['scheduler'];
    if (task is! Map<String, dynamic> ||
        scheduler is! Map<String, dynamic> ||
        task['yearCoverageVerified'] != false ||
        task['listingHistoryVerified'] != false) {
      throw const FormatException('Unverified historical custody');
    }
    final yearValues = task['years'];
    final runValues = raw['recentRuns'];
    final requestValues = raw['recentRequests'];
    if (yearValues is! List || yearValues.length != 21 ||
        runValues is! List || runValues.length > 8 ||
        requestValues is! List || requestValues.length > 12) {
      throw const FormatException('Unexpected progress history length');
    }
    final years = yearValues.map(HistoryYear.parse).toList(growable: false);
    if (years.map((year) => year.year).toSet().length != 21 ||
        years.first.year != 2006 || years.last.year != 2026) {
      throw const FormatException('Year coverage is incomplete');
    }
    final status = _string(raw, 'status');
    if (!{'RUNNING', 'IDLE', 'PAUSED', 'ERROR'}.contains(status) ||
        task['interval'] != 'ONE_DAY' ||
        !{'HOURLY_OFF_HOURS', 'BOUNDED_CONTINUOUS_OFF_HOURS',
          'BOUNDED_CONTINUOUS_DAILY'}.contains(scheduler['cadence'])) {
      throw const FormatException('Unsupported owner historical job');
    }
    final count = _number(task, 'candles');
    if (count != years.fold<int>(0, (sum, year) => sum + year.candles)) {
      throw const FormatException('Archive/year totals disagree');
    }
    DateTime? parseOptional(Object? value) =>
        value is String && value.isNotEmpty ? DateTime.parse(value) : null;
    return HistoricalProgress(
      generatedAt: DateTime.parse(_string(raw, 'generatedAt')),
      status: status,
      source: _string(raw, 'source'),
      taskName: _string(task, 'name'),
      interval: _string(task, 'interval'),
      requestedFrom: _string(task, 'requestedFrom'),
      requestedThrough: _string(task, 'requestedThrough'),
      candles: count,
      stocksWithCandles: _number(task, 'stocksWithCandles'),
      archivePages: _number(task, 'archivePages'),
      failedWindows: _number(task, 'failedWindows'),
      pendingRecoveryScans: _number(task, 'pendingRecoveryScans'),
      years: List.unmodifiable(years),
      schedulerCadence: _string(scheduler, 'cadence'),
      weekdayHours: _string(scheduler, 'weekdayHoursIST'),
      weekendHours: _string(scheduler, 'weekendHoursIST'),
      nextScheduledAt: parseOptional(scheduler['nextScheduledAt']),
      pausedUntil: parseOptional(scheduler['pausedUntil']),
      zeroCandleStreak: _number(scheduler, 'zeroCandleStreak'),
      recentRuns: List.unmodifiable(runValues.map(HistoryRun.parse)),
      recentRequests: List.unmodifiable(requestValues.map(HistoryRequest.parse)),
    );
  }
}

int _number(Map<String, dynamic> source, String name) {
  final value = source[name];
  if (value is! int || value < 0 || value > 1000000000) {
    throw FormatException('Invalid $name');
  }
  return value;
}

String _string(Map<String, dynamic> source, String name) {
  final value = source[name];
  if (value is! String || value.isEmpty || value.length > 200) {
    throw FormatException('Invalid $name');
  }
  return value;
}
