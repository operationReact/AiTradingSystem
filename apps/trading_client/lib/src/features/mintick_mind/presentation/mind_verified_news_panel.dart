import 'dart:async';

import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../data/mind_verified_news_repository.dart';

/// Source-verified, READ-ONLY evidence for an exact historic instrument and
/// decision cutoff. This widget is not an automatic order or scoring surface.
class MindVerifiedNewsPanel extends StatefulWidget {
  const MindVerifiedNewsPanel({
    required this.instrumentId,
    required this.asOf,
    required this.repository,
    super.key,
  });

  final int instrumentId;
  final DateTime asOf;
  final MindVerifiedNewsRepository repository;

  @override
  State<MindVerifiedNewsPanel> createState() => _MindVerifiedNewsPanelState();
}

class _MindVerifiedNewsPanelState extends State<MindVerifiedNewsPanel> {
  late Future<MindVerifiedNewsEvidence?> _future;

  Future<MindVerifiedNewsEvidence?> _load() async {
    try {
      return await widget.repository.verifiedAt(
        instrumentId: widget.instrumentId,
        asOf: widget.asOf,
      );
    } catch (_) {
      return null; // No stale fallback on revoked permission or missing evidence.
    }
  }

  @override
  void initState() {
    super.initState();
    _future = _load();
  }

  @override
  void didUpdateWidget(covariant MindVerifiedNewsPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.instrumentId != widget.instrumentId ||
        oldWidget.asOf != widget.asOf ||
        oldWidget.repository != widget.repository) {
      _future = _load();
    }
  }

  void _refresh() {
    setState(() { _future = _load(); });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    return FutureBuilder<MindVerifiedNewsEvidence?>(
      future: _future,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Padding(
            padding: EdgeInsets.all(20),
            child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
          );
        }
        final result = snapshot.data;
        if (result == null) {
          return Column(
            key: const ValueKey('verified-news-unavailable'),
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Verified news evidence unavailable or not currently permitted.',
                style: theme.textTheme.bodySmall),
              TextButton(onPressed: _refresh, child: const Text('Retry')),
            ],
          );
        }
        return Column(
          key: const ValueKey('verified-news-evidence'),
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Expanded(
                child: Text('Independently verified issuer events',
                  style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
              ),
              IconButton(
                key: const ValueKey('verified-news-refresh'),
                icon: const Icon(Icons.refresh_rounded, size: 18),
                tooltip: 'Reload verified issuer evidence',
                onPressed: _refresh,
              ),
            ]),
            Text('Research only · not included in trading decisions',
              style: theme.textTheme.labelSmall?.copyWith(color: muted)),
            const SizedBox(height: 5),
            Text('As of ${_ist(result.researchCutoff)} · instrument ${result.instrumentId}',
              style: theme.textTheme.labelSmall?.copyWith(color: muted)),
            const SizedBox(height: 4),
            Text(_status(result.verificationStatus),
              style: theme.textTheme.bodySmall?.copyWith(color: muted)),
            const SizedBox(height: 6),
            if (result.evidence.isEmpty)
              Text(result.hasVerifiedListing
                  ? 'Issuer identity verified; no authorized event evidence available.'
                  : 'Issuer identity is not independently verified at this cutoff.',
                style: theme.textTheme.bodySmall),
            for (final receipt in result.evidence) ...[
              Container(
                key: ValueKey('verified-news-receipt-${receipt.receiptId}'),
                margin: const EdgeInsets.only(bottom: 7),
                padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 10),
                decoration: BoxDecoration(
                  border: Border.all(color: theme.colorScheme.outlineVariant),
                  borderRadius: BorderRadius.circular(9),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(receipt.headline, maxLines: 3, overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 3),
                    Text('${receipt.publisher} · ${receipt.eventCategory}'
                        '${receipt.retracted ? ' · RETRACTED' : ''}',
                      style: theme.textTheme.labelSmall?.copyWith(color: muted)),
                    Text('First seen ${_ist(receipt.firstObservedAt)} · '
                        'Mapping known ${_ist(receipt.mappingFirstKnownAt)}',
                      style: theme.textTheme.labelSmall?.copyWith(color: muted)),
                    TextButton.icon(
                      onPressed: () => unawaited(_openSource(receipt.originalUrl)),
                      icon: const Icon(Icons.open_in_new, size: 16),
                      label: const Text('Original publisher'),
                    ),
                  ],
                ),
              ),
            ],
          ],
        );
      },
    );
  }

  static String _status(String status) => switch (status) {
    'VERIFIED_LISTING_EVENT_EVIDENCE' => 'Source-backed issuer evidence found',
    'VERIFIED_LISTING_NO_EVENT_EVIDENCE' => 'Verified issuer, no permitted news coverage',
    'NOT_CASH_EQUITY' => 'Cash-equity verification unavailable for this contract',
    'INSTRUMENT_NOT_IN_ASOF_CATALOGUE' => 'No matching historical instrument record',
    'UNVERIFIED_CATALOGUE_SOURCE' => 'Source catalogue is not independently verified',
    _ => 'Historical listing identity is unverified',
  };

  Future<void> _openSource(Uri uri) async {
    if (uri.scheme != 'https' || uri.host.isEmpty || uri.userInfo.isNotEmpty) return;
    try {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      // A browser error must never mutate evidence or trigger trading actions.
    }
  }

  static String _ist(DateTime input) {
    final date = input.toUtc().add(const Duration(hours: 5, minutes: 30));
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(date.day)}/${two(date.month)}/${date.year} '
        '${two(date.hour)}:${two(date.minute)} IST';
  }
}
