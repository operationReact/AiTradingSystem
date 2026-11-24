import '../../../data/authenticated_api_client.dart';

/// Source-backed publisher article. This is a factual article view, not a
/// canonical issuer event, a verified instrument match, or a trading signal.
class MarketNewsArticle {
  const MarketNewsArticle({
    required this.id,
    required this.source,
    required this.title,
    required this.url,
    required this.publishedAt,
    required this.ingestedAt,
    this.excerpt,
  });

  final String id;
  final String source;
  final String title;
  final String url;
  final String? excerpt;
  final DateTime publishedAt;
  final DateTime ingestedAt;

  factory MarketNewsArticle.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic>) {
      throw const FormatException('Invalid market article');
    }
    final id = raw['id'];
    final source = raw['source'];
    final title = raw['title'];
    final url = raw['url'];
    final excerpt = raw['excerpt'];
    final published = DateTime.tryParse('${raw['publishedAt'] ?? ''}');
    final ingested = DateTime.tryParse('${raw['ingestedAt'] ?? ''}');
    final uri = url is String ? Uri.tryParse(url) : null;

    if (id is! String || id.trim().isEmpty ||
        source is! String || source.trim().isEmpty ||
        title is! String || title.trim().isEmpty ||
        uri == null || (uri.scheme != 'https' && uri.scheme != 'http') ||
        uri.host.isEmpty || published == null || ingested == null ||
        (excerpt != null && excerpt is! String)) {
      throw const FormatException('Invalid source-backed article fields');
    }
    return MarketNewsArticle(
      id: id,
      source: source.trim(),
      title: title.trim(),
      url: uri.toString(),
      excerpt: (excerpt as String?)?.trim(),
      publishedAt: published.toUtc(),
      ingestedAt: ingested.toUtc(),
    );
  }
}

class MarketNewsPage {
  const MarketNewsPage({
    required this.items,
    required this.page,
    required this.size,
    required this.totalItems,
  });

  final List<MarketNewsArticle> items;
  final int page;
  final int size;
  final int totalItems;
  bool get hasNext => (page + 1) * size < totalItems;

  factory MarketNewsPage.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic>) {
      throw const FormatException('Invalid market news page');
    }
    final rows = raw['items'];
    final page = raw['page'];
    final size = raw['size'];
    final total = raw['totalItems'];
    if (rows is! List || page is! int || page < 0 ||
        size is! int || size < 1 || size > 50 ||
        total is! int || total < 0 ||
        rows.length > size || rows.length > total) {
      throw const FormatException('Invalid market news pagination');
    }
    final items = rows.map(MarketNewsArticle.fromJson).toList(growable: false);
    if (items.map((a) => a.id).toSet().length != items.length) {
      throw const FormatException('Duplicate article id in one page');
    }
    return MarketNewsPage(
      items: List.unmodifiable(items), page: page, size: size, totalItems: total,
    );
  }
}

abstract interface class MarketNewsRepository {
  Future<MarketNewsPage> articles({required int page, int size = 20});
}

/// Customer-scoped endpoint. No publisher feed or Gemini key is accessed by Flutter.
/// Until the corresponding backend read API is deployed, the UI displays an honest
/// unavailable state, never local fixture articles.
class HttpMarketNewsRepository implements MarketNewsRepository {
  const HttpMarketNewsRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<MarketNewsPage> articles({required int page, int size = 20}) async {
    if (page < 0 || size < 1 || size > 50) {
      throw ArgumentError('Invalid news page request');
    }
    final response = await api.getJson('/api/customer/market-intelligence/articles',
        query: {'page': '$page', 'size': '$size'});
    return MarketNewsPage.fromJson(response);
  }
}
