import '../../../data/authenticated_api_client.dart';

/// Immutable, source-backed event record. This DTO never implies a trading signal.
class MarketEventCard {
  const MarketEventCard({
    required this.eventId,
    required this.version,
    required this.headline,
    required this.eventClass,
    required this.category,
    required this.eventStatus,
    required this.sourceId,
    required this.publisher,
    required this.sourceUrl,
    required this.firstObservedAt,
    required this.effectiveKnownAt,
    required this.instrumentResolutionStatus,
    this.publishedAt,
  });

  final String eventId;
  final int version;
  final String headline;
  final String eventClass;
  final String category;
  final String eventStatus;
  final String sourceId;
  final String publisher;
  final String sourceUrl;
  final DateTime? publishedAt;
  final DateTime firstObservedAt;
  final DateTime effectiveKnownAt;
  final String instrumentResolutionStatus;

  factory MarketEventCard.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) {
      throw const FormatException('Invalid event record');
    }
    final id = value['eventId'];
    final version = value['version'];
    final headline = value['headline'];
    final eventClass = value['eventClass'];
    final category = value['category'];
    final eventStatus = value['eventStatus'];
    final sourceId = value['sourceId'];
    final publisher = value['publisher'];
    final uri = Uri.tryParse('${value['sourceUrl'] ?? ''}');
    final publishedRaw = value['publishedAt'];
    final published = publishedRaw == null ? null : DateTime.tryParse('$publishedRaw');
    final observed = DateTime.tryParse('${value['firstObservedAt'] ?? ''}');
    final known = DateTime.tryParse('${value['effectiveKnownAt'] ?? ''}');
    final resolution = value['instrumentResolutionStatus'];
    if (id is! String || id.trim().isEmpty ||
        version is! int || version <= 0 ||
        headline is! String || headline.trim().isEmpty ||
        eventClass is! String || eventClass.trim().isEmpty ||
        category is! String || category.trim().isEmpty ||
        eventStatus is! String || !{'ACTIVE', 'RETRACTED'}.contains(eventStatus) ||
        sourceId is! String || sourceId.trim().isEmpty ||
        publisher is! String || publisher.trim().isEmpty ||
        uri == null || !{'https', 'http'}.contains(uri.scheme) || uri.host.isEmpty ||
        observed == null || known == null || known.isBefore(observed) ||
        (publishedRaw != null && published == null) ||
        resolution is! String || resolution.trim().isEmpty) {
      throw const FormatException('Invalid event evidence fields');
    }
    return MarketEventCard(
      eventId: id,
      version: version,
      headline: headline.trim(),
      eventClass: eventClass.trim(),
      category: category.trim(),
      eventStatus: eventStatus,
      sourceId: sourceId,
      publisher: publisher,
      sourceUrl: uri.toString(),
      publishedAt: published?.toUtc(),
      firstObservedAt: observed.toUtc(),
      effectiveKnownAt: known.toUtc(),
      instrumentResolutionStatus: resolution,
    );
  }
}

class MarketEventPage {
  const MarketEventPage({
    required this.items,
    required this.page,
    required this.size,
    required this.totalItems,
  });

  final List<MarketEventCard> items;
  final int page;
  final int size;
  final int totalItems;
  bool get hasNext => (page + 1) * size < totalItems;

  factory MarketEventPage.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic>) {
      throw const FormatException('Invalid event response');
    }
    final itemsRaw = raw['items'];
    final page = raw['page'];
    final size = raw['size'];
    final total = raw['totalItems'];
    if (itemsRaw is! List || page is! int || page < 0 ||
        size is! int || size < 1 || size > 50 ||
        total is! int || total < 0 || itemsRaw.length > size ||
        itemsRaw.length > total) {
      throw const FormatException('Invalid event pagination');
    }
    final parsed = itemsRaw.map(MarketEventCard.fromJson).toList(growable: false);
    if (parsed.map((e) => e.eventId).toSet().length != parsed.length) {
      throw const FormatException('Duplicate event in page');
    }
    return MarketEventPage(items: List.unmodifiable(parsed), page: page,
      size: size, totalItems: total);
  }
}

abstract interface class MarketEventRepository {
  Future<MarketEventPage> events({required int page, int size = 20});
}

/// No direct publisher calls, broker data mutation or Gemini credentials in Flutter.
class HttpMarketEventRepository implements MarketEventRepository {
  const HttpMarketEventRepository(this.api);
  final AuthenticatedApiClient api;

  @override
  Future<MarketEventPage> events({required int page, int size = 20}) async {
    if (page < 0 || page > 200 || size < 1 || size > 50) {
      throw ArgumentError('Invalid event page request');
    }
    final json = await api.getJson('/api/customer/market-intelligence/events',
      query: {'page': '$page', 'size': '$size'});
    return MarketEventPage.fromJson(json);
  }
}
