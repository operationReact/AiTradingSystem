import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/trading_rooms/data/customer_angel_one_repository.dart';
import 'package:trading_client/src/features/trading_rooms/presentation/minttick_customer_broker_page.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _ServerConfiguredAngel extends Fake implements HttpCustomerAngelOneRepository {
  bool linked = false;
  bool available = true;
  int connects = 0;

  @override
  Future<bool> configuredLoginAvailable() async => available;

  @override
  Future<String> ownerLinkReference() async => 'owner-cognito-sub-12345';

  @override
  Future<CustomerAngelOneConnection> status() async => CustomerAngelOneConnection(
    connected: linked,
    clientCodeMasked: linked ? '***234' : null,
    expiresAt: null,
    orderExecutionEnabled: false,
  );

  @override
  Future<CustomerAngelOneConnection> connectConfigured() async {
    connects++;
    linked = true;
    return status();
  }

  @override
  Future<CustomerAngelOneConnection> disconnect() async {
    linked = false;
    return status();
  }
}

void main() {
  testWidgets('customer without owner binding can copy own Cognito reference',
      (tester) async {
    final repo = _ServerConfiguredAngel()..available = false;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: MintTickCustomerBrokerPage(repository: repo),
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('broker-copy-owner-reference')),
        findsOneWidget);
    expect(find.text('owner-cognito-sub-12345'), findsOneWidget);
    expect(find.byKey(const ValueKey('broker-connect-configured')), findsNothing);
    expect(repo.connects, 0);
  });

  for (final brightness in [Brightness.dark, Brightness.light]) {
    for (final width in [320.0, 390.0]) {
      testWidgets('preconfigured owner uses no input fields at ${width.toInt()}dp $brightness',
          (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = Size(width, 844);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final repository = _ServerConfiguredAngel();
        await tester.pumpWidget(MaterialApp(
          theme: buildTheme(brightness),
          home: MintTickCustomerBrokerPage(repository: repository),
        ));
        await tester.pumpAndSettle();
        final connect = find.byKey(const ValueKey('broker-connect-configured'));
        expect(connect, findsOneWidget);
        expect(find.byKey(const ValueKey('broker-api-key')), findsNothing);
        expect(find.byKey(const ValueKey('broker-pin')), findsNothing);
        expect(find.byKey(const ValueKey('broker-totp')), findsNothing);
        await tester.ensureVisible(connect);
        await tester.tap(connect);
        await tester.pumpAndSettle();
        expect(repository.connects, 1);
        expect(find.text('CONNECTED'), findsOneWidget);
        expect(find.textContaining('***234'), findsOneWidget);
        expect(tester.takeException(), isNull);
      });
    }
  }
}
