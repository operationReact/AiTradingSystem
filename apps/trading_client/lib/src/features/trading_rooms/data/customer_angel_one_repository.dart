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
