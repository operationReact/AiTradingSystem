import '../../../data/authenticated_api_client.dart';

/// No customer-entered broker credentials: Upstox handles login on its own site.
class CustomerUpstoxStatus {
  const CustomerUpstoxStatus({
    required this.available,
    required this.connected,
    required this.accountMasked,
    required this.expiresAt,
    required this.orderExecutionEnabled,
  });

  final bool available;
  final bool connected;
  final String? accountMasked;
  final DateTime? expiresAt;
  final bool orderExecutionEnabled;

  factory CustomerUpstoxStatus.fromJson(Object? raw) {
    if (raw is! Map<String, dynamic> || raw['broker'] != 'UPSTOX' ||
        raw['available'] is! bool || raw['connected'] is! bool ||
        raw['orderExecutionEnabled'] is! bool) {
      throw const FormatException('Invalid Upstox connection status');
    }
    return CustomerUpstoxStatus(
      available: raw['available'] as bool,
      connected: raw['connected'] as bool,
      accountMasked: raw['accountMasked'] as String?,
      expiresAt: raw['expiresAt'] is String
          ? DateTime.tryParse(raw['expiresAt'] as String) : null,
      orderExecutionEnabled: raw['orderExecutionEnabled'] as bool,
    );
  }
}

class HttpCustomerUpstoxRepository {
  HttpCustomerUpstoxRepository(this.api);
  final AuthenticatedApiClient api;

  static const _root = '/api/customer/brokers/upstox';

  Future<CustomerUpstoxStatus> status() async =>
      CustomerUpstoxStatus.fromJson(await api.getJson('$_root/status'));

  /// Optional exact-Cognito-owner PIN+TOTP capability. The backend alone
  /// authorizes access to SSM and verifies the provider account identity.
  Future<bool> configuredLoginAvailable() async {
    final data = await api.getJson('$_root/configured-login');
    if (data is! Map<String, dynamic> || data['available'] is! bool) {
      throw const FormatException('Invalid secure Upstox login availability');
    }
    return data['available'] as bool;
  }

  /// No PIN, TOTP seed, API key or one-time code ever crosses Flutter.
  Future<CustomerUpstoxStatus> connectConfigured() async =>
      CustomerUpstoxStatus.fromJson(
        await api.postJson('$_root/connect-configured'));



  Future<Uri> startAuthorization() async {
    final raw = await api.postJson('$_root/start');
    if (raw is! Map<String, dynamic> ||
        raw['authorizationUrl'] is! String) {
      throw const FormatException('Invalid Upstox authorization link');
    }
    final uri = Uri.parse(raw['authorizationUrl'] as String);
    // Only Upstox's official, fixed login endpoint can be opened externally.
    if (uri.scheme != 'https' || uri.host != 'api.upstox.com' ||
        uri.path != '/v2/login/authorization/dialog' ||
        uri.queryParameters['response_type'] != 'code' ||
        (uri.queryParameters['state']?.isEmpty ?? true)) {
      throw const FormatException('Untrusted broker authorization URL');
    }
    return uri;
  }

  Future<CustomerUpstoxStatus> disconnect() async =>
      CustomerUpstoxStatus.fromJson(await api.postJson('$_root/disconnect'));

  Future<Object?> funds() => _read('funds');
  Future<Object?> holdings() => _read('holdings');
  Future<Object?> positions() => _read('positions');
  Future<Object?> orders() => _read('orders');
  Future<Object?> registeredIps() => _read('registered-ips');

  Future<Object?> _read(String endpoint) async {
    final raw = await api.getJson('$_root/$endpoint');
    if (raw is! Map<String, dynamic> ||
        raw['provider'] != 'UPSTOX' || !raw.containsKey('data')) {
      throw const FormatException('Invalid Upstox account data');
    }
    return raw['data'];
  }
}
