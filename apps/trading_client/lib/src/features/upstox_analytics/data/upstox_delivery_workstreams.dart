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
