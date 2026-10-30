import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/data/health_repository.dart';
import 'package:trading_client/src/data/operator_session.dart';

const connectedApiBaseUrl = String.fromEnvironment('API_BASE_URL');
const connectedApiKey = String.fromEnvironment('CONNECTED_API_KEY');

Future<void> waitForFinder(
  WidgetTester tester,
  Finder finder, {
  Duration timeout = const Duration(seconds: 60),
}) async {
  final deadline = DateTime.now().add(timeout);
  while (finder.evaluate().isEmpty && DateTime.now().isBefore(deadline)) {
    await tester.pump(const Duration(milliseconds: 500));
  }
  expect(finder, findsOneWidget);
}

Future<void> openOperationsHealth(WidgetTester tester, OperatorSession session) async {
  await tester.pumpWidget(TradingApp(
    repositoryFactory: () => HttpHealthRepository(session: session, baseUri: Uri.parse(connectedApiBaseUrl)),
    session: session,
  ));
  await tester.pumpAndSettle();

  if (find.byKey(const ValueKey('operator-key-field')).evaluate().isEmpty) {
    final navigator = tester.state<NavigatorState>(find.byType(Navigator).first);
    navigator.pushNamed('/operations/health');
    await tester.pumpAndSettle();
  }

  expect(find.byKey(const ValueKey('operator-key-field')), findsOneWidget);
}

Future<void> connectHealth(WidgetTester tester, String key) async {
  final operatorField = find.byKey(const ValueKey('operator-key-field'));
  final connectButton = find.byKey(const ValueKey('connect-health'));
  await tester.ensureVisible(operatorField);
  await tester.enterText(operatorField, key);
  await tester.ensureVisible(connectButton);
  await tester.pumpAndSettle();
  await tester.tap(connectButton);
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('connected native runtime shows degraded/UNKNOWN-safe real backend evidence and survives theme/style rebuilds', (tester) async {
    expect(connectedApiBaseUrl, isNotEmpty);
    expect(connectedApiKey, isNotEmpty);
    final session = OperatorSession();
    await openOperationsHealth(tester, session);

    await connectHealth(tester, connectedApiKey);
    await waitForFinder(tester, find.byKey(const ValueKey('health-summary')));

    expect(find.text('PAPER'), findsWidgets);
    expect(find.text('UNAVAILABLE'), findsWidgets);

    final styleControl = find.byKey(const ValueKey('design-style-control'));
    await tester.ensureVisible(styleControl);
    await tester.tap(find.descendant(of: styleControl, matching: find.text('Clean')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('health-summary')), findsOneWidget);

    final themeControl = find.byKey(const ValueKey('theme-mode-control'));
    await tester.ensureVisible(themeControl);
    await tester.tap(themeControl);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Dark').last);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('health-summary')), findsOneWidget);
  });

  testWidgets('connected native runtime renders authentication rejection without leaking credential text', (tester) async {
    final session = OperatorSession();
    await openOperationsHealth(tester, session);
    final badKey = '${connectedApiKey}wrong';
    final operatorField = find.byKey(const ValueKey('operator-key-field'));
    await connectHealth(tester, badKey);
    await waitForFinder(tester, find.byKey(const ValueKey('health-error')));
    expect(find.text('AUTHENTICATION_REJECTED'), findsOneWidget);

    final allCredentialText = find.textContaining(badKey);
    final credentialInsideInput = find.descendant(of: operatorField, matching: allCredentialText);
    expect(allCredentialText, findsOneWidget);
    expect(credentialInsideInput, findsOneWidget);
  });
}
