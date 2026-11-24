import 'dart:async';

import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../data/market_event_detail_repository.dart';

/// CUSTOMER source-evidence drilldown. No synthetic fallback is shown if the
/// backend withholds an event for source-rights or as-of reasons.
class MarketEventEvidencePage extends StatefulWidget {
  const MarketEventEvidencePage({
    required this.eventId,
    required this.repository,
    super.key,
  });

  final String eventId;
  final MarketEventDetailRepository repository;

  @override
  State<MarketEventEvidencePage> createState() => _MarketEventEvidencePageState();
}

class _MarketEventEvidencePageState extends State<MarketEventEvidencePage> {
  late Future<MarketEventDetail> _detail;

  @override
  void initState() {
    super.initState();
    _detail = widget.repository.detail(widget.eventId);
  }

  void _reload() {
    // Future creation must happen outside setState; the callback is synchronous.
    final next = widget.repository.detail(widget.eventId);
    setState(() { _detail = next; });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Event evidence'),
        actions: [
          IconButton(
            key: const ValueKey('event-evidence-refresh'),
            tooltip: 'Reload evidence',
            icon: const Icon(Icons.refresh_rounded),
            onPressed: _reload,
          ),
        ],
      ),
      body: FutureBuilder<MarketEventDetail>(
        future: _detail,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError || !snapshot.hasData) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(mainAxisSize: MainAxisSize.min, children: [
                  Icon(Icons.cloud_off_outlined, color: muted, size: 30),
                  const SizedBox(height: 10),
                  const Text(
                    'Event evidence is unavailable or no longer permitted for display.',
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton(onPressed: _reload, child: const Text('Retry')),
                ]),
              ),
            );
          }
          final event = snapshot.data!;
          return ListView(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
            children: [
              Row(children: [
                Expanded(
                  child: Text('Version ${event.currentVersion} · ${event.eventStatus}',
                    style: theme.textTheme.labelMedium?.copyWith(color: muted)),
                ),
                if (event.eventStatus == 'RETRACTED')
                  Icon(Icons.report_outlined, color: theme.colorScheme.error, size: 19),
              ]),
              const SizedBox(height: 7),
              Text('Category: ${event.eventClass}', style: theme.textTheme.titleMedium),
              const SizedBox(height: 6),
              Text('First seen: ${_ist(event.firstSeenAt)}',
                style: theme.textTheme.bodySmall?.copyWith(color: muted)),
              Text('Current revision known: ${_ist(event.currentKnownAt)}',
                style: theme.textTheme.bodySmall?.copyWith(color: muted)),
              const SizedBox(height: 6),
              Text(event.instrumentResolutionStatus == 'VERIFIED'
                      ? 'Verified instrument evidence'
                      : 'Affected instruments are not yet verified',
                style: theme.textTheme.bodySmall?.copyWith(color: muted)),
              const SizedBox(height: 16),
              Text('Publication and correction history',
                style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              for (final revision in event.versions) ...[
                _revision(revision, theme),
                const SizedBox(height: 8),
              ],
              if (event.versionsTruncated)
                Text('Older revisions not included in this response.',
                  style: theme.textTheme.bodySmall?.copyWith(color: muted)),
            ],
          );
        },
      ),
    );
  }

  Widget _revision(MarketEventRevision revision, ThemeData theme) {
    final muted = theme.colorScheme.onSurfaceVariant;
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(9),
        side: BorderSide(color: theme.colorScheme.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(
              child: Text('v${revision.version} · ${revision.category}',
                style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w700)),
            ),
            if (revision.retraction)
              Text('RETRACTED',
                style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.error)),
          ]),
          if (revision.correctionOfVersion != null)
            Text('Correction of v${revision.correctionOfVersion}',
              style: theme.textTheme.bodySmall?.copyWith(color: muted)),
          Text('Known: ${_ist(revision.effectiveKnownAt)}',
            style: theme.textTheme.bodySmall?.copyWith(color: muted)),
          const SizedBox(height: 9),
          for (final receipt in revision.evidence) ...[
            Text(receipt.title, maxLines: 3, overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w500)),
            const SizedBox(height: 3),
            Text('${receipt.publisher} · ${receipt.evidenceKind}',
              style: theme.textTheme.labelSmall?.copyWith(color: muted)),
            Text('Published: ${receipt.publishedAt == null ? 'Unknown' : _ist(receipt.publishedAt!)}',
              style: theme.textTheme.labelSmall?.copyWith(color: muted)),
            Text('First observed: ${_ist(receipt.firstObservedAt)}',
              style: theme.textTheme.labelSmall?.copyWith(color: muted)),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                onPressed: () => unawaited(_openPublisher(receipt.sourceUrl)),
                icon: const Icon(Icons.open_in_new, size: 16),
                label: const Text('Original publisher'),
              ),
            ),
          ],
          if (revision.evidenceTruncated)
            Text('Additional source receipts not included in this response.',
              style: theme.textTheme.bodySmall?.copyWith(color: muted)),
        ]),
      ),
    );
  }

  Future<void> _openPublisher(Uri uri) async {
    if (!{'https', 'http'}.contains(uri.scheme) || uri.host.isEmpty) return;
    try {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      // A link failure never changes displayed evidence.
    }
  }

  static String _ist(DateTime value) {
    final t = value.toUtc().add(const Duration(hours: 5, minutes: 30));
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(t.day)}/${two(t.month)}/${t.year} '
        '${two(t.hour)}:${two(t.minute)} IST';
  }
}
