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
    this.analysisStatus = 'NOT_ANALYZED',
    this.stockImpact,
  });

  final String id;
  final String source;
  final String title;
  final String url;
  final String? excerpt;
  final String analysisStatus;
  final MarketNewsImpact? stockImpact;
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
    final analysisStatus = raw['analysisStatus'] ?? 'NOT_ANALYZED';
    final impactRaw = raw['stockImpact'];
    final published = DateTime.tryParse('${raw['publishedAt'] ?? ''}');
    final ingested = DateTime.tryParse('${raw['ingestedAt'] ?? ''}');
    final uri = url is String ? Uri.tryParse(url) : null;

    if (id is! String || id.trim().isEmpty ||
        source is! String || source.trim().isEmpty ||
        title is! String || title.trim().isEmpty ||
        uri == null || (uri.scheme != 'https' && uri.scheme != 'http') ||
        uri.host.isEmpty || published == null || ingested == null ||
        (excerpt != null && excerpt is! String) ||
        analysisStatus is! String ||
        !{'NOT_ANALYZED', 'ANALYZED', 'FAILED'}.contains(analysisStatus) ||
        (impactRaw != null && impactRaw is! Map<String, dynamic>) ||
        (analysisStatus == 'ANALYZED' && impactRaw == null)) {
      throw const FormatException('Invalid source-backed article fields');
    }
    return MarketNewsArticle(
      id: id,
      source: source.trim(),
      title: title.trim(),
      url: uri.toString(),
      excerpt: (excerpt as String?)?.trim(),
      analysisStatus: analysisStatus,
      stockImpact: impactRaw == null ? null : MarketNewsImpact.fromJson(impactRaw),
      publishedAt: published.toUtc(),
      ingestedAt: ingested.toUtc(),
    );
  }
}

/// Advisory Gemini classification for an article. Scores describe evidence only;
/// they are not a price forecast or an instruction to trade.
class MarketNewsImpact {
  const MarketNewsImpact({
    required this.sentiment,
    required this.category,
    required this.impactedSymbols,
    required this.evidence,
    required this.risks,
    this.directionScore,
    this.confidence,
    this.materiality,
    this.horizon,
    this.contradiction,
    this.ambiguity,
    this.analyzedAt,
    this.provider,
    this.model,
  });

  final String sentiment;
  final String category;
  final List<String> impactedSymbols;
  final String evidence;
  final List<String> risks;
  final double? directionScore;
  final double? confidence;
  final double? materiality;
  final String? horizon;
  final bool? contradiction;
  final bool? ambiguity;
  final DateTime? analyzedAt;
  final String? provider;
  final String? model;

  factory MarketNewsImpact.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic>) {
      throw const FormatException('Invalid stock impact analysis');
    }
    final sentiment = raw['sentiment'];
    final category = raw['category'];
    final symbols = raw['impactedSymbols'];
    final evidence = raw['evidence'];
    final risks = raw['risks'];
    final score = _boundedScore(raw['directionScore'], -1, 1);
    final confidence = _boundedScore(raw['confidence'], 0, 1);
    final materiality = _boundedScore(raw['materiality'], 0, 1);
    final horizon = raw['horizon'];
    final contradiction = raw['contradiction'];
    final ambiguity = raw['ambiguity'];
    final analyzed = raw['analyzedAt'];
    final analyzedAt = analyzed == null ? null : DateTime.tryParse('$analyzed');
    if (sentiment is! String ||
        !{'POSITIVE', 'NEGATIVE', 'NEUTRAL', 'MIXED'}.contains(sentiment) ||
        category is! String || category.trim().isEmpty ||
        symbols is! List || symbols.any((v) => v is! String) ||
        evidence is! String || evidence.trim().isEmpty ||
        risks is! List || risks.any((v) => v is! String) ||
        (horizon != null && !{'INTRADAY', 'MULTI_DAY', 'LONGER_TERM', 'UNKNOWN'}.contains(horizon)) ||
        (contradiction != null && contradiction is! bool) ||
        (ambiguity != null && ambiguity is! bool) ||
        (analyzed != null && analyzedAt == null)) {
      throw const FormatException('Invalid stock impact analysis fields');
    }
    return MarketNewsImpact(
      sentiment: sentiment,
      category: category,
      impactedSymbols: List.unmodifiable(symbols.cast<String>()),
      evidence: evidence.trim(),
      risks: List.unmodifiable(risks.cast<String>()),
      directionScore: score,
      confidence: confidence,
      materiality: materiality,
      horizon: horizon as String?,
      contradiction: contradiction as bool?,
      ambiguity: ambiguity as bool?,
      analyzedAt: analyzedAt?.toUtc(),
      provider: raw['provider'] as String?,
      model: raw['model'] as String?,
    );
  }

  static double? _boundedScore(Object? value, double minimum, double maximum) {
    if (value == null) return null;
    if (value is! num || !value.isFinite || value < minimum || value > maximum) {
      throw const FormatException('Impact score is out of range');
    }
    return value.toDouble();
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

class NewsDrivenMindScan {
  const NewsDrivenMindScan({required this.id, required this.status,
    required this.scannedCount, required this.candidateCount});
  final String id;
  final String status;
  final int scannedCount;
  final int candidateCount;

  factory NewsDrivenMindScan.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['id'] is! String ||
        raw['status'] is! String || raw['scannedCount'] is! int ||
        raw['candidateCount'] is! int) {
      throw const FormatException('Invalid news-driven Mind scan response');
    }
    return NewsDrivenMindScan(id: raw['id'] as String, status: raw['status'] as String,
      scannedCount: raw['scannedCount'] as int, candidateCount: raw['candidateCount'] as int);
  }
}

class NewsDrivenMindEvidence {
  const NewsDrivenMindEvidence({required this.publisher, required this.title,
    required this.url, required this.summary, required this.publishedAt});
  final String publisher, title, url, summary;
  final DateTime? publishedAt;

  factory NewsDrivenMindEvidence.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['source'] is! String ||
        raw['title'] is! String || raw['url'] is! String ||
        raw['evidenceSummary'] is! String) {
      throw const FormatException('Invalid supporting news evidence');
    }
    final uri = Uri.tryParse(raw['url'] as String);
    final published = raw['publishedAt'] == null
        ? null : DateTime.tryParse('${raw['publishedAt']}');
    if (uri == null || (uri.scheme != 'https' && uri.scheme != 'http') ||
        uri.host.isEmpty || (raw['publishedAt'] != null && published == null)) {
      throw const FormatException('Invalid supporting news evidence fields');
    }
    return NewsDrivenMindEvidence(publisher: raw['source'] as String,
      title: raw['title'] as String, url: uri.toString(),
      summary: raw['evidenceSummary'] as String, publishedAt: published?.toUtc());
  }
}

class NewsDrivenMindCandidate {
  const NewsDrivenMindCandidate({required this.symbol, required this.score,
    required this.direction, required this.evidence});
  final String symbol;
  final double score;
  final String direction;
  final List<NewsDrivenMindEvidence> evidence;

  factory NewsDrivenMindCandidate.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['tradingSymbol'] is! String ||
        raw['directionStrength'] is! num || !(raw['directionStrength'] as num).isFinite ||
        !const ['POSITIVE', 'NEGATIVE'].contains(raw['direction']) ||
        raw['supportingEvidence'] is! List) {
      throw const FormatException('Invalid news-driven candidate');
    }
    return NewsDrivenMindCandidate(
      symbol: raw['tradingSymbol'] as String,
      score: (raw['directionStrength'] as num).toDouble(),
      direction: raw['direction'] as String,
      evidence: List.unmodifiable((raw['supportingEvidence'] as List)
          .map(NewsDrivenMindEvidence.fromJson)),
    );
  }
}

abstract interface class MarketNewsRepository {
  Future<MarketNewsPage> articles({required int page, int size = 20, String? symbol});
  Future<List<MarketNewsSource>> sources();
  Future<NewsDrivenMindScan> runNewsDrivenScan({String interval = 'FIVE_MINUTE', int limit = 100});
  Future<List<NewsDrivenMindCandidate>> newsDrivenCandidates(String runId);
}

class MarketNewsSource {
  const MarketNewsSource({required this.sourceId, required this.publisher,
    required this.status, required this.fetchedItems, required this.insertedItems,
    this.finishedAt, this.ageSeconds, this.errorCode});
  final String sourceId, publisher, status;
  final int fetchedItems, insertedItems;
  final DateTime? finishedAt;
  final int? ageSeconds;
  final String? errorCode;

  factory MarketNewsSource.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['sourceId'] is! String ||
        raw['publisher'] is! String || raw['status'] is! String ||
        raw['fetchedItems'] is! int || raw['insertedItems'] is! int) {
      throw const FormatException('Invalid news source health');
    }
    final finished = raw['finishedAt'] == null ? null : DateTime.tryParse('${raw['finishedAt']}');
    final age = raw['ageSeconds'];
    if (age != null && age is! int || (raw['finishedAt'] != null && finished == null) ||
        (age is int && age < 0)) {
      throw const FormatException('Invalid news source health fields');
    }
    return MarketNewsSource(sourceId: raw['sourceId'] as String,
      publisher: raw['publisher'] as String, status: raw['status'] as String,
      fetchedItems: raw['fetchedItems'] as int, insertedItems: raw['insertedItems'] as int,
      finishedAt: finished?.toUtc(), ageSeconds: age as int?,
      errorCode: raw['errorCode'] as String?);
  }
}

/// Customer-scoped endpoint. Flutter never contacts publisher feeds or Gemini directly.
/// Source approval and analysis status come from the backend; no fixture fallback is used.
class HttpMarketNewsRepository implements MarketNewsRepository {
  const HttpMarketNewsRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<MarketNewsPage> articles({required int page, int size = 20, String? symbol}) async {
    if (page < 0 || size < 1 || size > 50) {
      throw ArgumentError('Invalid news page request');
    }
    final normalized = symbol?.trim().toUpperCase();
    if (normalized != null && normalized.isNotEmpty &&
        !RegExp(r'^[A-Z0-9&.-]{1,20}$').hasMatch(normalized)) {
      throw ArgumentError('Invalid stock symbol');
    }
    final response = await api.getJson('/api/customer/market-intelligence/articles',
        query: {'page': '$page', 'size': '$size', if (normalized != null && normalized.isNotEmpty) 'symbol': normalized});
    return MarketNewsPage.fromJson(response);
  }

  @override
  Future<List<MarketNewsSource>> sources() async {
    final response = await api.getJson('/api/customer/market-intelligence/sources');
    if (response is! List) throw const FormatException('Invalid news source health response');
    return List.unmodifiable(response.map(MarketNewsSource.fromJson));
  }

  @override
  Future<NewsDrivenMindScan> runNewsDrivenScan({String interval = 'FIVE_MINUTE', int limit = 100}) async {
    if (!const {'ONE_MINUTE', 'THREE_MINUTE', 'FIVE_MINUTE'}.contains(interval) ||
        limit < 1 || limit > 1000) {
      throw ArgumentError('Invalid news-driven scan request');
    }
    final response = await api.postJson('/api/research/news-driven-scans',
        query: {'interval': interval, 'limit': '$limit'},
        timeout: const Duration(seconds: 90));
    return NewsDrivenMindScan.fromJson(response);
  }

  @override
  Future<List<NewsDrivenMindCandidate>> newsDrivenCandidates(String runId) async {
    if (runId.length != 36 ||
        !RegExp(r'^[0-9a-fA-F-]{36}').hasMatch(runId)) {
      throw ArgumentError('Invalid news scan id');
    }
    final response = await api.getJson('/api/research/news-driven-scans/$runId/candidates');
    if (response is! List) throw const FormatException('Invalid news-driven candidate list');
    return List.unmodifiable(response.map(NewsDrivenMindCandidate.fromJson));
  }
}
