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

class DashboardSummary {
  const DashboardSummary({
    required this.mode,
    required this.liveTradingEnabled,
    required this.newOrdersHalted,
    required this.capital,
    required this.availableCash,
    required this.investedCapital,
    required this.marketValue,
    required this.equity,
    required this.realizedPnl,
    required this.unrealizedPnl,
    required this.dailyPnl,
    required this.openPositions,
    required this.exposurePercent,
    required this.cashReconciled,
    required this.markPriceSource,
    required this.systemStatus,
    required this.asOf,
  });

  final String mode;
  final bool liveTradingEnabled;
  final bool newOrdersHalted;
  final num capital;
  final num availableCash;
  final num investedCapital;
  final num marketValue;
  final num equity;
  final num realizedPnl;
  final num unrealizedPnl;
  final num dailyPnl;
  final int openPositions;
  final num exposurePercent;
  final bool cashReconciled;
  final String? markPriceSource;
  final String systemStatus;
  final DateTime? asOf;

  factory DashboardSummary.fromJson(Map<String, dynamic> json) {
    num number(String key) => json[key] is num ? json[key] as num : num.tryParse('${json[key] ?? 0}') ?? 0;
    return DashboardSummary(
      mode: '${json['mode'] ?? 'UNKNOWN'}',
      liveTradingEnabled: json['liveTradingEnabled'] == true,
      newOrdersHalted: json['newOrdersHalted'] == true,
      capital: number('capital'),
      availableCash: number('availableCash'),
      investedCapital: number('investedCapital'),
      marketValue: number('marketValue'),
      equity: number('equity'),
      realizedPnl: number('realizedPnl'),
      unrealizedPnl: number('unrealizedPnl'),
      dailyPnl: number('dailyPnl'),
      openPositions: number('openPositions').toInt(),
      exposurePercent: number('exposurePercent'),
      cashReconciled: json['cashReconciled'] == true,
      markPriceSource: json['markPriceSource']?.toString(),
      systemStatus: '${json['systemStatus'] ?? 'UNKNOWN'}',
      asOf: DateTime.tryParse('${json['asOf'] ?? ''}'),
    );
  }
}

sealed class DashboardFailure implements Exception {
  const DashboardFailure(this.code, this.correlationId);
  final String code;
  final String? correlationId;
}

class DashboardAuthenticationRequired extends DashboardFailure {
  const DashboardAuthenticationRequired() : super('AUTHENTICATION_REQUIRED', null);
}

class DashboardHttpFailure extends DashboardFailure {
  const DashboardHttpFailure(super.code, super.correlationId, this.statusCode);
  final int statusCode;
}

class DashboardTransportFailure extends DashboardFailure {
  const DashboardTransportFailure(super.code, super.correlationId);
}

abstract interface class DashboardRepository {
  Future<DashboardSummary> fetchSummary();
}

class HttpDashboardRepository implements DashboardRepository {
  HttpDashboardRepository({
    required this.session,
    http.Client? client,
    Uri? baseUri,
  })  : client = client ?? http.Client(),
        baseUri = baseUri ?? Uri.parse(const String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8080'));

  final OperatorSession session;
  final http.Client client;
  final Uri baseUri;

  @override
  Future<DashboardSummary> fetchSummary() async {
    final key = session.operatorKey;
    if (key == null || key.isEmpty) throw const DashboardAuthenticationRequired();
    final uri = baseUri.resolve('/api/dashboard/summary');
    try {
      final response = await client.get(uri, headers: {'X-API-Key': key}).timeout(const Duration(seconds: 10));
      final correlation = response.headers['x-correlation-id'];
      if (response.statusCode == 401 || response.statusCode == 403) {
        throw DashboardHttpFailure('AUTHENTICATION_REJECTED', correlation, response.statusCode);
      }
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw DashboardHttpFailure('DASHBOARD_READ_FAILED', correlation, response.statusCode);
      }
      final decoded = jsonDecode(response.body);
      if (decoded is! Map<String, dynamic>) {
        throw DashboardTransportFailure('INVALID_DASHBOARD_RESPONSE', correlation);
      }
      return DashboardSummary.fromJson(decoded);
    } on DashboardFailure {
      rethrow;
    } catch (_) {
      throw const DashboardTransportFailure('NETWORK_OR_PARSE_FAILURE', null);
    }
  }
}
