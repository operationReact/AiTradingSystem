import '../../../data/authenticated_api_client.dart';

/// Read-only customer mutual-fund research. No orders, payments, or mandates.
abstract interface class MfAlertsRepository {
  Future<MfResearchBoard> load();
}

class HttpMfAlertsRepository implements MfAlertsRepository {
  const HttpMfAlertsRepository(this.api);
  final AuthenticatedApiClient api;
  @override
  Future<MfResearchBoard> load() async {
    final raw = await api.getJson('/api/customer/mutual-funds/v1/board');
    if (raw is! Map<String, dynamic>) {
      throw const FormatException('Mutual-fund board unavailable');
    }
    return MfResearchBoard.fromJson(raw);
  }
}

class MfResearchBoard {
  MfResearchBoard.fromJson(Map<String, dynamic> json)
      : source = json['source'] as String,
        disclaimer = json['disclaimer'] as String,
        feed = Map<String, dynamic>.from(json['feed'] as Map),
        watchlist = (json['watchlist'] as List)
            .map((e) => Map<String, dynamic>.from(e as Map)).toList(growable: false),
        alerts = (json['alerts'] as List)
            .map((e) => Map<String, dynamic>.from(e as Map)).toList(growable: false) {
    if (watchlist.length != 9 || alerts.length > 30) {
      throw const FormatException('Unrecognized mutual-fund watchlist');
    }
  }

  final String source;
  final String disclaimer;
  final Map<String, dynamic> feed;
  final List<Map<String, dynamic>> watchlist;
  final List<Map<String, dynamic>> alerts;
}
