import '../../../data/authenticated_api_client.dart';

/// Customer-specific Angel One SmartAPI connection. The secret inputs are
/// forwarded over authenticated HTTPS once and are never cached in Flutter.
class CustomerAngelOneConnection {
  const CustomerAngelOneConnection({
    required this.connected,
    required this.clientCodeMasked,
    required this.expiresAt,
    required this.orderExecutionEnabled,
  });
  final bool connected;
  final String? clientCodeMasked;
  final DateTime? expiresAt;
  final bool orderExecutionEnabled;
}

class HttpCustomerAngelOneRepository {
  HttpCustomerAngelOneRepository(this.api);
  final AuthenticatedApiClient api;
  static const _root = '/api/customer/brokers/angel-one';

  Future<CustomerAngelOneConnection> status() async =>
      _status(await api.getJson('$_root/status'));

  /// An owner-specific capability, not a shared login for MintTick users.
  /// The API returns only an availability flag; SSM secrets never leave AWS.
  Future<bool> configuredLoginAvailable() async {
    final payload = await api.getJson('$_root/configured-login');
    if (payload is! Map<String, dynamic> || payload['available'] is! bool) {
      throw const FormatException('Invalid configured broker availability');
    }
    return payload['available'] as bool;
  }

  /// The caller's own Cognito subject, used only for explicit AWS owner setup.
  /// This is not a broker secret or MintTick's permanent app-user ID.
  Future<String> ownerLinkReference() async {
    final data = await api.getJson('$_root/owner-link-reference');
    if (data is! Map<String, dynamic> ||
        data['cognitoSubject'] is! String) {
      throw const FormatException('Invalid customer identity reference');
    }
    final subject = (data['cognitoSubject'] as String).trim();
    if (!RegExp(r'^[A-Za-z0-9_-]{10,128}$').hasMatch(subject)) {
      throw const FormatException('Invalid customer identity reference');
    }
    return subject;
  }

  Future<CustomerAngelOneConnection> connectConfigured() async =>
      _status(await api.postJson('$_root/connect-configured'));

  Future<CustomerAngelOneConnection> connect({
    required String apiKey,
    required String clientCode,
    required String pin,
    required String totp,
  }) async {
    // Do not create any local preferences or storage records for secrets.
    return _status(await api.postJson('$_root/connect', body: {
      'apiKey': apiKey,
      'clientCode': clientCode,
      'pin': pin,
      'totp': totp,
    }));
  }

  Future<CustomerAngelOneConnection> disconnect() async =>
      _status(await api.postJson('$_root/disconnect'));

  Future<Object?> funds() async => _unwrap(await api.getJson('$_root/funds'));
  Future<Object?> holdings() async => _unwrap(await api.getJson('$_root/holdings'));
  Future<Object?> positions() async => _unwrap(await api.getJson('$_root/positions'));
  Future<Object?> orders() async => _unwrap(await api.getJson('$_root/orders'));

  static CustomerAngelOneConnection _status(Object? data) {
    if (data is! Map<String, dynamic> ||
        data['connected'] is! bool ||
        data['orderExecutionEnabled'] is! bool ||
        data['broker'] != 'ANGEL_ONE') {
      throw const FormatException('Invalid broker connection status');
    }
    final expiry = data['expiresAt'];
    return CustomerAngelOneConnection(
      connected: data['connected'] as bool,
      clientCodeMasked: data['clientCodeMasked'] as String?,
      expiresAt: expiry is String ? DateTime.tryParse(expiry) : null,
      orderExecutionEnabled: data['orderExecutionEnabled'] as bool,
    );
  }

  static Object? _unwrap(Object? response) {
    if (response is! Map<String, dynamic> ||
        response['provider'] != 'ANGEL_ONE' ||
        !response.containsKey('data')) {
      throw const FormatException('Invalid broker account response');
    }
    return response['data'];
  }
}

