import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_upstox_repository.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_upstox_page.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_upstox_symbol.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _FakeUpstox extends Fake implements HttpCustomerUpstoxRepository {
  bool linked = false;
  bool enabled = true;
  int startCalls = 0;
  int ownerConnectCalls = 0;
  bool ownerConfigured = false;
  bool ownerRejects = false;
  String ownerRejectCode = 'UPSTOX_TOTP_AUTH_REJECTED';
  bool statusFails = false;
  String? lastAuthError;
  Object? holdingsResponse;

  @override
  Future<bool> configuredLoginAvailable() async => ownerConfigured;

  @override
  Future<CustomerUpstoxStatus> connectConfigured() async {
    ownerConnectCalls++;
    if (ownerRejects) {
      throw ApiHttpFailure(ownerRejectCode, null, 502);
    }
    linked = true;
    return status();
  }


  @override
  Future<CustomerUpstoxStatus> status() async {
    if (statusFails) throw StateError('unavailable backend');
    return CustomerUpstoxStatus(
    available: enabled,
    connected: linked,
    accountMasked: linked ? '***456' : null,
    expiresAt: linked ? DateTime.utc(2026, 11, 8, 3, 30) : null,
    orderExecutionEnabled: false,
    lastAuthError: lastAuthError,
    );
  }

  @override
  Future<Uri> startAuthorization() async {
    startCalls++;
    return Uri.parse(
      'https://api.upstox.com/v2/login/authorization/dialog'
      '?response_type=code&client_id=app&redirect_uri=https%3A%2F%2Fexample.com'
      '&state=secure-test-state',
    );
  }

  @override
  Future<CustomerUpstoxStatus> disconnect() async {
    linked = false;
    return status();
  }

  @override
  Future<Object?> funds() async => {
    'equity': {'available_margin': 1250.50, 'used_margin': 0},
  };

  @override
  Future<Object?> holdings() async => holdingsResponse ?? [
    {'trading_symbol': 'SBIN', 'quantity': 2},
  ];

  @override
  Future<Object?> positions() async => [];

  @override
  Future<Object?> orders() async => [];

  @override
  Future<Object?> registeredIps() async => {'primary_ip': '192.0.2.1'};
}

void main() {
  for (final width in [320.0, 390.0]) {
    testWidgets('Upstox login uses external broker URL at ${width.toInt()}dp',
        (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = Size(width, 844);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final repo = _FakeUpstox();
      Uri? opened;
      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: MintTickUpstoxPage(
          repository: repo,
          launchAuthentication: (uri) async {
            opened = uri;
            return true;
          },
        ),
      ));
      await tester.pumpAndSettle();
      // A locally rendered mark remains sharp on different device densities.
      expect(find.byType(UpstoxSymbol), findsWidgets);
      expect(find.byKey(const ValueKey('upstox-start-oauth')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('upstox-start-oauth')));
      await tester.pumpAndSettle();
      expect(repo.startCalls, 1);
      expect(opened?.host, 'api.upstox.com');
      expect(find.textContaining('Complete authentication on Upstox'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('returning from broker OAuth refreshes account without another tap',
      (tester) async {
    final repo = _FakeUpstox();
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickUpstoxPage(
        repository: repo,
        launchAuthentication: (_) async => true,
      ),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('upstox-start-oauth')));
    await tester.pumpAndSettle();
    repo.linked = true; // Represents successful server-side callback.
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(find.text('CONNECTED'), findsOneWidget);
    expect(repo.startCalls, 1); // Never launch OAuth a second time.
  });

  test('safe OAuth callback status decodes without broker credentials', () {
    final status = CustomerUpstoxStatus.fromJson({
      'broker': 'UPSTOX', 'available': true, 'connected': false,
      'accountMasked': null, 'expiresAt': null, 'orderExecutionEnabled': false,
      'lastAuthError': 'UPSTOX_OAUTH_APP_CREDENTIALS_INVALID',
    });
    expect(status.connected, isFalse);
    expect(status.lastAuthError, 'UPSTOX_OAUTH_APP_CREDENTIALS_INVALID');
    expect(CustomerUpstoxStatus.fromJson({
      'broker': 'UPSTOX', 'available': true, 'connected': false,
      'orderExecutionEnabled': false,
    }).lastAuthError, isNull);
  });

  for (final (code, explanation) in [
    ('UPSTOX_AUTH_CODE_INVALID', 'authorization code expired'),
    ('UPSTOX_OAUTH_APP_CREDENTIALS_INVALID', 'API key or API secret'),
    ('UPSTOX_OAUTH_REDIRECT_REJECTED', 'rejected the callback URL'),
    ('UPSTOX_PROFILE_REJECTED', 'active account profile'),
    ('UPSTOX_ACCOUNT_SEGMENTS_INACTIVE', 'no active trading segments'),
    ('UPSTOX_AUTH_DECLINED', 'cancelled or declined'),
  ]) {
    testWidgets('failed OAuth callback $code has a next step', (tester) async {
      final repo = _FakeUpstox()..lastAuthError = code;
      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: MintTickUpstoxPage(repository: repo),
      ));
      await tester.pumpAndSettle();
      expect(find.textContaining(explanation), findsOneWidget);
      expect(find.text('NOT CONNECTED'), findsOneWidget);
      expect(find.byKey(const ValueKey('upstox-start-oauth')), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('connected Upstox account displays real provider read data',
      (tester) async {
    final repo = _FakeUpstox()..linked = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickUpstoxPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    expect(find.text('CONNECTED'), findsOneWidget);
    expect(find.textContaining('***456'), findsOneWidget);
    await tester.tap(find.text('Funds'));
    await tester.pumpAndSettle();
    expect(find.text('Available margin'), findsOneWidget);
    expect(find.text('₹1250.5'), findsOneWidget);
    expect(find.textContaining('Real-money order execution disabled'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('broker holdings show current value, invested capital and P&L '
      'on a narrow device', (tester) async {
    tester.view.physicalSize = const Size(320, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repo = _FakeUpstox()
      ..linked = true
      ..holdingsResponse = [
        {
          'trading_symbol': 'SKPSEC',
          'quantity': 3,
          'last_price': 120.0,
          'average_price': 100.0,
          'pnl': 60.0,
        },
      ];
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickUpstoxPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Holdings'));
    await tester.pumpAndSettle();
    expect(find.text('Current value'), findsOneWidget);
    expect(find.text('Invested'), findsOneWidget);
    expect(find.text('Total P&L'), findsOneWidget);
    expect(find.text('₹360.00'), findsWidgets);
    expect(find.text('₹300.00'), findsOneWidget);
    expect(find.text('₹60.00'), findsOneWidget);
    expect(find.textContaining('Total return: +20.00%'), findsOneWidget);
    expect(find.text('SKPSEC'), findsOneWidget);
    expect(find.textContaining('Qty 3'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('holdings with missing last price never invent rupee value',
      (tester) async {
    final repo = _FakeUpstox()
      ..linked = true
      ..holdingsResponse = [
        {'trading_symbol': 'SKPSEC', 'quantity': 17,
         'average_price': 270.0},
      ];
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickUpstoxPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Holdings'));
    await tester.pumpAndSettle();
    expect(find.text('Current value'), findsOneWidget);
    expect(find.text('₹4,590.00'), findsOneWidget);
    expect(find.text('SKPSEC'), findsOneWidget);
    expect(find.textContaining('missing totals are shown as —'), findsOneWidget);
    expect(find.text('₹0.00'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('Upstox totals use broker P&L even if price-change arithmetic '
      'would be different', (tester) async {
    final repo = _FakeUpstox()
      ..linked = true
      ..holdingsResponse = [
        {'trading_symbol': 'SKPSEC', 'quantity': 2,
         'average_price': 100.0, 'last_price': 90.0, 'pnl': -21.5},
      ];
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickUpstoxPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Holdings'));
    await tester.pumpAndSettle();
    expect(find.text('₹180.00'), findsWidgets);
    expect(find.text('₹200.00'), findsOneWidget);
    expect(find.text('-₹21.50'), findsOneWidget);
    expect(find.textContaining('Total return: -10.75%'), findsOneWidget);
  });

  testWidgets('bound owner can connect using protected TOTP without any credential field',
      (tester) async {
    final repo = _FakeUpstox()..ownerConfigured = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickUpstoxPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('upstox-connect-saved-owner')),
      findsOneWidget);
    expect(find.text('Use official Upstox login instead'), findsOneWidget);
    expect(find.textContaining('TOTP seed'), findsNothing);
    await tester.tap(find.byKey(const ValueKey('upstox-connect-saved-owner')));
    await tester.pumpAndSettle();
    expect(repo.ownerConnectCalls, 1);
    expect(repo.startCalls, 0);
    expect(find.text('CONNECTED'), findsOneWidget);
    expect(find.textContaining('Real-money order execution disabled'),
      findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  for (final (code, expected) in [
    ('UPSTOX_TOTP_APP_NOT_ELIGIBLE', 'not eligible for PIN + TOTP'),
    ('UPSTOX_TOTP_APP_KEY_INVALID', 'developer API key was rejected'),
    ('UPSTOX_TOTP_CREDENTIALS_INVALID', 'saved PIN or TOTP'),
    ('UPSTOX_TOTP_NOT_ENABLED', 'TOTP is not enabled'),
    ('UPSTOX_TOTP_RATE_LIMITED', 'rate limiting saved login'),
  ]) {
    testWidgets('saved Upstox failure $code is actionable', (tester) async {
      final repo = _FakeUpstox()
        ..ownerConfigured = true
        ..ownerRejects = true
        ..ownerRejectCode = code;
      await tester.pumpWidget(MaterialApp(
        theme: buildTheme(Brightness.dark),
        home: MintTickUpstoxPage(repository: repo),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('upstox-connect-saved-owner')));
      await tester.pumpAndSettle();
      expect(find.textContaining(expected), findsOneWidget);
      expect(find.text('NOT CONNECTED'), findsOneWidget);
      expect(find.byKey(const ValueKey('upstox-start-oauth')), findsOneWidget);
    });
  }

  testWidgets('nonowner does not see protected automatic login action',
      (tester) async {
    final repo = _FakeUpstox()..ownerConfigured = false;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickUpstoxPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('upstox-connect-saved-owner')),
      findsNothing);
    expect(find.byKey(const ValueKey('upstox-start-oauth')), findsOneWidget);
    expect(repo.ownerConnectCalls, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets('rejected owner TOTP login explains eligibility and retains OAuth',
      (tester) async {
    final repo = _FakeUpstox()
      ..ownerConfigured = true
      ..ownerRejects = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickUpstoxPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('upstox-connect-saved-owner')));
    await tester.pumpAndSettle();
    expect(repo.ownerConnectCalls, 1);
    expect(find.text('NOT CONNECTED'), findsOneWidget);
    expect(find.textContaining('individual-app PIN + TOTP'), findsOneWidget);
    expect(find.byKey(const ValueKey('upstox-start-oauth')), findsOneWidget);
    expect(repo.startCalls, 0);
    expect(tester.takeException(), isNull);
  });

  testWidgets('failed refresh removes stale connected state and owner access',
      (tester) async {
    final repo = _FakeUpstox()..linked = true;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickUpstoxPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    expect(find.text('CONNECTED'), findsOneWidget);
    repo.statusFails = true;
    await tester.tap(find.byKey(const ValueKey('upstox-refresh-status')));
    await tester.pumpAndSettle();
    expect(find.text('CONNECTED'), findsNothing);
    expect(find.textContaining('Unable to verify Upstox connection'),
      findsOneWidget);
    expect(find.byKey(const ValueKey('upstox-connect-saved-owner')),
      findsNothing);
    expect(tester.widget<FilledButton>(
        find.byKey(const ValueKey('upstox-start-oauth'))).onPressed, isNull);
    expect(tester.takeException(), isNull);
  });

  testWidgets('unconfigured Upstox app disables OAuth instead of inventing a login',
      (tester) async {
    final repo = _FakeUpstox()..enabled = false;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickUpstoxPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(
      find.byKey(const ValueKey('upstox-start-oauth'))).onPressed, isNull);
    expect(find.textContaining('developer app configured'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
