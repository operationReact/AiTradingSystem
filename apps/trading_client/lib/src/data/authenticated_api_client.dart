import 'dart:convert';

import 'package:http/http.dart' as http;

import 'operator_session.dart';

sealed class ApiFailure implements Exception {
  const ApiFailure(this.code, this.correlationId, {this.statusCode});

  final String code;
  final String? correlationId;
  final int? statusCode;
}

class ApiAuthenticationRequired extends ApiFailure {
  const ApiAuthenticationRequired() : super('AUTHENTICATION_REQUIRED', null);
}

class ApiHttpFailure extends ApiFailure {
  const ApiHttpFailure(super.code, super.correlationId, int statusCode)
      : super(statusCode: statusCode);
}

class ApiTransportFailure extends ApiFailure {
  const ApiTransportFailure(super.code, super.correlationId);
}

class AuthenticatedApiClient {
  AuthenticatedApiClient({
    required this.session,
    http.Client? client,
    Uri? baseUri,
  })  : client = client ?? http.Client(),
        baseUri = baseUri ??
            Uri.parse(
              const String.fromEnvironment(
                'API_BASE_URL',
                defaultValue: 'http://localhost:8080',
              ),
            );

  final OperatorSession session;
  final http.Client client;
  final Uri baseUri;

  Future<Object?> getJson(
    String path, {
    Map<String, String?> query = const {},
    Map<String, String> headers = const {},
    Duration timeout = const Duration(seconds: 10),
  }) =>
      _send(
        method: 'GET',
        path: path,
        query: query,
        extraHeaders: headers,
        timeout: timeout,
      );

  Future<Object?> postJson(
    String path, {
    Object? body,
    Map<String, String?> query = const {},
    Map<String, String> headers = const {},
    Duration timeout = const Duration(seconds: 20),
  }) =>
      _send(
        method: 'POST',
        path: path,
        query: query,
        extraHeaders: headers,
        body: body,
        timeout: timeout,
      );

  Future<Object?> putJson(
    String path, {
    Object? body,
    Map<String, String?> query = const {},
    Map<String, String> headers = const {},
    Duration timeout = const Duration(seconds: 20),
  }) =>
      _send(
        method: 'PUT',
        path: path,
        query: query,
        extraHeaders: headers,
        body: body,
        timeout: timeout,
      );

  Future<Object?> _send({
    required String method,
    required String path,
    required Map<String, String?> query,
    required Map<String, String> extraHeaders,
    Object? body,
    required Duration timeout,
  }) async {
    final key = session.operatorKey;
    if (key == null || key.isEmpty) throw const ApiAuthenticationRequired();

    final base = baseUri.resolve(path);
    final queryParameters = <String, String>{
      ...base.queryParameters,
      for (final entry in query.entries)
        if (entry.value != null && entry.value!.isNotEmpty) entry.key: entry.value!,
    };
    final uri = base.replace(queryParameters: queryParameters.isEmpty ? null : queryParameters);
    final requestHeaders = <String, String>{
      ...extraHeaders,
      // Authentication and representation headers are owned by this client and
      // cannot be replaced by feature repositories.
      'X-API-Key': key,
      'Accept': 'application/json',
      if (body != null) 'Content-Type': 'application/json',
    };

    try {
      final response = switch (method) {
        'GET' => await client.get(uri, headers: requestHeaders).timeout(timeout),
        'POST' => await client
            .post(
              uri,
              headers: requestHeaders,
              body: body == null ? null : jsonEncode(body),
            )
            .timeout(timeout),
        'PUT' => await client
            .put(
              uri,
              headers: requestHeaders,
              body: body == null ? null : jsonEncode(body),
            )
            .timeout(timeout),
        _ => throw ArgumentError.value(method, 'method', 'Unsupported HTTP method'),
      };
      final correlation = response.headers['x-correlation-id'];

      if (response.statusCode == 401 || response.statusCode == 403) {
        throw ApiHttpFailure(
          'AUTHENTICATION_REJECTED',
          correlation,
          response.statusCode,
        );
      }
      if (response.statusCode < 200 || response.statusCode >= 300) {
        String code = 'API_REQUEST_FAILED';
        try {
          final decoded = jsonDecode(response.body);
          if (decoded is Map<String, dynamic>) {
            if (decoded['code'] != null) {
              code = '${decoded['code']}';
            } else if (decoded['title'] != null) {
              code = '${decoded['title']}';
            }
          }
        } catch (_) {
          // Keep the generic code when an error response is not JSON.
        }
        throw ApiHttpFailure(code, correlation, response.statusCode);
      }
      if (response.body.trim().isEmpty) return null;
      return jsonDecode(response.body);
    } on ApiFailure {
      rethrow;
    } catch (_) {
      throw const ApiTransportFailure('NETWORK_OR_PARSE_FAILURE', null);
    }
  }
}
