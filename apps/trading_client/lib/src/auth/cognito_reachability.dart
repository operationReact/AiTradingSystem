import 'dart:convert';

import 'package:http/http.dart' as http;

/// Checks only the public Cognito signing-key document. Never sends a
/// username, password, AWS token, broker credential, or client secret.
/// A successful check verifies basic browser connectivity, NOT sign-in.
abstract final class MintTickCognitoReachability {
  static Future<bool> check({
    http.Client? client,
    String? regionOverride,
    String? poolIdOverride,
  }) async {
    final region = regionOverride ??
        const String.fromEnvironment(
            'MINTTICK_COGNITO_REGION', defaultValue: 'ap-south-1');
    final poolId = poolIdOverride ??
        const String.fromEnvironment('MINTTICK_COGNITO_USER_POOL_ID');

    if (!RegExp(r'^[a-z]{2}-[a-z]+-\d+$').hasMatch(region) ||
        !RegExp(r'^[a-z]{2}-[a-z]+-\d+_[A-Za-z0-9]+$').hasMatch(poolId) ||
        !poolId.startsWith('${region}_')) {
      return false;
    }

    final httpClient = client ?? http.Client();
    try {
      final url = Uri.https(
        'cognito-idp.$region.amazonaws.com',
        '/$poolId/.well-known/jwks.json',
      );
      final response = await httpClient.get(
        url,
        headers: const {'Accept': 'application/json'},
      ).timeout(const Duration(seconds: 6));
      if (response.statusCode != 200) return false;
      final payload = jsonDecode(response.body);
      return payload is Map &&
          payload['keys'] is List &&
          (payload['keys'] as List).isNotEmpty;
    } catch (_) {
      // Do not log HTTP exceptions; they can contain sensitive proxy details.
      return false;
    } finally {
      if (client == null) httpClient.close();
    }
  }
}
