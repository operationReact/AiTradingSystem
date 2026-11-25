import '../../../data/authenticated_api_client.dart';

/// Normalized source records from the authenticated private account backend.
/// Existing provider adapters remain a bounded fallback during rollout.
class HttpCustomerPortfolioRepository {
  HttpCustomerPortfolioRepository(this.api);
  final AuthenticatedApiClient api;

  Future<Object?> records(String broker, {required bool positions}) async {
    if (broker != 'ANGEL_ONE' && broker != 'UPSTOX') {
      throw const FormatException('Unknown Portfolio broker');
    }
    final kind = positions ? 'POSITIONS' : 'HOLDINGS';
    final raw = await api.getJson(
      '/api/customer/portfolio/records?broker=$broker&kind=$kind',
    );
    if (raw is! Map || raw['broker'] != broker ||
        raw['kind'] != kind ||
        raw['attribution'] != 'BROKER_REPORTED_UNATTRIBUTED' ||
        raw['truncated'] != false ||
        raw['rows'] is! List) {
      // Never silently show truncated source data as a complete portfolio.
      throw const FormatException('Portfolio read model unavailable');
    }
    return {'rows': raw['rows']};
  }
}
