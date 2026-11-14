import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../brand/minttick_brand.dart';
import '../../../data/authenticated_api_client.dart';
import '../data/upstox_analytics_progress_repository.dart';
import '../data/upstox_delivery_workstreams.dart';
import '../data/upstox_readiness_progress.dart';

/// Backend-evidence-only owner monitor. Never places PAPER/LIVE orders.
class UpstoxAnalyticsProgressPage extends StatefulWidget {
  const UpstoxAnalyticsProgressPage({required this.repository, super.key});
  final UpstoxAnalyticsProgressRepository repository;

  @override
  State<UpstoxAnalyticsProgressPage> createState() => _UpstoxAnalyticsProgressPageState();
}

class _UpstoxAnalyticsProgressPageState extends State<UpstoxAnalyticsProgressPage> {
  static const tabs = ['Overview', 'Coverage', 'Operations'];
  static const specs = <(String, String, String, IconData)>[
    ('SOURCE_VERIFICATION', 'Source verification', 'Analytics token and validated source reads', Icons.verified_user_outlined),
    ('INSTRUMENT_MAPPING', 'Instrument mapping', 'ISIN and canonical MintTick listing identity', Icons.account_tree_outlined),
    ('HISTORICAL_INGESTION', 'Historical ingestion', 'Candle windows, checkpoints, gaps', Icons.storage_outlined),
    ('QUALITY_RIGHTS', 'Quality & source rights', 'Data validity, permissions and reconciliation', Icons.shield_outlined),
    ('FUNDAMENTALS', 'Fundamentals', 'Ratios, corporate actions and statement sources', Icons.analytics_outlined),
    ('MIND_DATASETS', 'MintTick Mind feed', 'Canonical features for strategies and models', Icons.psychology_outlined),
    ('PAPER_READINESS', 'PAPER readiness', 'Risk, fresh quotes and virtual trade gates', Icons.science_outlined),
    ('GITHUB_DEVELOPMENT', 'GitHub development', 'Pull requests, CI and code delivery', Icons.code_outlined),
  ];

  Timer? refreshTimer;
  UpstoxAnalyticsProgress? snapshot;
  String activeTab = 'Overview';
  String? error;
  bool loading = true;
  int generation = 0;
  double? changeSinceRefresh;

  @override
  void initState() {
    super.initState();
    refresh();
    refreshTimer = Timer.periodic(const Duration(seconds: 30),
        (_) { if (!loading) refresh(); });
  }

  @override
  void dispose() {
    refreshTimer?.cancel();
    ++generation;
    super.dispose();
  }

  Future<void> refresh() async {
    final request = ++generation;
    if (!mounted) return;
    setState(() { loading = true; error = null; });
    try {
      final data = await widget.repository.load();
      if (!mounted || request != generation) return;
      final previous = snapshot == null ? null
          : UpstoxReadinessProgress.calculate(snapshot!).percent;
      final current = UpstoxReadinessProgress.calculate(data).percent;
      setState(() {
        changeSinceRefresh = previous == null || current == null
            ? null : (current - previous).toDouble();
        snapshot = data;
        error = null;
      });
    } on ApiHttpFailure catch (ex) {
      if (!mounted || request != generation) return;
      setState(() {
        snapshot = null;
        error = ex.statusCode == 403
          ? 'Owner authorization required for Analytics progress.'
          : 'Analytics progress API unavailable.';
      });
    } catch (_) {
      if (!mounted || request != generation) return;
      setState(() {
        snapshot = null;
        error = 'No authenticated Analytics progress snapshot available.';
      });
    } finally {
      if (mounted && request == generation) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final data = snapshot;
    return Scaffold(
      backgroundColor: MintTickPalette.nearBlack,
      appBar: AppBar(title: const Text('Upstox Analytics'),
        actions: [IconButton(tooltip: 'Refresh analytics progress',
          onPressed: loading ? null : refresh,
          icon: const Icon(Icons.refresh_rounded))]),
      body: SafeArea(child: Column(children: [
        Padding(padding: const EdgeInsets.fromLTRB(14, 4, 14, 12),
          child: Row(children: [
            const Icon(Icons.insights_outlined, color: MintTickPalette.mint, size: 24),
            const SizedBox(width: 10),
            const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Read-only market intelligence',
                  style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
                Text('Upstox → Canonical Data → MintTick Mind',
                  style: TextStyle(fontSize: 11, color: MintTickPalette.mutedSilver)),
              ])),
            status(data?.find('SOURCE_VERIFICATION'),
              labelOverride: data?.find('SOURCE_VERIFICATION')?.state == 'DISABLED'
                ? 'PROBE OFF' : null),
          ])),
        Padding(padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          child: Row(children: [for (final name in tabs)
            Expanded(child: Padding(padding: const EdgeInsets.symmetric(horizontal: 3),
              child: ChoiceChip(label: Center(child: Text(name,
                  maxLines: 1, overflow: TextOverflow.ellipsis)),
                selected: name == activeTab, showCheckmark: false,
                selectedColor: const Color(0xFF18231A),
                backgroundColor: MintTickPalette.darkCard,
                side: BorderSide(color: name == activeTab
                  ? MintTickPalette.mint : MintTickPalette.darkBorder),
                labelStyle: TextStyle(fontSize: 11,
                  color: name == activeTab ? MintTickPalette.mint
                    : MintTickPalette.mutedSilver),
                onSelected: (_) => setState(() => activeTab = name))))])),
        const Divider(color: MintTickPalette.darkBorder, height: 12),
        if (loading && data == null)
          const Expanded(child: Center(child: CircularProgressIndicator()))
        else if (error != null)
          Expanded(child: Center(child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              const Icon(Icons.cloud_off_outlined, color: Color(0xFFE6BD73), size: 30),
              const SizedBox(height: 12),
              Text(error!, textAlign: TextAlign.center),
              const SizedBox(height: 12),
              OutlinedButton(onPressed: refresh, child: const Text('Retry')),
            ]))))
        else if (data != null)
          Expanded(child: RefreshIndicator(
            onRefresh: refresh,
            child: ListView(
              key: const ValueKey('upstox-analytics-progress-list'),
              padding: const EdgeInsets.fromLTRB(14, 10, 14, 30),
              children: [
                if (activeTab == 'Overview') ...overview(data),
                if (activeTab == 'Coverage') ...acceptanceProgress(data),
                if (activeTab == 'Coverage') ...history(data), 
                if (activeTab == 'Coverage') ...quality(data),
                if (activeTab == 'Coverage') ...mind(data),
                if (activeTab == 'Operations') ...acceptanceProgress(data),
                if (activeTab == 'Operations') ...workerDelivery(data),
                if (activeTab == 'Operations') ...taskList(data, specs),
                if (activeTab == 'Operations') ...github(data),
                if (activeTab == 'Operations') ...paper(data),
              ],
            ),
          )),
      ])),
    );
  }

  List<Widget> overview(UpstoxAnalyticsProgress data) {
    final verified = data.tasks.where((t) => t.state == 'VERIFIED' && !t.stale).length;
    final blocked = data.tasks.where((t) =>
        t.state == 'BLOCKED' || t.state == 'FAILED').length;
    final active = data.tasks.where((t) => t.state == 'RUNNING').length;
    final source = data.find('SOURCE_VERIFICATION');
    // Only current, verified monitor observations may be displayed as evidence.
    final evidence = source?.state == 'VERIFIED' && source?.stale == false
        ? source!.details : const <String, dynamic>{};
    final candleCount = evidence['validatedDailyCandles'];
    final ratioCount = evidence['currentRatios'];
    final candles = candleCount is int && candleCount > 0 ? '$candleCount' : '—';
    final ratios = ratioCount is int && ratioCount > 0 ? '$ratioCount' : '—';
    return [
      ...acceptanceProgress(data),
      const SizedBox(height: 10),
      workerActivitySummary(data),
      const SizedBox(height: 14),
      Row(children: [
        const Expanded(child: Text('Analytics health',
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800))),
        Text('Updated ${when(data.generatedAt)}',
          style: const TextStyle(fontSize: 10, color: MintTickPalette.mutedSilver)),
      ]),
      const SizedBox(height: 10),
      Row(children: [
        metric('Candles', candles, Icons.bar_chart_outlined),
        const SizedBox(width: 6),
        metric('Ratios', ratios, Icons.analytics_outlined),
        const SizedBox(width: 6),
        metric('Passing checks', verified.toString(), Icons.check_circle_outline),
      ]),
      const SizedBox(height: 6),
      Text('Read-only sample · $active active · $blocked blocked · '
        'not a historical download',
        style: const TextStyle(fontSize: 10, color: MintTickPalette.mutedSilver)),
      const SizedBox(height: 13),
      if (source == null || source.state != 'VERIFIED' || source.stale)
        Padding(padding: const EdgeInsets.only(bottom: 12),
          child: panel(Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Icon(Icons.info_outline, size: 17,
              color: Color(0xFFE6BD73)),
            const SizedBox(width: 9),
            Expanded(child: Text(
              source == null ? 'No verified source telemetry received.'
                : source.stale ? 'Last source verification is stale.'
                : reasonDescription(source.reasonCode),
              style: const TextStyle(fontSize: 11,
                color: MintTickPalette.mutedSilver, height: 1.35))),
          ]), padding: const EdgeInsets.all(10))),
      Row(children: [
        const Expanded(child: Text('Data readiness',
          style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800))),
        TextButton(
          onPressed: () => setState(() => activeTab = 'Operations'),
          child: const Text('All details', style: TextStyle(fontSize: 12))),
      ]),
      panel(Column(children: [
        for (var i = 0; i < 4; i++)
          pipelineRow(specs[i], data.find(specs[i].$1), last: i == 3),
      ]), padding: EdgeInsets.zero),
      const SizedBox(height: 11),
      ...workerDelivery(data),
      const SizedBox(height: 11),
      Text('Analytics reads, dataset rights and trade execution are separate '
        'checks. Tap any stage for evidence and the latest failure reason.',
        style: const TextStyle(color: MintTickPalette.mutedSilver,
          fontSize: 11, height: 1.4)),
    ];
  }

  List<Widget> taskList(UpstoxAnalyticsProgress data,
      Iterable<(String, String, String, IconData)> source) =>
    [for (final spec in source) taskTile(spec, data.find(spec.$1))];

  List<Widget> acceptanceProgress(UpstoxAnalyticsProgress data) {
    final score = UpstoxReadinessProgress.calculate(data);
    return [
      panel(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Expanded(child: Text('Data & PAPER acceptance',
            style: TextStyle(fontSize: 13, fontWeight: FontWeight.w800))),
          Text(score.percent == null ? '—' : '${score.percent}%',
            key: const ValueKey('upstox-verified-completion-percent'),
            style: const TextStyle(color: MintTickPalette.mint,
              fontWeight: FontWeight.w800, fontSize: 20)),
        ]),
        const SizedBox(height: 8),
        if (score.fraction != null)
          LinearProgressIndicator(
            key: const ValueKey('upstox-verified-completion-bar'),
            minHeight: 5,
            value: score.fraction!,
            backgroundColor: MintTickPalette.darkBorder,
            valueColor: const AlwaysStoppedAnimation<Color>(MintTickPalette.mint),
          )
        else
          Container(height: 5, decoration: BoxDecoration(
            color: MintTickPalette.darkBorder,
            borderRadius: BorderRadius.circular(3))),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(child: Text(
            score.percent == null ? 'Completed: —'
              : 'Completed: ${score.percent}%',
            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 11))),
          Expanded(child: Text(
            score.remainingPercent == null ? 'Remaining: —'
              : 'Remaining: ${score.remainingPercent}%',
            textAlign: TextAlign.right,
            key: const ValueKey('upstox-remaining-completion-percent'),
            style: const TextStyle(fontWeight: FontWeight.w700,
              color: MintTickPalette.mutedSilver, fontSize: 11))),
        ]),
        const SizedBox(height: 5),
        Text('${score.verifiedStages} of ${score.gates.length} milestones verified · '
          '${score.outstandingStages} not fully verified',
          style: const TextStyle(color: MintTickPalette.mutedSilver, fontSize: 10)),
        if (changeSinceRefresh != null)
          Text('Since previous refresh: '
            '${changeSinceRefresh! > 0 ? '+' : ''}${changeSinceRefresh!.toStringAsFixed(1)} pp',
            key: const ValueKey('upstox-acceptance-refresh-delta'),
            style: TextStyle(fontSize: 10,
              color: changeSinceRefresh! > 0
                ? MintTickPalette.mint : MintTickPalette.mutedSilver)),
        Row(children: [
          const Expanded(child: Text('Verified + receipt-backed partial · no PR credit',
              maxLines: 2, style: TextStyle(color: MintTickPalette.mutedSilver,
                  fontSize: 10))),
          TextButton(
            onPressed: () => showProgressFormula(score),
            child: const Text('Pending details', style: TextStyle(fontSize: 11))),
        ]),
        if (!score.observed)
          const Text('No verified milestone telemetry yet',
            style: TextStyle(color: MintTickPalette.mutedSilver, fontSize: 11)),
      ]), padding: const EdgeInsets.fromLTRB(11, 10, 11, 8)),
    ];
  }

  void showProgressFormula(UpstoxReadinessProgress score) {
    final labels = <String, String>{
      'SOURCE_VERIFICATION': 'Source access',
      'INSTRUMENT_MAPPING': 'Instrument mapping',
      'HISTORICAL_INGESTION': 'Persistent historical data',
      'QUALITY_RIGHTS': 'Quality and usage rights',
      'FUNDAMENTALS': 'Fundamentals',
      'MIND_DATASETS': 'MintTick Mind feature feed',
      'PAPER_READINESS': 'PAPER end-to-end readiness',
    };
    showModalBottomSheet<void>(
      context: context, showDragHandle: true, isScrollControlled: true,
      backgroundColor: MintTickPalette.darkCard,
      builder: (_) => SafeArea(child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 20),
        child: Column(mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('Percentage calculation',
              style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800)),
            const SizedBox(height: 5),
            const Text('A fresh VERIFIED milestone earns its full weight. '
              'Validated partial coverage can earn tenths of a percent only '
              'with a matching persisted-receipt proof and fixed target. '
              'GitHub PRs, worker notes and API smoke tests cannot earn '
              'backend acceptance.',
              style: TextStyle(fontSize: 11,
                color: MintTickPalette.mutedSilver, height: 1.4)),
            const SizedBox(height: 8),
            Text(score.remainingPercent == null
                ? 'Remaining: not verified'
                : 'Completed ${score.percent}% · Remaining ${score.remainingPercent}%',
              style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 13)),
            const SizedBox(height: 12),
            for (final gate in score.gates)
              Padding(padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(children: [
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(labels[gate.code] ?? gate.code,
                        style: const TextStyle(fontSize: 12,
                          fontWeight: FontWeight.w700)),
                      Text(gate.evidenceState, style: const TextStyle(
                        fontSize: 10, color: MintTickPalette.mutedSilver)),
                    ])),
                  Text(gate.earned == gate.weight
                      ? '${gate.weight}% done'
                      : gate.earned > 0
                        ? '${gate.earned}% / ${gate.weight}% earned'
                        : '${gate.weight}% left',
                    style: TextStyle(fontSize: 12,
                      color: gate.earned == gate.weight
                        ? MintTickPalette.mint : MintTickPalette.mutedSilver)),
                ])),
            const SizedBox(height: 8),
            Text('Formula: ${UpstoxReadinessProgress.formulaVersion}',
              style: const TextStyle(fontSize: 10,
                color: MintTickPalette.mutedSilver)),
            const SizedBox(height: 5),
            const Text('This is verified acceptance, not developer effort. '
              'PAPER trading and persisted ingestion remain separate runtime gates.',
              style: TextStyle(fontSize: 11,
                color: MintTickPalette.mutedSilver)),
          ]),
      )),
    );
  }

  List<UpstoxWorkerObservation> workerObservations(UpstoxAnalyticsProgress data) {
    final task = data.find('WORKER_REPORTS');
    if (task == null || task.state == 'BLOCKED' || task.state == 'DISABLED') {
      return UpstoxWorkerObservation.fromBackend(null);
    }
    return UpstoxWorkerObservation.fromBackend(
      task.details['workerReports'], staleSnapshot: task.stale);
  }

  Widget workerActivitySummary(UpstoxAnalyticsProgress data) {
    final reports = workerObservations(data);
    final fresh = reports.where((x) => x.state == 'FRESH').length;
    final stale = reports.where((x) => x.state == 'STALE').length;
    return panel(Row(children: [
      const Icon(Icons.work_history_outlined,
        size: 19, color: MintTickPalette.mint),
      const SizedBox(width: 9),
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Development activity', style: TextStyle(
            fontWeight: FontWeight.w800, fontSize: 12)),
          Text('$fresh/5 fresh worker reports · $stale stale',
            style: const TextStyle(fontSize: 10,
              color: MintTickPalette.mutedSilver)),
          const Text('GitHub evidence, not operational readiness',
            style: TextStyle(fontSize: 10,
              color: MintTickPalette.mutedSilver)),
        ])),
      TextButton(onPressed: () => setState(() => activeTab = 'Operations'),
        child: const Text('Work details', style: TextStyle(fontSize: 11))),
    ]), padding: const EdgeInsets.all(10));
  }

  List<Widget> workerDelivery(UpstoxAnalyticsProgress data) {
    final githubTask = data.find('GITHUB_DEVELOPMENT');
    final observedPrSample = githubTask?.state == 'VERIFIED' && githubTask?.stale == false;
    final prSample = UpstoxDeliveryWorkstreams.fromRecentPrs(
        observedPrSample ? githubTask?.details['trackedPrs'] : null);
    final reports = workerObservations(data);
    final fresh = reports.where((x) => x.state == 'FRESH').length;
    final missing = reports.where((x) => x.state == 'NO_REPORT').length;
    return [
      heading('Five data workstreams',
        'Reported GitHub delivery · separate from backend ingestion'),
      panel(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('Fresh reports $fresh/5 · No report $missing/5',
          style: const TextStyle(color: MintTickPalette.mutedSilver,
            fontSize: 11, fontWeight: FontWeight.w700)),
        const SizedBox(height: 6),
        for (var i = 0; i < reports.length; i++)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 7),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              SizedBox(width: 26, child: Text('W${i + 1}',
                style: const TextStyle(fontSize: 11,
                  color: MintTickPalette.mint, fontWeight: FontWeight.w800))),
              const SizedBox(width: 5),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(reports[i].label, maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
                  Text(reports[i].state == 'NO_REPORT'
                    ? 'No recent worker report · ${prSample[i].pr == null ? 'No PR in sample' : 'PR #${prSample[i].pr!.number} ${prSample[i].pr!.state}'}'
                    : '${reports[i].result ?? 'UNKNOWN'} · PR ${reports[i].pr?.toString() ?? '—'} · CI ${reports[i].ci ?? 'UNKNOWN'}',
                    maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 10,
                      color: MintTickPalette.mutedSilver)),
                  if (reports[i].state != 'NO_REPORT')
                    Text('Last reported ${when(reports[i].reportedAt)} · ${reports[i].state}',
                      maxLines: 1, overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 10,
                        color: MintTickPalette.mutedSilver)),
                  if (reports[i].lastRunChange != null)
                    Text('Last report change: ${reports[i].lastRunChange!.replaceAll('_', ' ').toLowerCase()}'
                      ' · ${reports[i].reportCount12h ?? '—'} reports / 12h',
                      maxLines: 2, overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 10,
                        color: MintTickPalette.mutedSilver)),
                  if (reports[i].blocker != null && reports[i].blocker != 'NONE')
                    Text('Blocked: ${reports[i].blocker}',
                      maxLines: 2, overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 10,
                        color: Color(0xFFE6BD73))),
                  if (reports[i].next != null)
                    Text('Next: ${reports[i].next}',
                      maxLines: 2, overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 10,
                        color: MintTickPalette.mutedSilver)),
                ])),
              const SizedBox(width: 4),
              Text(reports[i].state == 'NO_REPORT' ? 'UNKNOWN'
                    : reports[i].state,
                style: TextStyle(fontSize: 9,
                  color: reports[i].state == 'FRESH'
                    ? MintTickPalette.mint : const Color(0xFFE6BD73))),
              if (reports[i].url != null)
                IconButton(
                  tooltip: 'Copy worker PR link',
                  constraints: const BoxConstraints(minWidth: 31, minHeight: 31),
                  icon: const Icon(Icons.copy_outlined, size: 16),
                  onPressed: () async {
                    await Clipboard.setData(ClipboardData(text: reports[i].url!));
                    if (mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('PR link copied')));
                    }
                  },
                ),
            ]),
          ),
      ]), padding: const EdgeInsets.all(10)),
      const SizedBox(height: 8),
      note('Last-reported times come from GitHub issue #1012. '
        'Reported run changes compare GitHub issue notes, not '
        'independently validated production milestones. Repeated notes '
        'do not count as new delivery. A recent worker report or merged PR '
        'does not prove that a Spring Boot downloader, MintTick Mind feed, or PAPER execution is running. '
        'Without a deployed report collector, worker activity remains unknown.'),
    ];
  }

  List<Widget> history(UpstoxAnalyticsProgress data) {
    final ingestion = data.find('HISTORICAL_INGESTION');
    final target = ingestion?.details['targetUnits'];
    final receiptEvidence = ingestion != null && !ingestion.stale &&
        ingestion.state == 'PARTIAL' &&
        ingestion.details['progressProof'] ==
            'PERSISTED_CANDLE_CHECKPOINTS_V1' &&
        target is int && target > 0 &&
        ingestion.verified != null && ingestion.verified! <= target &&
        ingestion.checked != null && ingestion.checked! >= ingestion.verified!;
    return [
      heading('Historical coverage', 'Per-instrument and per-year receipts'),
      receiptEvidence
        ? note('Verified unique historical partitions: '
            '${ingestion.verified} / $target from persisted checkpoint evidence. '
            'This is partial coverage, not full-universe readiness.')
        : note('No verified timeframe, instrument or year coverage is available '
            'from the Upstox Analytics read-only sample. Counts are not backfill.'),
      taskTile(specs[1], data.find('INSTRUMENT_MAPPING')),
      taskTile(specs[2], ingestion),
      note('NIFTY 50, NIFTY 500 and wider NSE/BSE coverage require '
        'independent ingestion and source-rights validation.'),
    ];
  }

  List<Widget> quality(UpstoxAnalyticsProgress data) => [
    heading('Quality & rights', 'A working API token never grants model-training rights'),
    taskTile(specs[3], data.find('QUALITY_RIGHTS')),
    taskTile(specs[1], data.find('INSTRUMENT_MAPPING')),
    note('Expected checks: price and time order, exchange holidays, OHLCV gaps, '
      'cross-broker conflicts, corporate actions, licensing and point-in-time availability.'),
  ];

  List<Widget> mind(UpstoxAnalyticsProgress data) => [
    heading('Mind linkage', 'One model feature pipeline, not disconnected broker stores'),
    panel(Column(children: [
      pipelineStep('Upstox Analytics', Icons.insights_outlined),
      const Icon(Icons.arrow_downward, size: 16, color: MintTickPalette.mutedSilver),
      pipelineStep('Canonical market data', Icons.dataset_outlined),
      const Icon(Icons.arrow_downward, size: 16, color: MintTickPalette.mutedSilver),
      pipelineStep('MintTick Mind · Strategies', Icons.psychology_outlined),
      const Icon(Icons.arrow_downward, size: 16, color: MintTickPalette.mutedSilver),
      pipelineStep('PAPER strategy research', Icons.science_outlined),
    ])),
    const SizedBox(height: 10),
    taskTile(specs[5], data.find('MIND_DATASETS')),
    taskTile(specs[4], data.find('FUNDAMENTALS')),
    note('Existing strategy engines: RSI reversal, moving-average reclaim, volume breakout. '
      'Research signals cannot submit orders from this screen.'),
    const SizedBox(height: 10),
    FilledButton.icon(onPressed: () => Navigator.of(context).pushNamed('/mind'),
      icon: const Icon(Icons.psychology_outlined),
      label: const Text('Open MintTick Mind')),
  ];

  List<Widget> paper(UpstoxAnalyticsProgress data) => [
    heading('Paper readiness', 'Historical source data is not a tradeable live quote'),
    taskTile(specs[6], data.find('PAPER_READINESS')),
    taskTile(specs[5], data.find('MIND_DATASETS')),
    note('Required gates: source access, data rights, mapped instruments, '
      'complete candle history, validated strategy, fresh exchange quote, '
      'virtual wallet and risk approval. No automatic PAPER or LIVE order is enabled here.'),
    const SizedBox(height: 9),
    OutlinedButton(onPressed: () => setState(() => activeTab = 'Coverage'),
      child: const Text('Review Mind dependencies')),
  ];

  List<Widget> github(UpstoxAnalyticsProgress data) {
    final task = data.find('GITHUB_DEVELOPMENT');
    final details = task?.details ?? const <String, dynamic>{};
    final sample = details['trackedPrs'];
    final prs = sample is List ? sample : const <dynamic>[];
    return [
      heading('GitHub development', 'Read-only PR and CI status · recent sample'),
      taskTile(specs[7], task),
      const SizedBox(height: 10),
      Row(children: [
        metric('Open PRs', str(details['openPrs']), Icons.code_outlined),
        const SizedBox(width: 7),
        metric('Merged', str(details['mergedPrs']), Icons.merge_type_rounded),
        const SizedBox(width: 7),
        metric('CI pass', str(details['recentCiPassed']), Icons.check_circle_outline),
        const SizedBox(width: 7),
        metric('CI fail', str(details['recentCiFailed']), Icons.error_outline),
      ]),
      const SizedBox(height: 10),
      note('Recent API samples are not all-time PR or CI totals. GitHub '
        'unavailability does not stop the independent Analytics scheduler.'),
      const SizedBox(height: 10),
      for (final value in prs)
        if (value is Map && value['number'] is int &&
            value['title'] is String && value['url'] is String)
          Padding(padding: const EdgeInsets.only(bottom: 7),
            child: panel(Row(children: [
              const Icon(Icons.merge_outlined, color: MintTickPalette.mint, size: 18),
              const SizedBox(width: 10),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('#${value['number']} · ${value['title']}',
                    maxLines: 2, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
                  Text(str(value['state']), style: const TextStyle(
                    color: MintTickPalette.mutedSilver, fontSize: 11)),
                ])),
              IconButton(tooltip: 'Copy GitHub PR link',
                icon: const Icon(Icons.copy_outlined, size: 17),
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: value['url'].toString()));
                  if (mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('PR link copied')));
                  }
                }),
            ]))),
      if (prs.isEmpty)
        note('No verified PR sample available. Private GitHub repositories need '
          'a backend-only read token or GitHub App permissions.'),
    ];
  }

  String? relatedDevelopment(String code, UpstoxAnalyticsTask? task) {
    // This line deliberately never changes an operational readiness state.
    if (task == null || task.state == 'VERIFIED') return null;
    final worker = switch (code) {
      'INSTRUMENT_MAPPING' || 'HISTORICAL_INGESTION' => 1,
      'QUALITY_RIGHTS' || 'MIND_DATASETS' => 2,
      'FUNDAMENTALS' => 4,
      _ => null,
    };
    if (worker == null || snapshot == null) return null;
    final report = workerObservations(snapshot!)[worker - 1];
    if (report.state == 'NO_REPORT' || report.pr == null) return null;
    return 'Developer W$worker · PR #${report.pr} · ${report.ci ?? 'CI unknown'}';
  }

  Widget pipelineRow(
      (String, String, String, IconData) spec,
      UpstoxAnalyticsTask? task, {bool last = false}) {
    final subtitle = task == null
        ? 'No backend telemetry available'
        : task.stale ? 'Last verification is stale'
        : task.state == 'VERIFIED'
          ? 'Verified ${when(task.lastSuccessAt)}'
          : task.reasonCode == null ? spec.$3 : reasonDescription(task.reasonCode);
    return Material(color: Colors.transparent,
      child: InkWell(
        key: ValueKey('upstox-stage-${spec.$1}'),
        onTap: () => showDetails(spec, task),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          decoration: BoxDecoration(border: last ? null
            : const Border(bottom: BorderSide(color: MintTickPalette.darkBorder))),
          child: Row(children: [
            Icon(spec.$4, size: 18, color: MintTickPalette.mint),
            const SizedBox(width: 10),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(spec.$2, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 12,
                    fontWeight: FontWeight.w700)),
                Text(subtitle, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 10,
                    color: MintTickPalette.mutedSilver)),
                if (relatedDevelopment(spec.$1, task) case final work?)
                  Text(work, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 10,
                      color: MintTickPalette.mint)),
              ])),
            const SizedBox(width: 5),
            status(task),
          ]),
        ),
      ),
    );
  }

  Widget taskTile((String, String, String, IconData) spec, UpstoxAnalyticsTask? task) =>
    Padding(padding: const EdgeInsets.only(bottom: 7),
      child: Material(color: MintTickPalette.darkCard,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: const BorderSide(color: MintTickPalette.darkBorder)),
        child: InkWell(borderRadius: BorderRadius.circular(12),
          onTap: () => showDetails(spec, task),
          child: Padding(padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 11),
            child: Row(children: [
              Icon(spec.$4, size: 19, color: MintTickPalette.mint),
              const SizedBox(width: 9),
              Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(spec.$2, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700)),
                  const SizedBox(height: 2),
                  Text(task?.reasonCode == null ? spec.$3 : reasonDescription(task!.reasonCode),
                    maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 11, color: MintTickPalette.mutedSilver)),
                  if (relatedDevelopment(spec.$1, task) case final work?)
                    Text(work, maxLines: 1, overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 10,
                        color: MintTickPalette.mint)),
                  if (task?.checked != null)
                    Text('Verified ${str(task?.verified)} / ${str(task?.checked)} checks',
                      style: const TextStyle(fontSize: 10, color: MintTickPalette.mutedSilver)),
                ])),
              status(task),
              const Icon(Icons.chevron_right, size: 18, color: MintTickPalette.mutedSilver),
            ]))),
      ));

  void showDetails((String, String, String, IconData) spec, UpstoxAnalyticsTask? task) {
    showModalBottomSheet<void>(
      context: context, showDragHandle: true, isScrollControlled: true,
      backgroundColor: MintTickPalette.darkCard,
      builder: (_) => SafeArea(child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 5, 20, 28),
        child: Column(mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(spec.$2, style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w800)),
            const SizedBox(height: 5),
            Text(spec.$3, style: const TextStyle(color: MintTickPalette.mutedSilver)),
            const SizedBox(height: 12),
            status(task),
            const SizedBox(height: 11),
            detail('Reason', reasonDescription(task?.reasonCode)),
            if (task?.reasonCode != null)
              detail('Code', task!.reasonCode!),
            detail('Last attempt', when(task?.lastAttemptAt)),
            detail('Last success', when(task?.lastSuccessAt)),
            detail('Checks', str(task?.checked)),
            detail('Verified', str(task?.verified)),
            detail('Issues', str(task?.issues)),
            if (task != null)
              for (final entry in task.details.entries.take(8))
                if (entry.value is! List && entry.value is! Map)
                  detail(entry.key, str(entry.value)),
          ]),
      )),
    );
  }

  Widget detail(String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 3),
    child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      SizedBox(width: 110, child: Text(label, style: const TextStyle(
        color: MintTickPalette.mutedSilver, fontSize: 12))),
      Expanded(child: Text(value, style: const TextStyle(fontSize: 12))),
    ]));

  Widget status(UpstoxAnalyticsTask? task, {String? labelOverride}) {
    final state = task == null ? 'UNKNOWN' : task.stale ? 'STALE' : task.state;
    final color = switch (state) {
      'VERIFIED' => MintTickPalette.mint,
      'RUNNING' => const Color(0xFF53D69D),
      'PARTIAL' || 'BLOCKED' || 'STALE' => const Color(0xFFE6BD73),
      'FAILED' => const Color(0xFFF48C93),
      _ => MintTickPalette.mutedSilver,
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 5),
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(15),
        border: Border.all(color: color.withValues(alpha: .45)),
        color: color.withValues(alpha: .08)),
      child: Text(labelOverride ?? state.replaceAll('_', ' '), style: TextStyle(
        fontSize: 9.5, color: color, fontWeight: FontWeight.w800)),
    );
  }

  Widget metric(String label, String value, IconData icon) => Expanded(
    child: panel(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Icon(icon, size: 14, color: MintTickPalette.mint),
      const SizedBox(height: 5),
      Text(value, maxLines: 1, overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800)),
      Text(label, maxLines: 1, overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 10, color: MintTickPalette.mutedSilver)),
    ]), padding: const EdgeInsets.all(8)));

  Widget pipelineStep(String text, IconData icon) => Row(children: [
    Icon(icon, color: MintTickPalette.mint, size: 18),
    const SizedBox(width: 9),
    Text(text, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
  ]);

  Widget heading(String title, String subtitle) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(title, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 17)),
      const SizedBox(height: 3),
      Text(subtitle, style: const TextStyle(
        fontSize: 11, color: MintTickPalette.mutedSilver)),
    ]));

  Widget panel(Widget child, {EdgeInsets padding = const EdgeInsets.all(12)}) =>
      Container(padding: padding,
        decoration: BoxDecoration(color: MintTickPalette.darkCard,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: MintTickPalette.darkBorder)),
        child: child);

  Widget note(String content) => Container(width: double.infinity,
    margin: const EdgeInsets.only(bottom: 8),
    padding: const EdgeInsets.all(11),
    decoration: BoxDecoration(color: const Color(0xFF111512),
      borderRadius: BorderRadius.circular(11),
      border: Border.all(color: MintTickPalette.darkBorder)),
    child: Text(content, style: const TextStyle(
      fontSize: 11, color: MintTickPalette.mutedSilver, height: 1.45)));

  static String reasonDescription(String? code) => switch (code) {
    'GITHUB_DNS_UNAVAILABLE' => 'Backend DNS cannot resolve GitHub',
    'GITHUB_CONNECT_FAILURE' => 'Backend cannot connect to GitHub',
    'GITHUB_NETWORK_UNAVAILABLE' => 'Backend network route unavailable',
    'GITHUB_TIMEOUT' => 'GitHub request timed out',
    'GITHUB_TLS_FAILURE' => 'HTTPS TLS verification failed',
    'GITHUB_RATE_LIMITED' => 'GitHub API request quota reached',
    'GITHUB_AUTH_REJECTED' => 'GitHub rejected the configured credential',
    'GITHUB_ACCESS_DENIED' => 'GitHub access forbidden',
    'GITHUB_ENDPOINT_NOT_FOUND' => 'GitHub API route not found',
    'GITHUB_INVALID_DATA' => 'GitHub response failed validation',
    'GITHUB_RESPONSE_TOO_LARGE' => 'GitHub result exceeded its safe response-size limit',
    'GITHUB_SERVER_UNAVAILABLE' => 'GitHub service error',
    'GITHUB_HTTP_FAILURE' => 'GitHub returned an unexpected HTTP status',
    'GITHUB_READ_UNAVAILABLE' || 'GITHUB_API_UNAVAILABLE' =>
      'GitHub request failed; detailed diagnostics unavailable',
    'OPERATOR_OPT_IN_REQUIRED' || 'SOURCE_PROBE_DISABLED' =>
      'Read-only progress probe is disabled on the backend',
    'ANALYTICS_SOURCE_DISABLED' =>
      'Upstox Analytics source is disabled in the backend environment',
    'NO_VERIFIED_COVERAGE' => 'No verified instrument coverage yet',
    'NO_VERIFIED_RIGHTS_RECEIPT' => 'Data usage rights not verified',
    'NO_VERIFIED_MIND_FEED' => 'No verified MintTick Mind feature feed yet',
    'NO_EXECUTION_EVIDENCE' => 'Paper readiness not yet verified',
    null => 'No failure reported',
    _ => code,
  };

  static String str(Object? value) => value == null ? '—' : value.toString();
  static String when(DateTime? at) {
    if (at == null) return 'Not available';
    final date = at.toLocal();
    final day = date.day.toString().padLeft(2, '0');
    final month = date.month.toString().padLeft(2, '0');
    final hour = date.hour.toString().padLeft(2, '0');
    final minute = date.minute.toString().padLeft(2, '0');
    return '$day/$month $hour:$minute';
  }
}
