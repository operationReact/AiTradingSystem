import 'dart:convert';

import 'package:http/http.dart' as http;

import '../domain/health_snapshot.dart';
import 'operator_session.dart';

sealed class HealthFailure implements Exception {
  const HealthFailure(this.code, this.correlationId);
  final String code;
  final String? correlationId;
}

class AuthenticationRequired extends HealthFailure {
  const AuthenticationRequired() : super('AUTHENTICATION_REQUIRED', null);
}

class HealthHttpFailure extends HealthFailure {
  const HealthHttpFailure(super.code, super.correlationId, this.statusCode);
  final int statusCode;
}

class HealthTransportFailure extends HealthFailure {
  const HealthTransportFailure(super.code, super.correlationId);
}

abstract interface class HealthRepository {
  Future<HealthSnapshot> fetch();
}

class HttpHealthRepository implements HealthRepository {
  HttpHealthRepository({
    required this.session,
    http.Client? client,
    Uri? baseUri,
  })  : client = client ?? http.Client(),
        baseUri = baseUri ?? Uri.parse(const String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8080'));

  final OperatorSession session;
  final http.Client client;
  final Uri baseUri;

  @override
  Future<HealthSnapshot> fetch() async {
    final key = session.operatorKey;
    if (key == null || key.isEmpty) throw const AuthenticationRequired();
    final uri = baseUri.resolve('/api/operations/health');
    try {
      final response = await client.get(uri, headers: {'X-API-Key': key}).timeout(const Duration(seconds: 10));
      final correlation = response.headers['x-correlation-id'];
      if (response.statusCode == 401 || response.statusCode == 403) {
        throw HealthHttpFailure('AUTHENTICATION_REJECTED', correlation, response.statusCode);
      }
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw HealthHttpFailure('HEALTH_READ_FAILED', correlation, response.statusCode);
      }
      final decoded = jsonDecode(response.body);
      if (decoded is! Map<String, dynamic>) {
        throw HealthTransportFailure('INVALID_HEALTH_RESPONSE', correlation);
      }
      return HealthSnapshot.fromJson(decoded);
    } on HealthFailure {
      rethrow;
    } catch (_) {
      throw const HealthTransportFailure('NETWORK_OR_PARSE_FAILURE', null);
    }
  }
}
