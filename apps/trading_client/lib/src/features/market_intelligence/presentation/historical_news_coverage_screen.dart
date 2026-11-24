import 'package:flutter/material.dart';

import '../data/historical_news_coverage_repository.dart';

/// Each page is an observation, not atomic multi-page certification.
class HistoricalNewsCoverageScreen extends StatefulWidget {
  const HistoricalNewsCoverageScreen({required this.repository, required this.initialCutoff, super.key});
  final HistoricalNewsCoverageRepository repository;
  final DateTime initialCutoff;

  @override
  State<HistoricalNewsCoverageScreen> createState() => _HistoricalNewsCoverageScreenState();
}

class _HistoricalNewsCoverageScreenState extends State<HistoricalNewsCoverageScreen> {
  HistoricalNewsCoveragePage? _page;
  late DateTime _cutoff = widget.initialCutoff.toUtc();
  bool _loading = false;
  bool _failed = false;

  Future<void> _read({int offset = 0}) async {
    if (_loading) return;
    final previous = _page;
    setState(() { _loading = true; _failed = false; _page = null; });
    try {
      final page = await widget.repository.read(asOf: _cutoff, offset: offset,
        version: offset == 0 ? null : previous?.version,
        fingerprint: offset == 0 ? null : previous?.fingerprint);
      if (!mounted) return;
      setState(() { _page = page; _loading = false; });
    } catch (_) {
      if (!mounted) return;
      setState(() { _failed = true; _loading = false; });
    }
  }

  Future<void> _chooseDate() async {
    final now = DateTime.now().toUtc();
    final date = await showDatePicker(context: context,
      initialDate: DateTime(_cutoff.year, _cutoff.month, _cutoff.day),
      firstDate: DateTime(2000), lastDate: DateTime(now.year, now.month, now.day));
    if (date == null || !mounted) return;
    final end = DateTime.utc(date.year, date.month, date.day, 23, 59, 59);
    setState(() { _cutoff = end.isAfter(now) ? now : end; _page = null; _failed = false; });
  }

  @override
  Widget build(BuildContext context) {
    final page = _page;
    return Scaffold(
      appBar: AppBar(title: const Text('Historical news coverage')),
      body: SafeArea(child: ListView(padding: const EdgeInsets.all(16), children: [
        Text('Research cutoff (UTC): ${_cutoff.toIso8601String()}'),
        Wrap(spacing: 8, children: [
          OutlinedButton(onPressed: _loading ? null : _chooseDate, child: const Text('Choose date')),
          FilledButton(onPressed: _loading ? null : () => _read(), child: const Text('Measure coverage')),
        ]),
        const Text('NSE and BSE cash equities only. Results reflect the selected catalogue and current source permissions. No news evidence does not mean no news.'),
        if (_loading) const LinearProgressIndicator(),
        if (_failed) const Text('Coverage unavailable. Start a new measurement; no counts have been inferred.',
          key: ValueKey('historical-coverage-unavailable')),
        if (page != null) ...[
          const SizedBox(height: 16),
          Text('Catalogue instruments: ${page.total}'),
          Text('Page measured: ${page.measured} (offset ${page.offset})'),
          if (page.total > 0) ...[
            LinearProgressIndicator(value: (page.offset + page.measured) / page.total),
            Text('Catalogue traversal: ${((page.offset + page.measured) * 100 / page.total).toStringAsFixed(1)}%'),
          ],
          const Text('Counts below apply only to this page; traversal is not evidence coverage or certification.'),
          _metric('Verified with evidence', page.withEvidence),
          _metric('Verified without evidence', page.withoutEvidence),
          _metric('Unverified identity', page.unverified),
          _metric('Unavailable', page.unavailable),
          if (page.nextOffset != null)
            OutlinedButton(key: const ValueKey('historical-coverage-next'),
              onPressed: _loading ? null : () => _read(offset: page.nextOffset!),
              child: const Text('Next page')),
          Text('Catalogue version: ${page.version}', style: Theme.of(context).textTheme.labelSmall),
        ],
      ])),
    );
  }

  Widget _metric(String label, int value) => ListTile(
    dense: true, contentPadding: EdgeInsets.zero, title: Text(label), trailing: Text('$value'));
}
