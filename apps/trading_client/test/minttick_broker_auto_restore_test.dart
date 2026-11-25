import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_angel_one_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_upstox_repository.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_broker_auto_restore.dart';

class _Angel extends Fake implements HttpCustomerAngelOneRepository {
  bool connected = false;
  bool owner = false;
  bool throwOnAvailability = false;
  int statusCalls = 0;
  int authorizationChecks = 0;
  int connectCalls = 0;

  @override
  Future<CustomerAngelOneConnection> status() async {
    statusCalls++;
    return CustomerAngelOneConnection(
      connected: connected,
      clientCodeMasked: connected ? '***ABC' : null,
      expiresAt: null, orderExecutionEnabled: false,
    );
  }

  @override
  Future<bool> configuredLoginAvailable() async {
    authorizationChecks++;
    if (throwOnAvailability) throw StateError('configuration unavailable');
    return owner;
  }

  @override
  Future<CustomerAngelOneConnection> connectConfigured() async {
    connectCalls++;
    connected = true;
    return status();
  }
}

class _Upstox extends Fake implements HttpCustomerUpstoxRepository {
  bool connected = false;
  bool available = true;
  bool ownerAutoAvailable = false;
  int statusCalls = 0;
  int ownerChecks = 0;
  int ownerConnections = 0;
  int oauthStarts = 0;

  @override
  Future<CustomerUpstoxStatus> status() async {
    statusCalls++;
    return CustomerUpstoxStatus(
      available: available, connected: connected,
      accountMasked: connected ? '***123' : null,
      expiresAt: null, orderExecutionEnabled: false,
    );
  }

  @override
  Future<bool> configuredLoginAvailable() async {
    ownerChecks++;
    return ownerAutoAvailable;
  }

  @override
  Future<CustomerUpstoxStatus> connectConfigured() async {
    ownerConnections++;
    connected = true;
    return status();
  }

  @override
  Future<Uri> startAuthorization() async {
    oauthStarts++;
    throw StateError('Restoration must NEVER auto-start broker OAuth');
  }
}

void main() {
  test('verified owner restores Angel One without credentials in client', () async {
    final angel = _Angel()..owner = true;
    final upstox = _Upstox()..connected = true;
    final restore = CustomerBrokerAutoRestore(angelOne: angel, upstox: upstox);
    final result = await restore.restore();
    expect(result.angelOneConnected, isTrue);
    expect(result.upstoxConnected, isTrue);
    expect(result.upstoxAuthorizationRequired, isFalse);
    expect(angel.connectCalls, 1);
    expect(upstox.oauthStarts, 0);
  });

  test('nonowner never receives server-configured Angel One login', () async {
    final angel = _Angel();
    final upstox = _Upstox();
    final result = await CustomerBrokerAutoRestore(
      angelOne: angel, upstox: upstox,
    ).restore();
    expect(angel.connectCalls, 0);
    expect(result.angelOneConnected, isFalse);
    expect(result.upstoxAuthorizationRequired, isTrue);
    expect(upstox.oauthStarts, 0);
  });

  test('already connected owner does not reauthenticate and invalidate session',
      () async {
    final angel = _Angel()..owner = true..connected = true;
    final upstox = _Upstox()..connected = true;
    final result = await CustomerBrokerAutoRestore(
      angelOne: angel, upstox: upstox,
    ).restore();
    expect(result.angelOneConnected, isTrue);
    expect(angel.authorizationChecks, 0);
    expect(angel.connectCalls, 0);
  });

  test('broker errors do not prevent customer Home from opening', () async {
    final angel = _Angel()..throwOnAvailability = true;
    final upstox = _Upstox()..available = false;
    final result = await CustomerBrokerAutoRestore(
      angelOne: angel, upstox: upstox,
    ).restore();
    expect(result.angelOneConnected, isFalse);
    expect(result.upstoxAuthorizationRequired, isFalse);
    expect(angel.connectCalls, 0);
  });

  test('individual Upstox owner auto-restores without browser OAuth', () async {
    final upstox = _Upstox()..ownerAutoAvailable = true;
    final result = await CustomerBrokerAutoRestore(
      angelOne: _Angel(), upstox: upstox,
    ).restore();
    expect(result.upstoxConnected, isTrue);
    expect(result.upstoxAuthorizationRequired, isFalse);
    expect(upstox.ownerConnections, 1);
    expect(upstox.oauthStarts, 0);
  });

  test('valid Upstox session never regenerates or revokes broker token', () async {
    final upstox = _Upstox()
      ..ownerAutoAvailable = true
      ..connected = true;
    final result = await CustomerBrokerAutoRestore(
      angelOne: _Angel(), upstox: upstox,
    ).restore();
    expect(result.upstoxConnected, isTrue);
    expect(upstox.ownerChecks, 0);
    expect(upstox.ownerConnections, 0);
    expect(upstox.oauthStarts, 0);
  });

  test('Upstox expired grant prompts user, never silently logs in', () async {
    final result = await CustomerBrokerAutoRestore(
      angelOne: _Angel(), upstox: _Upstox(),
    ).restore();
    expect(result.upstoxConnected, isFalse);
    expect(result.upstoxAuthorizationRequired, isTrue);
  });
}
