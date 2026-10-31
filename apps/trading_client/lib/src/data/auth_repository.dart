import 'dart:convert';

import 'package:http/http.dart' as http;

class AuthProviderConfig {
  const AuthProviderConfig({
    required this.enabled,
    required this.protocol,
    required this.providerName,
    required this.authorizationStartPath,
    required this.mfaReady,
    required this.status,
  });

  final bool enabled;
  final String protocol;
  final String providerName;
  final String? authorizationStartPath;
  final bool mfaReady;
  final String status;

  factory AuthProviderConfig.fromJson(Map<String, dynamic> json) => AuthProviderConfig(
        enabled: json['enabled'] == true,
        protocol: '${json['protocol'] ?? 'UNKNOWN'}',
        providerName: '${json['providerName'] ?? 'Approved identity provider'}',
        authorizationStartPath: json['authorizationStartPath'] is String
            ? json['authorizationStartPath'] as String
            : null,
        mfaReady: json['mfaReady'] == true,
        status: '${json['status'] ?? 'UNKNOWN'}',
      );
}

sealed class AuthConfigFailure implements Exception {
  const AuthConfigFailure();
}

class AuthConfigUnavailable extends AuthConfigFailure {
  const AuthConfigUnavailable();
}

abstract interface class AuthRepository {
  Future<AuthProviderConfig> fetchProviderConfig();
}

class HttpAuthRepository implements AuthRepository {
  HttpAuthRepository({http.Client? client, Uri? baseUri})
      : client = client ?? http.Client(),
        baseUri = baseUri ??
            Uri.parse(const String.fromEnvironment(
              'API_BASE_URL',
              defaultValue: 'http://localhost:8080',
            ));

  final http.Client client;
  final Uri baseUri;

  @override
  Future<AuthProviderConfig> fetchProviderConfig() async {
    try {
      final response = await client
          .get(baseUri.resolve('/api/auth/config'))
          .timeout(const Duration(seconds: 10));
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw const AuthConfigUnavailable();
      }
      final decoded = jsonDecode(response.body);
      if (decoded is! Map<String, dynamic>) {
        throw const AuthConfigUnavailable();
      }
      return AuthProviderConfig.fromJson(decoded);
    } on AuthConfigFailure {
      rethrow;
    } catch (_) {
      throw const AuthConfigUnavailable();
    }
  }
}
