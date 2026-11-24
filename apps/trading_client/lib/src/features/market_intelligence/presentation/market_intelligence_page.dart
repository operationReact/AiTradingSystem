import 'dart:async';

import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../data/market_news_repository.dart';
import '../data/market_event_repository.dart';
import 'market_event_timeline_page.dart';

/// Read-only, source-attributed news surface. This view deliberately does not
/// reinterpret an article as a verified stock event, options signal or order.
class MarketIntelligencePage extends StatefulWidget {
  const MarketIntelligencePage({
    required this.repository, this.eventRepository, super.key,
  });

  final MarketNewsRepository repository;
  final MarketEventRepository? eventRepository;

  @override
  State<MarketIntelligencePage> createState() => _MarketIntelligencePageState();
}

class _MarketIntelligencePageState extends State<MarketIntelligencePage> {
  final ScrollController _scroll = ScrollController();
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
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _generation++;
    _scroll.removeListener(_onScroll);
    _scroll.dispose();
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
      final page = await widget.repository.articles(page: 0);
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

  Future<void> _loadMore() async {
    if (_loading || _loadingMore || !_hasMore) return;
    final generation = _generation;
    final requestedPage = _nextPage;
    setState(() {
      _loadingMore = true;
      _pageError = null;
    });
    try {
      final page = await widget.repository.articles(page: requestedPage);
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
                builder: (_) => MarketEventTimelinePage(repository: widget.eventRepository!),
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
