import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../brand/minttick_brand.dart';
import '../data/mf_alerts_repository.dart';

/// Customer research watchlist and dated review alerts: never places a purchase.
class MfAlertsPage extends StatefulWidget {
  const MfAlertsPage({required this.repository, super.key});
  final MfAlertsRepository repository;
  @override
  State<MfAlertsPage> createState() => _MfAlertsPageState();
}

class _MfAlertsPageState extends State<MfAlertsPage> {
  MfResearchBoard? board;
  bool loading = true;
  String? error;
  bool showAlerts = true;
  int generation = 0;

  @override
  void initState() { super.initState(); refresh(); }

  Future<void> refresh() async {
    final request = ++generation;
    setState(() { loading = true; error = null; });
    try {
      final next = await widget.repository.load();
      if (!mounted || generation != request) return;
      setState(() => board = next);
    } catch (_) {
      if (!mounted || generation != request) return;
      setState(() {
        board = null;
        error = 'No verified NAV research is available. Retry later.';
      });
    } finally {
      if (mounted && generation == request) setState(() => loading = false);
    }
  }

  @override
  void dispose() { generation++; super.dispose(); }

  @override
  Widget build(BuildContext context) {
    final data = board;
    return Scaffold(
      key: const ValueKey('minttick-mutual-fund-alerts-page'),
      backgroundColor: MintTickPalette.nearBlack,
      appBar: AppBar(title: const Text('Mutual Funds'),
        actions: [IconButton(tooltip: 'Refresh mutual-fund alerts',
          onPressed: loading ? null : refresh,
          icon: const Icon(Icons.refresh_rounded))]),
      body: SafeArea(child: RefreshIndicator(
        onRefresh: refresh,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(14, 16, 14, 36),
          children: [
            _panel(const Column(crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(children: [
                  Icon(Icons.insights_outlined, color: MintTickPalette.mint),
                  SizedBox(width: 9),
                  Expanded(child: Text('Opportunity monitor',
                    style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800))),
                ]),
                SizedBox(height: 7),
                Text('9 funds · Read-only research · Manual investing',
                  style: TextStyle(fontSize: 12, color: MintTickPalette.mutedSilver)),
                SizedBox(height: 7),
                Text('Pullback signals are review prompts, not predictions. '
                  'No SIP, automatic order, or payment is initiated.',
                  style: TextStyle(fontSize: 12, height: 1.45)),
              ])),
            const SizedBox(height: 14),
            if (loading && data == null)
              const Padding(padding: EdgeInsets.all(48),
                child: Center(child: CircularProgressIndicator()))
            else if (error != null)
              _panel(Column(children: [
                const Icon(Icons.cloud_off_outlined, color: Color(0xFFDAB274)),
                const SizedBox(height: 9),
                Text(error!, textAlign: TextAlign.center),
                TextButton(onPressed: refresh, child: const Text('Retry')),
              ]))
            else if (data != null) ...[
              _feed(data),
              const SizedBox(height: 14),
              SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(value: true, icon: Icon(Icons.notifications_none_rounded),
                    label: Text('Alerts')),
                  ButtonSegment(value: false, icon: Icon(Icons.bookmarks_outlined),
                    label: Text('Watchlist')),
                ],
                selected: {showAlerts},
                onSelectionChanged: (value) =>
                  setState(() => showAlerts = value.first),
              ),
              const SizedBox(height: 14),
              if (showAlerts) ...[
                if (data.alerts.isEmpty)
                  _panel(const Column(crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('No verified opportunities yet',
                        style: TextStyle(fontWeight: FontWeight.w800,fontSize: 15)),
                      SizedBox(height: 7),
                      Text('Signals require 22 distinct, current AMFI NAV observations '
                        'and a newly crossed pullback threshold. '
                        'Missing history never produces synthetic buy alerts.',
                        style: TextStyle(color: MintTickPalette.mutedSilver,
                          fontSize: 12,height: 1.5)),
                    ]))
                else
                  for (final alert in data.alerts) _alert(alert),
              ] else
                for (final fund in data.watchlist) _fund(fund),
              const SizedBox(height: 16),
              Text(data.disclaimer, style: const TextStyle(fontSize: 11,
                height: 1.45,color: MintTickPalette.mutedSilver)),
            ],
          ],
        ),
      )),
    );
  }

  Widget _feed(MfResearchBoard data) {
    final feed = data.feed;
    final status = feed['status']?.toString() ?? 'UNKNOWN';
    return _panel(Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Icon(status == 'VERIFIED' ? Icons.verified_outlined : Icons.info_outline,
          size: 19, color: status == 'VERIFIED' ? MintTickPalette.mint :
            const Color(0xFFDAB274)),
        const SizedBox(width: 9),
        Expanded(child: Text('AMFI source: ${status.toLowerCase()}',
          style: const TextStyle(fontWeight: FontWeight.w800))),
      ]),
      const SizedBox(height: 5),
      Text('Latest NAV date: ${feed['latestNavDate'] ?? 'Unavailable'}',
        style: const TextStyle(color: MintTickPalette.mutedSilver,fontSize: 12)),
      if (feed['failureCode'] != null)
        Text('Source problem: ${feed['failureCode']}',
          style: const TextStyle(color: Color(0xFFDAB274),fontSize: 11)),
    ]));
  }

  Widget _fund(Map<String,dynamic> fund) {
    final nav = (fund['nav'] as num?)?.toDouble();
    final state = fund['state']?.toString() ?? 'UNKNOWN';
    return Padding(padding: const EdgeInsets.only(bottom: 9),
      child: _panel(Column(crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Expanded(child: Text('${fund['name']}',
              style: const TextStyle(fontWeight: FontWeight.w800,fontSize: 14))),
            Text(state.replaceAll('_', ' ').toLowerCase(),
              style: const TextStyle(fontSize: 10.5,color: MintTickPalette.mutedSilver)),
          ]),
          const SizedBox(height: 6),
          Text('${fund['category']} · ${fund['risk']} risk',
            style: const TextStyle(fontSize: 11,color: MintTickPalette.mutedSilver)),
          const SizedBox(height: 10),
          Row(children: [
            Expanded(child: Text(nav == null ? 'NAV unavailable' :
              'NAV ₹${nav.toStringAsFixed(3)}',
              style: const TextStyle(fontWeight: FontWeight.w800,fontSize: 15))),
            Text('${fund['navDate'] ?? '—'}',
              style: const TextStyle(color: MintTickPalette.mutedSilver,fontSize: 11)),
          ]),
          const SizedBox(height: 6),
          Row(children: [
            Expanded(child: Text('${fund['observations']}/22+ NAV observations',
              style: const TextStyle(color: MintTickPalette.mutedSilver,fontSize: 11))),
            TextButton.icon(
              onPressed: () => Clipboard.setData(ClipboardData(text: '${fund['isin']}')),
              icon: const Icon(Icons.copy_rounded,size: 14),
              label: const Text('ISIN'),
            ),
          ]),
        ])));
  }

  Widget _alert(Map<String,dynamic> alert) {
    final pullback = (alert['drawdownPercent'] as num?)?.toDouble();
    return Padding(padding: const EdgeInsets.only(bottom: 9),
      child: _panel(Column(crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            const Icon(Icons.notifications_active_outlined, size: 18,
              color: MintTickPalette.mint),
            const SizedBox(width: 7),
            Expanded(child: Text(
              alert['kind'] == 'DEEP_PULLBACK_REVIEW'
                ? 'Deeper pullback · Review' : 'Pullback · Review',
              style: const TextStyle(color: MintTickPalette.mint,
                fontSize: 12,fontWeight: FontWeight.w800))),
            Text('${alert['navDate']}',
              style: const TextStyle(fontSize: 11,color: MintTickPalette.mutedSilver)),
          ]),
          const SizedBox(height: 8),
          Text('${alert['name']}',
            style: const TextStyle(fontWeight: FontWeight.w800,fontSize: 15)),
          const SizedBox(height: 9),
          Text(pullback == null ? 'Drawdown unavailable' :
            '${pullback.toStringAsFixed(2)}% below recent peak',
            style: const TextStyle(fontWeight: FontWeight.w800,fontSize: 16)),
          const SizedBox(height: 5),
          Text('${alert['explanation']}',
            style: const TextStyle(color: MintTickPalette.mutedSilver,
              height: 1.4,fontSize: 12)),
          const SizedBox(height: 10),
          Text('Illustrative amount: ₹${alert['illustrativeAmountInr']}',
            style: const TextStyle(fontWeight: FontWeight.w800,fontSize: 12)),
          const SizedBox(height: 4),
          const Text('Not tailored to your budget, holdings, or risk capacity. '
            'Decide manually after reviewing suitability.',
            style: TextStyle(fontSize: 11,height: 1.4,
              color: MintTickPalette.mutedSilver)),
        ])));
  }

  Widget _panel(Widget child) => Container(
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(color: MintTickPalette.darkCard,
      borderRadius: BorderRadius.circular(15),
      border: Border.all(color: MintTickPalette.darkBorder)),
    child: child,
  );
}
