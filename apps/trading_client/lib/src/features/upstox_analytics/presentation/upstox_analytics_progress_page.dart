import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../brand/minttick_brand.dart';
import '../../../data/authenticated_api_client.dart';
import '../data/upstox_analytics_progress_repository.dart';

/// Backend-evidence-only owner monitor. Never places PAPER/LIVE orders.
class UpstoxAnalyticsProgressPage extends StatefulWidget {
  const UpstoxAnalyticsProgressPage({required this.repository, super.key});
  final UpstoxAnalyticsProgressRepository repository;

  @override
  State<UpstoxAnalyticsProgressPage> createState() => _UpstoxAnalyticsProgressPageState();
}

class _UpstoxAnalyticsProgressPageState extends State<UpstoxAnalyticsProgressPage> {
  static const tabs = ['Overview', 'Tasks', 'History', 'Quality', 'Mind', 'GitHub', 'Paper'];
  static const intervals = ['1m', '5m', '15m', '1h', '1D', '1W', '1M'];
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
  String activeTab = 'Overview', interval = '1D';
  String? error;
  bool loading = true;
  int generation = 0;

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
      setState(() { snapshot = data; error = null; });
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
            status(data?.find('SOURCE_VERIFICATION')),
          ])),
        SizedBox(height: 45,
          child: ListView(scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            children: [for (final name in tabs)
              Padding(padding: const EdgeInsets.only(right: 6),
                child: ChoiceChip(label: Text(name),
                  selected: name == activeTab, showCheckmark: false,
                  selectedColor: const Color(0xFF18231A),
                  backgroundColor: MintTickPalette.darkCard,
                  side: BorderSide(color: name == activeTab
                    ? MintTickPalette.mint : MintTickPalette.darkBorder),
                  labelStyle: TextStyle(fontSize: 12,
                    color: name == activeTab ? MintTickPalette.mint
                      : MintTickPalette.mutedSilver),
                  onSelected: (_) => setState(() => activeTab = name)))],
          )),
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
                if (activeTab == 'Tasks') ...taskList(data, specs),
                if (activeTab == 'History') ...history(data),
                if (activeTab == 'Quality') ...quality(data),
                if (activeTab == 'Mind') ...mind(data),
                if (activeTab == 'GitHub') ...github(data),
                if (activeTab == 'Paper') ...paper(data),
              ],
            ),
          )),
      ])),
    );
  }

  List<Widget> overview(UpstoxAnalyticsProgress data) {
    final verified = data.tasks.where((t) => t.state == 'VERIFIED').length;
    final blocked = data.tasks.where((t) => t.state == 'BLOCKED' || t.state == 'FAILED').length;
    final active = data.tasks.where((t) => t.state == 'RUNNING').length;
    return [
      heading('Execution overview', 'Persisted backend state · no placeholder percentages'),
      Row(children: [
        metric('Verified', verified.toString(), Icons.check_circle_outline),
        const SizedBox(width: 7),
        metric('Running', active.toString(), Icons.sync_outlined),
        const SizedBox(width: 7),
        metric('Blocked', blocked.toString(), Icons.report_problem_outlined),
        const SizedBox(width: 7),
        metric('Tasks', data.tasks.length.toString(), Icons.view_list_outlined),
      ]),
      const SizedBox(height: 14),
      heading('Analytics pipeline', 'Every stage feeds the canonical MintTick Mind architecture'),
      ...taskList(data, specs.take(7)),
      const SizedBox(height: 8),
      note('Progress snapshot: ${when(data.generatedAt)}. A connected trading account '
        'does not establish Analytics source readiness.'),
      const SizedBox(height: 10),
      Row(children: [
        Expanded(child: OutlinedButton(
          onPressed: () => setState(() => activeTab = 'Tasks'),
          child: const Text('All tasks'))),
        const SizedBox(width: 8),
        Expanded(child: FilledButton(
          onPressed: () => setState(() => activeTab = 'Mind'),
          child: const Text('Mind linkage'))),
      ]),
    ];
  }

  List<Widget> taskList(UpstoxAnalyticsProgress data,
      Iterable<(String, String, String, IconData)> source) =>
    [for (final spec in source) taskTile(spec, data.find(spec.$1))];

  List<Widget> history(UpstoxAnalyticsProgress data) => [
    heading('History & coverage', 'Verified timeframes, completeness and gap recovery'),
    SingleChildScrollView(scrollDirection: Axis.horizontal,
      child: Row(children: [for (final v in intervals)
        Padding(padding: const EdgeInsets.only(right: 5),
          child: ChoiceChip(label: Text(v), selected: v == interval,
            showCheckmark: false,
            onSelected: (_) => setState(() => interval = v)))])),
    const SizedBox(height: 10),
    note('$interval coverage has no verified per-instrument/year receipt yet. '
      'Unavailable is not equivalent to zero records.'),
    const SizedBox(height: 10),
    taskTile(specs[1], data.find('INSTRUMENT_MAPPING')),
    taskTile(specs[2], data.find('HISTORICAL_INGESTION')),
    taskTile(specs[3], data.find('QUALITY_RIGHTS')),
    note('NIFTY 50, NIFTY 500 and the complete cash-equity universe require '
      'independent coverage verification before percentages appear.'),
  ];

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
    OutlinedButton(onPressed: () => setState(() => activeTab = 'Mind'),
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
                  Text(spec.$3, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 11, color: MintTickPalette.mutedSilver)),
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

  Widget status(UpstoxAnalyticsTask? task) {
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
      child: Text(state.replaceAll('_', ' '), style: TextStyle(
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
    'GITHUB_SERVER_UNAVAILABLE' => 'GitHub service error',
    'GITHUB_HTTP_FAILURE' => 'GitHub returned an unexpected HTTP status',
    'GITHUB_READ_UNAVAILABLE' || 'GITHUB_API_UNAVAILABLE' =>
      'GitHub request failed; detailed diagnostics unavailable',
    'OPERATOR_OPT_IN_REQUIRED' => 'Read-only source verification is switched off',
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
