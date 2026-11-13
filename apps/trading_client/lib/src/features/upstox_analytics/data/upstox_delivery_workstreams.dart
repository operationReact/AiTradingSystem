/// Recent GitHub development evidence grouped into the five Upstox data
/// workstreams. This is NOT an automation heartbeat or ingestion health signal.
class UpstoxDeliveryPr {
  const UpstoxDeliveryPr(this.number, this.title, this.state, this.url);
  final int number;
  final String title;
  final String state;
  final String url;
}

class UpstoxDeliveryWorkstream {
  const UpstoxDeliveryWorkstream(this.number, this.label, this.pr);
  final int number;
  final String label;
  final UpstoxDeliveryPr? pr;
}

/// The backend exposes only a bounded *recent* GitHub sample. Missing a PR
/// cannot establish worker inactivity; never convert an unobserved sample to 0.
class UpstoxDeliveryWorkstreams {
  static const names = <String>[
    'Source & ingestion',
    'Canonical datasets',
    'Coverage & gaps',
    'Fundamentals & events',
    'Operations UI',
  ];

  static List<UpstoxDeliveryWorkstream> fromRecentPrs(Object? raw) {
    final recent = <int, UpstoxDeliveryPr>{};
    if (raw is List) {
      for (final entry in raw) {
        if (entry is! Map) continue;
        final number = entry['number'];
        final title = entry['title'];
        final state = entry['state'];
        final url = entry['url'];
        if (number is! int || number <= 0 || number > 1000000 ||
            title is! String || title.isEmpty || title.length > 180 ||
            state is! String || !const {'OPEN', 'MERGED', 'CLOSED'}.contains(state) ||
            url is! String ||
            url != 'https://github.com/operationReact/AiTradingSystem/pull/$number') {
          continue;
        }
        final worker = _classify(title.toLowerCase());
        if (worker != null && !recent.containsKey(worker)) {
          // Input is ordered most-recent-first by the backend GitHub reader.
          recent[worker] = UpstoxDeliveryPr(number, title, state, url);
        }
      }
    }
    return List.unmodifiable([
      for (var worker = 1; worker <= names.length; worker++)
        UpstoxDeliveryWorkstream(worker, names[worker - 1], recent[worker]),
    ]);
  }

  // Title-based categorization is advisory only. Workers must publish
  // separately attested completion reports before claiming a heartbeat.
  static int? _classify(String text) {
    if (text.contains('upstox-ops') || text.contains('operations ui') ||
        text.contains('data workstreams') || text.contains('worker activity') ||
        text.contains('operational telemetry') || text.contains('analytics progress')) {
      return 5;
    }
    if (text.contains('fundamental') || text.contains('corporate-event') ||
        text.contains('corporate event') || text.contains('issuer filing')) {
      return 4;
    }
    if (text.contains('coverage') || text.contains('gap audit') ||
        text.contains('retry candidate') || text.contains('completeness')) {
      return 3;
    }
    if (text.contains('canonical') || text.contains('dataset') ||
        text.contains('candle window quality')) {
      return 2;
    }
    if (text.contains('upstox') || text.contains('source-window')) return 1;
    return null;
  }
}


/// Bounded developer reports fetched by the owner-only backend from issue
/// #1012. A report confirms a GitHub note, NOT scheduled execution, ingestion
/// or PAPER readiness.
class UpstoxWorkerObservation {
  const UpstoxWorkerObservation({
    required this.worker,
    required this.label,
    required this.state,
    this.result,
    this.pr,
    this.ci,
    this.blocker,
    this.next,
    this.reportedAt,
    this.url,
  });

  final int worker;
  final String label;
  final String state;
  final String? result;
  final int? pr;
  final String? ci;
  final String? blocker;
  final String? next;
  final DateTime? reportedAt;
  final String? url;

  static const scopes = [
    'SOURCE_INGESTION',
    'CANONICAL_DATA',
    'COVERAGE_GAPS',
    'FUNDAMENTALS_EVENTS',
    'OPERATIONS_UI',
  ];

  static List<UpstoxWorkerObservation> fromBackend(
      Object? raw, {bool staleSnapshot = false}) {
    final parsed = <int, UpstoxWorkerObservation>{};
    if (raw is List) {
      for (final item in raw) {
        if (item is! Map) continue;
        final number = item['worker'];
        final scope = item['scope'];
        final state = item['state'];
        if (number is! int || number < 1 || number > 5 ||
            scope != scopes[number - 1] ||
            state is! String ||
            !const {'FRESH', 'STALE', 'NO_REPORT'}.contains(state)) {
          continue;
        }
        String? bounded(Object? value, int max) =>
            value is String && value.length <= max ? value : null;
        final prText = bounded(item['pr'], 8);
        final pr = prText == null ? null : int.tryParse(prText);
        final url = pr != null && pr > 0 && pr < 1000000 &&
            item['url'] == 'https://github.com/operationReact/AiTradingSystem/pull/$pr'
            ? item['url'] as String : null;
        final observedAt = DateTime.tryParse(
            (item['reportedAt'] ?? '').toString());
        parsed[number] = UpstoxWorkerObservation(
          worker: number,
          label: UpstoxDeliveryWorkstreams.names[number - 1],
          state: staleSnapshot && state == 'FRESH' ? 'STALE' : state,
          result: bounded(item['result'], 24),
          pr: url == null ? null : pr,
          url: url,
          ci: bounded(item['ci'], 16),
          blocker: bounded(item['blocker'], 96),
          next: bounded(item['next'], 160),
          reportedAt: observedAt,
        );
      }
    }
    return List.unmodifiable([
      for (var i = 1; i <= 5; i++)
        parsed[i] ?? UpstoxWorkerObservation(
            worker: i,
            label: UpstoxDeliveryWorkstreams.names[i - 1],
            state: 'NO_REPORT'),
    ]);
  }
}
