import 'dart:async';

import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../data/market_event_repository.dart';

/// Compact, read-only canonical event timeline. Market news cannot authorize an
/// order, claim a verified issuer, or infer a call/put signal in this screen.
class MarketEventTimelinePage extends StatefulWidget {
  const MarketEventTimelinePage({required this.repository, super.key});

  final MarketEventRepository repository;

  @override
  State<MarketEventTimelinePage> createState() => _MarketEventTimelinePageState();
}

class _MarketEventTimelinePageState extends State<MarketEventTimelinePage> {
  final ScrollController _scroll = ScrollController();
  final TextEditingController _searchController = TextEditingController();
  final List<MarketEventCard> _items = [];
  String? _category;
  String _query = '';
  int _windowDays = 0;
  int _generation = 0;
  int _page = 0;
  bool _loading = false;
  bool _loadingMore = false;
  bool _hasNext = false;
  String? _error;
  String? _paginationError;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_handleScroll);
    unawaited(_reload());
  }

  @override
  void dispose() {
    _generation++;
    _scroll.removeListener(_handleScroll);
    _scroll.dispose();
    _searchController.dispose();
    super.dispose();
  }

  void _handleScroll() {
    if (!_scroll.hasClients || _loading || _loadingMore || !_hasNext) return;
    if (_scroll.position.pixels >= _scroll.position.maxScrollExtent - 230) {
      unawaited(_loadNext());
    }
  }

  Future<void> _reload({bool resetPrevious = false}) async {
    final generation = ++_generation;
    if (resetPrevious && _scroll.hasClients) _scroll.jumpTo(0);
    setState(() {
      _loading = true;
      _error = null;
      _paginationError = null;
      if (resetPrevious) {
        // Prior results must never appear under a different selected filter.
        _items.clear();
        _page = 0;
        _hasNext = false;
      }
    });
    try {
      final result = await widget.repository.events(
        page: 0, category: _category, query: _query,
        windowDays: _windowDays,
      );
      if (!mounted || generation != _generation) return;
      setState(() {
        _items
          ..clear()
          ..addAll(result.items);
        _page = 1;
        _hasNext = result.hasNext;
        _loading = false;
      });
    } catch (_) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _error = _items.isEmpty
            ? 'Verified event data is not available from the connected backend.'
            : 'Refresh failed. Showing the last event records loaded in this session.';
        _loading = false;
      });
    }
  }

  Future<void> _loadNext() async {
    if (_loading || _loadingMore || !_hasNext) return;
    final generation = _generation;
    final requested = _page;
    setState(() {
      _loadingMore = true;
      _paginationError = null;
    });
    try {
      final result = await widget.repository.events(
        page: requested, category: _category, query: _query,
        windowDays: _windowDays,
      );
      if (!mounted || generation != _generation) return;
      final seen = _items.map((item) => item.eventId).toSet();
      setState(() {
        _items.addAll(result.items.where((item) => seen.add(item.eventId)));
        _page = requested + 1;
        _hasNext = result.hasNext;
        _loadingMore = false;
      });
    } catch (_) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _paginationError = 'More events could not be loaded.';
        _loadingMore = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Market events'),
        actions: [
          IconButton(
            key: const ValueKey('market-events-refresh'),
            tooltip: 'Refresh events',
            onPressed: _loading ? null : () => unawaited(_reload()),
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: Column(
        children: [
          _filterBar(),
          Expanded(
            child: _loading && _items.isEmpty
                ? const Center(child: CircularProgressIndicator())
                : _items.isEmpty
                    ? _emptyState()
                    : RefreshIndicator(
                  onRefresh: _reload,
                  child: ListView.builder(
                    controller: _scroll,
                    physics: const AlwaysScrollableScrollPhysics(),
                    itemCount: _items.length + 2,
                    itemBuilder: (context, index) {
                      if (index == 0) {
                        return Padding(
                          padding: const EdgeInsets.fromLTRB(16, 10, 16, 8),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('Evidence timeline',
                                style: theme.textTheme.labelMedium?.copyWith(
                                  color: colors.onSurfaceVariant)),
                              const SizedBox(height: 3),
                              Text('Source-backed events · company mapping is not assumed',
                                style: theme.textTheme.bodySmall?.copyWith(
                                  color: colors.onSurfaceVariant)),
                              if (_error != null) ...[
                                const SizedBox(height: 8),
                                Text(_error!, style: theme.textTheme.bodySmall),
                              ],
                            ],
                          ),
                        );
                      }
                      if (index == _items.length + 1) {
                        if (_loadingMore) {
                          return const Padding(padding: EdgeInsets.all(16),
                            child: Center(child: CircularProgressIndicator()));
                        }
                        if (_paginationError != null) {
                          return TextButton(
                            onPressed: () => unawaited(_loadNext()),
                            child: Text('Retry — ${_paginationError!}'),
                          );
                        }
                        return const SizedBox(height: 16);
                      }
                      final item = _items[index - 1];
                      return Column(
                        children: [
                          ListTile(
                            key: ValueKey('market-event-${item.eventId}'),
                            dense: true,
                            contentPadding: const EdgeInsets.symmetric(
                              horizontal: 16, vertical: 3),
                            leading: Icon(
                              item.eventStatus == 'RETRACTED'
                                  ? Icons.report_outlined : Icons.article_outlined,
                              size: 22, color: colors.onSurfaceVariant),
                            title: Text(item.headline, maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.bodyMedium?.copyWith(
                                fontWeight: FontWeight.w600)),
                            subtitle: Padding(
                              padding: const EdgeInsets.only(top: 5),
                              child: Text(
                                '${item.publisher} · ${_ist(item.firstObservedAt)}'
                                '${item.eventStatus == 'RETRACTED' ? ' · Retracted' : ''}',
                                maxLines: 1, overflow: TextOverflow.ellipsis),
                            ),
                            trailing: const Icon(Icons.chevron_right_rounded, size: 19),
                            onTap: () => _showEvent(item),
                          ),
                          Divider(height: 1, color: colors.outlineVariant),
                        ],
                      );
                    },
                  ),
                ),
          ),
        ],
      ),
    );
  }

  Widget _filterBar() {
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 6),
      child: Column(
        children: [
          TextField(
            key: const ValueKey('market-event-search-input'),
            controller: _searchController,
            maxLength: 100,
            textInputAction: TextInputAction.search,
            onSubmitted: (_) => _applySearch(),
            decoration: InputDecoration(
              isDense: true,
              hintText: 'Search publisher headlines',
              counterText: '',
              prefixIcon: const Icon(Icons.search_rounded, size: 20),
              suffixIcon: IconButton(
                key: const ValueKey('market-event-search-apply'),
                tooltip: 'Search headlines',
                icon: const Icon(Icons.arrow_forward_rounded, size: 19),
                onPressed: _applySearch,
              ),
              border: const OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 7),
          SizedBox(
            height: 36,
            child: ListView(
              scrollDirection: Axis.horizontal,
              children: [
                _categoryChip('All', null),
                _categoryChip('Results', 'RESULTS'),
                _categoryChip('Regulatory', 'REGULATORY'),
                _categoryChip('Unclassified', 'UNCLASSIFIED'),
              ],
            ),
          ),
          const SizedBox(height: 4),
          SizedBox(
            height: 34,
            child: ListView(
              scrollDirection: Axis.horizontal,
              children: [
                _dayChip('All dates', 0),
                _dayChip('24h', 1),
                _dayChip('7d', 7),
                _dayChip('30d', 30),
              ],
            ),
          ),
          Divider(height: 7, color: scheme.outlineVariant),
        ],
      ),
    );
  }

  Widget _categoryChip(String label, String? value) => Padding(
    padding: const EdgeInsets.only(right: 6),
    child: ChoiceChip(
      key: ValueKey('market-event-category-${value ?? 'ALL'}'),
      label: Text(label),
      selected: _category == value,
      onSelected: (_) {
        if (_category == value) return;
        setState(() => _category = value);
        unawaited(_reload(resetPrevious: true));
      },
    ),
  );

  Widget _dayChip(String label, int days) => Padding(
    padding: const EdgeInsets.only(right: 6),
    child: ChoiceChip(
      key: ValueKey('market-event-window-$days'),
      label: Text(label),
      selected: _windowDays == days,
      onSelected: (_) {
        if (_windowDays == days) return;
        setState(() => _windowDays = days);
        unawaited(_reload(resetPrevious: true));
      },
    ),
  );

  void _applySearch() {
    final value = _searchController.text.trim();
    FocusScope.of(context).unfocus();
    if (value == _query) return;
    setState(() => _query = value);
    unawaited(_reload(resetPrevious: true));
  }

  Widget _emptyState() => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        const Icon(Icons.feed_outlined, size: 32),
        const SizedBox(height: 12),
        Text(_error ?? 'No currently authorized source-backed events.',
          textAlign: TextAlign.center),
        const SizedBox(height: 12),
        OutlinedButton(onPressed: () => unawaited(_reload()), child: const Text('Retry')),
      ]),
    ),
  );

  void _showEvent(MarketEventCard event) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(event.headline,
                  style: Theme.of(sheetContext).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w700)),
                const SizedBox(height: 10),
                Text('${event.publisher} · ${event.category}',
                  style: Theme.of(sheetContext).textTheme.labelMedium),
                const SizedBox(height: 12),
                Text('Published: ${event.publishedAt == null ? 'Unavailable' : _ist(event.publishedAt!)}'),
                Text('First observed: ${_ist(event.firstObservedAt)}'),
                Text('Analysis available: ${_ist(event.effectiveKnownAt)}'),
                Text('Status: ${event.eventStatus} · version ${event.version}'),
                const SizedBox(height: 12),
                Text(event.instrumentResolutionStatus == 'VERIFIED'
                    ? 'Instrument mapping verified'
                    : 'Affected instruments are not yet verified',
                  style: Theme.of(sheetContext).textTheme.bodySmall),
                const SizedBox(height: 12),
                TextButton.icon(
                  onPressed: () => unawaited(_openSource(event.sourceUrl)),
                  icon: const Icon(Icons.open_in_new, size: 18),
                  label: const Text('Open original publisher'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _openSource(String link) async {
    final uri = Uri.tryParse(link);
    if (uri == null || !{'https', 'http'}.contains(uri.scheme) || uri.host.isEmpty) return;
    try {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      // Browser access is optional; failure must never change event provenance.
    }
  }

  static String _ist(DateTime value) {
    final t = value.toUtc().add(const Duration(hours: 5, minutes: 30));
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(t.day)}/${two(t.month)}/${t.year} '
        '${two(t.hour)}:${two(t.minute)} IST';
  }
}
