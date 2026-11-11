import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_upstox_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_upstox_page.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_upstox_symbol.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _FakeUpstox extends Fake implements HttpCustomerUpstoxRepository {
  bool linked = false;
  bool enabled = true;
  int startCalls = 0;

  @override
  Future<CustomerUpstoxStatus> status() async => CustomerUpstoxStatus(
    available: enabled,
    connected: linked,
    accountMasked: linked ? '***456' : null,
    expiresAt: linked ? DateTime.utc(2026, 11, 8, 3, 30) : null,
    orderExecutionEnabled: false,
  );

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
  Future<Object?> holdings() async => [
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
