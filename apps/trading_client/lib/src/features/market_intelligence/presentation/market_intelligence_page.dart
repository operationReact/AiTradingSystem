import 'dart:async';

import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../data/market_news_repository.dart';
import '../data/market_event_repository.dart';
import '../data/market_event_detail_repository.dart';
import 'market_event_timeline_page.dart';

/// Read-only, source-attributed news surface. This view deliberately does not
/// reinterpret an article as a verified stock event, options signal or order.
class MarketIntelligencePage extends StatefulWidget {
  const MarketIntelligencePage({
    required this.repository, this.eventRepository, this.eventDetailRepository,
    this.initialSymbol, super.key,
  });

  final MarketNewsRepository repository;
  final MarketEventRepository? eventRepository;
  final MarketEventDetailRepository? eventDetailRepository;
  final String? initialSymbol;

  @override
  State<MarketIntelligencePage> createState() => _MarketIntelligencePageState();
}

class _MarketIntelligencePageState extends State<MarketIntelligencePage> {
  final ScrollController _scroll = ScrollController();
  final TextEditingController _symbolInput = TextEditingController();
  String? _activeSymbol;
  final List<MarketNewsArticle> _articles = [];
  int _generation = 0;
  int _nextPage = 0;
  bool _loading = false;
  bool _loadingMore = false;
  bool _hasMore = false;
  String? _error;
  String? _pageError;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
    _activeSymbol = _normalizeSymbol(widget.initialSymbol);
    _symbolInput.text = _activeSymbol ?? '';
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _generation++;
    _scroll.removeListener(_onScroll);
    _scroll.dispose();
    _symbolInput.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scroll.hasClients || _loading || _loadingMore || !_hasMore) return;
    if (_scroll.position.pixels >= _scroll.position.maxScrollExtent - 250) {
      unawaited(_loadMore());
    }
  }

  Future<void> _refresh() async {
    final generation = ++_generation;
    setState(() {
      _loading = true;
      _error = null;
      _pageError = null;
    });
    try {
      final page = await widget.repository.articles(page: 0, symbol: _activeSymbol);
      if (!mounted || generation != _generation) return;
      setState(() {
        _articles
          ..clear()
          ..addAll(page.items);
        _nextPage = 1;
        _hasMore = page.hasNext;
        _loading = false;
      });
    } catch (_) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _error = _articles.isEmpty
            ? 'Market intelligence is not available from the connected source yet.'
            : 'Could not refresh. Showing the last articles loaded in this session.';
        _loading = false;
      });
    }
  }

  static String? _normalizeSymbol(String? value) {
    final normalized = value?.trim().toUpperCase();
    if (normalized == null || normalized.isEmpty) return null;
    return RegExp(r'^[A-Z0-9&.-]{1,20}
    if (_loading || _loadingMore || !_hasMore) return;
    final generation = _generation;
    final requestedPage = _nextPage;
    setState(() {
      _loadingMore = true;
      _pageError = null;
    });
    try {
      final page = await widget.repository.articles(page: requestedPage, symbol: _activeSymbol);
      if (!mounted || generation != _generation) return;
      final known = _articles.map((a) => a.id).toSet();
      setState(() {
        for (final article in page.items) {
          if (known.add(article.id)) _articles.add(article);
        }
        _nextPage = requestedPage + 1;
        _hasMore = page.hasNext;
        _loadingMore = false;
      });
    } catch (_) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _pageError = 'Additional headlines could not be loaded.';
        _loadingMore = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Market intelligence'),
        actions: [
          if (widget.eventRepository != null)
            IconButton(
              key: const ValueKey('market-events-open'),
              tooltip: 'Event timeline',
              onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
                builder: (_) => MarketEventTimelinePage(
                  repository: widget.eventRepository!,
                  detailRepository: widget.eventDetailRepository,
                ),
              )),
              icon: const Icon(Icons.timeline_rounded),
            ),
          IconButton(
            key: const ValueKey('market-news-refresh'),
            tooltip: 'Refresh news',
            onPressed: _loading ? null : () => unawaited(_refresh()),
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: _loading && _articles.isEmpty
          ? const Center(child: CircularProgressIndicator())
          : _articles.isEmpty
              ? _emptyState()
              : RefreshIndicator(
                  onRefresh: _refresh,
                  child: ListView.builder(
                    controller: _scroll,
                    physics: const AlwaysScrollableScrollPhysics(),
                    itemCount: _articles.length + 2,
                    itemBuilder: (context, index) {
                      if (index == 0) {
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            if (_error != null)
                              _message(_error!, Icons.cloud_off_outlined),
                            Padding(
                              padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
                              child: Text('Latest publisher-attributed headlines',
                                style: Theme.of(context).textTheme.labelMedium
                                    ?.copyWith(color: scheme.onSurfaceVariant)),
                            ),
                            _symbolSearchBar(scheme),
                            if (_activeSymbol != null)
                              Padding(
                                padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                                child: Text('Showing successfully analyzed articles affecting $_activeSymbol',
                                  style: Theme.of(context).textTheme.labelSmall
                                      ?.copyWith(color: scheme.onSurfaceVariant)),
                              ),
                          ],
                        );
                      }
                      if (index == _articles.length + 1) {
                        if (_loadingMore) {
                          return const Padding(padding: EdgeInsets.all(16),
                            child: Center(child: CircularProgressIndicator()));
                        }
                        if (_pageError != null) {
                          return TextButton(
                            onPressed: () => unawaited(_loadMore()),
                            child: Text('Retry loading headlines — ${_pageError!}'),
                          );
                        }
                        return const SizedBox(height: 16);
                      }
                      final article = _articles[index - 1];
                      return Column(children: [
                        _ArticleLine(
                          key: ValueKey('market-news-${article.id}'),
                          article: article,
                          onTap: () => _openArticle(article),
                        ),
                        Divider(height: 1, color: scheme.outlineVariant),
                      ]);
                    },
                  ),
                ),
    );
  }

  Widget _symbolSearchBar(ColorScheme scheme) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 4, 16, 10),
    child: Row(children: [
      Expanded(
        child: TextField(
          key: const ValueKey('market-news-symbol-filter'),
          controller: _symbolInput,
          textCapitalization: TextCapitalization.characters,
          textInputAction: TextInputAction.search,
          onSubmitted: (_) => _applySymbol(),
          decoration: InputDecoration(
            isDense: true,
            labelText: 'Filter by stock symbol',
            hintText: 'e.g. RELIANCE',
            prefixIcon: const Icon(Icons.search, size: 19),
            suffixIcon: _activeSymbol == null ? null : IconButton(
              tooltip: 'Clear stock filter',
              onPressed: () {
                _symbolInput.clear();
                setState(() { _activeSymbol = null; });
                unawaited(_refresh());
              },
              icon: const Icon(Icons.close, size: 18),
            ),
            border: const OutlineInputBorder(),
          ),
        ),
      ),
      const SizedBox(width: 8),
      IconButton.filledTonal(
        key: const ValueKey('market-news-symbol-submit'),
        tooltip: 'Filter news',
        onPressed: _loading ? null : _applySymbol,
        icon: const Icon(Icons.arrow_forward),
      ),
    ]),
  );

  Widget _emptyState() {
    final scheme = Theme.of(context).colorScheme;
    return Column(children: [
      _symbolSearchBar(scheme),
      if (_activeSymbol != null)
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
          child: Text('Showing successfully analyzed articles affecting $_activeSymbol',
            style: Theme.of(context).textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
        ),
      Expanded(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              Icon(_error == null ? Icons.article_outlined : Icons.cloud_off_outlined,
                size: 30, color: scheme.onSurfaceVariant),
              const SizedBox(height: 12),
              Text(_activeSymbol == null
                  ? (_error ?? 'No approved publisher articles are available.')
                  : (_error ?? 'No successfully analyzed news for $_activeSymbol.'),
                textAlign: TextAlign.center),
              const SizedBox(height: 12),
              OutlinedButton(onPressed: () => unawaited(_refresh()),
                child: const Text('Retry')),
            ]),
          ),
        ),
      ),
    ]);
  }

  Widget _message(String message, IconData icon) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
    child: Row(children: [
      Icon(icon, size: 17),
      const SizedBox(width: 8),
      Expanded(child: Text(message, style: Theme.of(context).textTheme.bodySmall)),
    ]),
  );

  void _openArticle(MarketNewsArticle article) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (sheetContext) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 24),
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(article.title, style: Theme.of(sheetContext).textTheme.titleMedium
                      ?.copyWith(fontWeight: FontWeight.w700)),
                  const SizedBox(height: 8),
                  Text('${article.source} · ${_ist(article.publishedAt)}',
                    style: Theme.of(sheetContext).textTheme.labelSmall),
                  if (article.excerpt != null && article.excerpt!.isNotEmpty) ...[
                    const SizedBox(height: 14),
                    Text(article.excerpt!),
                  ],
                  const SizedBox(height: 16),
                  _stockImpactDetails(sheetContext, article),
                  const SizedBox(height: 12),
                  Text('Observed by MintTick · ${_ist(article.ingestedAt)}',
                    style: Theme.of(sheetContext).textTheme.labelSmall),
                  const SizedBox(height: 12),
                  TextButton.icon(
                    onPressed: () => unawaited(_openOriginal(article.url)),
                    icon: const Icon(Icons.open_in_new, size: 17),
                    label: const Text('Open publisher source'),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _stockImpactDetails(BuildContext context, MarketNewsArticle article) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    final impact = article.stockImpact;
    if (impact == null) {
      final message = article.analysisStatus == 'FAILED'
          ? 'MintTick Mind analysis failed. No stock impact is available.'
          : 'MintTick Mind has not analyzed this article yet.';
      return Text(message, style: theme.textTheme.bodySmall?.copyWith(color: muted));
    }
    String score(double? value) => value == null
        ? 'not available'
        : '${value >= 0 ? '+' : ''}${value.toStringAsFixed(2)}';
    return Container(
      key: ValueKey('market-news-impact-${article.id}'),
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.35),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: theme.colorScheme.outlineVariant),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('MintTick Mind · stock impact assessment',
          style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
        const SizedBox(height: 5),
        Text('Affected symbols: ${impact.impactedSymbols.isEmpty ? 'not identified' : impact.impactedSymbols.join(', ')}'),
        Text('News tone: ${impact.sentiment.toLowerCase()} · ${impact.category.toLowerCase().replaceAll('_', ' ')}'),
        Text('Directional evidence: ${score(impact.directionScore)} · confidence ${impact.confidence == null ? 'not available' : '${(impact.confidence! * 100).round()}%'}'),
        Text('Materiality: ${impact.materiality == null ? 'not available' : '${(impact.materiality! * 100).round()}%'} · horizon ${impact.horizon?.toLowerCase().replaceAll('_', ' ') ?? 'unknown'}'),
        if (impact.evidence.isNotEmpty) ...[
          const SizedBox(height: 8),
          Text('Evidence: ${impact.evidence}'),
        ],
        if (impact.risks.isNotEmpty) ...[
          const SizedBox(height: 6),
          Text('Uncertainty: ${impact.risks.join(' · ')}'),
        ],
        if (impact.contradiction == true || impact.ambiguity == true) ...[
          const SizedBox(height: 5),
          Text('Caution: ${impact.contradiction == true ? 'conflicting evidence' : ''}${impact.contradiction == true && impact.ambiguity == true ? ' · ' : ''}${impact.ambiguity == true ? 'interpretation is ambiguous' : ''}',
            style: theme.textTheme.bodySmall?.copyWith(color: muted)),
        ],
        const SizedBox(height: 8),
        Text('Research context only. Directional evidence is not a price or return forecast, and cannot trigger a trade.',
          style: theme.textTheme.labelSmall?.copyWith(color: muted)),
      ]),
    );
  }

  Future<void> _openOriginal(String original) async {
    final uri = Uri.tryParse(original);
    if (uri == null || !{'http', 'https'}.contains(uri.scheme) || uri.host.isEmpty) {
      return;
    }
    try {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      // A publisher link is optional. Failure never changes the article's evidence.
    }
  }

  static String _ist(DateTime value) {
    final t = value.toUtc().add(const Duration(hours: 5, minutes: 30));
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(t.day)}/${two(t.month)}/${t.year} '
        '${two(t.hour)}:${two(t.minute)} IST';
  }
}

class _ArticleLine extends StatelessWidget {
  const _ArticleLine({required this.article, required this.onTap, super.key});

  final MarketNewsArticle article;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ListTile(
      dense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      leading: Icon(Icons.article_outlined, size: 22, color: scheme.onSurfaceVariant),
      title: Text(article.title, maxLines: 2, overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600)),
      subtitle: Padding(
        padding: const EdgeInsets.only(top: 5),
        child: Text(
          '${article.source} · ${_MarketIntelligencePageState._ist(article.publishedAt)}'
          '${article.stockImpact == null ? (article.analysisStatus == 'FAILED' ? ' · analysis failed' : ' · Mind analysis pending') : ' · ${article.stockImpact!.sentiment.toLowerCase()} · ${article.stockImpact!.impactedSymbols.join(', ')}'}',
          maxLines: 2, overflow: TextOverflow.ellipsis),
      ),
      trailing: const Icon(Icons.chevron_right_rounded, size: 19),
      onTap: onTap,
    );
  }
}
).hasMatch(normalized) ? normalized : null;
  }

  void _applySymbol() {
    final raw = _symbolInput.text.trim();
    final normalized = _normalizeSymbol(raw);
    if (raw.isNotEmpty && normalized == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Enter a valid exchange symbol, such as RELIANCE or TCS.')),
      );
      return;
    }
    setState(() { _activeSymbol = normalized; });
    unawaited(_refresh());
  }

  Future<void> _loadMore() async {
    if (_loading || _loadingMore || !_hasMore) return;
    final generation = _generation;
    final requestedPage = _nextPage;
    setState(() {
      _loadingMore = true;
      _pageError = null;
    });
    try {
      final page = await widget.repository.articles(page: requestedPage, symbol: _activeSymbol);
      if (!mounted || generation != _generation) return;
      final known = _articles.map((a) => a.id).toSet();
      setState(() {
        for (final article in page.items) {
          if (known.add(article.id)) _articles.add(article);
        }
        _nextPage = requestedPage + 1;
        _hasMore = page.hasNext;
        _loadingMore = false;
      });
    } catch (_) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _pageError = 'Additional headlines could not be loaded.';
        _loadingMore = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Market intelligence'),
        actions: [
          if (widget.eventRepository != null)
            IconButton(
              key: const ValueKey('market-events-open'),
              tooltip: 'Event timeline',
              onPressed: () => Navigator.of(context).push(MaterialPageRoute<void>(
                builder: (_) => MarketEventTimelinePage(
                  repository: widget.eventRepository!,
                  detailRepository: widget.eventDetailRepository,
                ),
              )),
              icon: const Icon(Icons.timeline_rounded),
            ),
          IconButton(
            key: const ValueKey('market-news-refresh'),
            tooltip: 'Refresh news',
            onPressed: _loading ? null : () => unawaited(_refresh()),
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: _loading && _articles.isEmpty
          ? const Center(child: CircularProgressIndicator())
          : _articles.isEmpty
              ? _emptyState()
              : RefreshIndicator(
                  onRefresh: _refresh,
                  child: ListView.builder(
                    controller: _scroll,
                    physics: const AlwaysScrollableScrollPhysics(),
                    itemCount: _articles.length + 2,
                    itemBuilder: (context, index) {
                      if (index == 0) {
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            if (_error != null)
                              _message(_error!, Icons.cloud_off_outlined),
                            Padding(
                              padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
                              child: Text('Publisher-attributed headlines',
                                style: Theme.of(context).textTheme.labelMedium
                                    ?.copyWith(color: scheme.onSurfaceVariant)),
                            ),
                          ],
                        );
                      }
                      if (index == _articles.length + 1) {
                        if (_loadingMore) {
                          return const Padding(padding: EdgeInsets.all(16),
                            child: Center(child: CircularProgressIndicator()));
                        }
                        if (_pageError != null) {
                          return TextButton(
                            onPressed: () => unawaited(_loadMore()),
                            child: Text('Retry loading headlines — ${_pageError!}'),
                          );
                        }
                        return const SizedBox(height: 16);
                      }
                      final article = _articles[index - 1];
                      return Column(children: [
                        _ArticleLine(
                          key: ValueKey('market-news-${article.id}'),
                          article: article,
                          onTap: () => _openArticle(article),
                        ),
                        Divider(height: 1, color: scheme.outlineVariant),
                      ]);
                    },
                  ),
                ),
    );
  }

  Widget _emptyState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Icon(_error == null ? Icons.article_outlined : Icons.cloud_off_outlined,
            size: 30, color: Theme.of(context).colorScheme.onSurfaceVariant),
          const SizedBox(height: 12),
          Text(_error ?? 'No approved publisher articles are available.',
            textAlign: TextAlign.center),
          const SizedBox(height: 12),
          OutlinedButton(onPressed: () => unawaited(_refresh()),
            child: const Text('Retry')),
        ]),
      ),
    );
  }

  Widget _message(String message, IconData icon) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
    child: Row(children: [
      Icon(icon, size: 17),
      const SizedBox(width: 8),
      Expanded(child: Text(message, style: Theme.of(context).textTheme.bodySmall)),
    ]),
  );

  void _openArticle(MarketNewsArticle article) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (sheetContext) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 24),
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(article.title, style: Theme.of(sheetContext).textTheme.titleMedium
                      ?.copyWith(fontWeight: FontWeight.w700)),
                  const SizedBox(height: 8),
                  Text('${article.source} · ${_ist(article.publishedAt)}',
                    style: Theme.of(sheetContext).textTheme.labelSmall),
                  if (article.excerpt != null && article.excerpt!.isNotEmpty) ...[
                    const SizedBox(height: 14),
                    Text(article.excerpt!),
                  ],
                  const SizedBox(height: 16),
                  Text('Observed by MintTick · ${_ist(article.ingestedAt)}',
                    style: Theme.of(sheetContext).textTheme.labelSmall),
                  const SizedBox(height: 12),
                  TextButton.icon(
                    onPressed: () => unawaited(_openOriginal(article.url)),
                    icon: const Icon(Icons.open_in_new, size: 17),
                    label: const Text('Open publisher source'),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Future<void> _openOriginal(String original) async {
    final uri = Uri.tryParse(original);
    if (uri == null || !{'http', 'https'}.contains(uri.scheme) || uri.host.isEmpty) {
      return;
    }
    try {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      // A publisher link is optional. Failure never changes the article's evidence.
    }
  }

  static String _ist(DateTime value) {
    final t = value.toUtc().add(const Duration(hours: 5, minutes: 30));
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(t.day)}/${two(t.month)}/${t.year} '
        '${two(t.hour)}:${two(t.minute)} IST';
  }
}

class _ArticleLine extends StatelessWidget {
  const _ArticleLine({required this.article, required this.onTap, super.key});

  final MarketNewsArticle article;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return ListTile(
      dense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      leading: Icon(Icons.article_outlined, size: 22, color: scheme.onSurfaceVariant),
      title: Text(article.title, maxLines: 2, overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600)),
      subtitle: Padding(
        padding: const EdgeInsets.only(top: 5),
        child: Text('${article.source} · ${_MarketIntelligencePageState._ist(article.publishedAt)}',
          maxLines: 1, overflow: TextOverflow.ellipsis),
      ),
      trailing: const Icon(Icons.chevron_right_rounded, size: 19),
      onTap: onTap,
    );
  }
}
