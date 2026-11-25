import '../../../data/authenticated_api_client.dart';
import 'customer_home_summary_repository.dart';

/// Source-backed history limited to a customer's own room plan and virtual
/// PAPER ledger records. Does not imply an executed trade.
abstract interface class CustomerHomeActivityRepository {
  Future<List<HomeActivity>> load(String mode);
}

class HttpCustomerHomeActivityRepository
    implements CustomerHomeActivityRepository {
  const HttpCustomerHomeActivityRepository(this.api);

  final AuthenticatedApiClient api;

  @override
  Future<List<HomeActivity>> load(String mode) async {
    if (mode != 'PAPER' && mode != 'LIVE') {
      throw const FormatException('Unknown activity workspace');
    }
    final raw = await api.getJson(
      '/api/customer/home/v1/activity?mode=$mode',
    );
    if (raw is! List || raw.length > 40) {
      throw const FormatException('Activity response not verified');
    }
    return List.unmodifiable(raw.map(HomeActivity.decode));
  }
}
