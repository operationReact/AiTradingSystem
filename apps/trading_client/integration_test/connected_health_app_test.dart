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

  final operatorField = find.byKey(const ValueKey('operator-key-field'));
  expect(operatorField, findsOneWidget);
  await tester.ensureVisible(operatorField);
  await tester.pumpAndSettle();
}

Future<void> connectAndWait(
  WidgetTester tester,
  String key,
  Finder expectedState,
) async {
  final operatorField = find.byKey(const ValueKey('operator-key-field'));
  final connect = find.byKey(const ValueKey('connect-health'));
  await tester.ensureVisible(operatorField);
  await tester.enterText(operatorField, key);
  await tester.ensureVisible(connect);
  await tester.pumpAndSettle();
  await tester.tap(connect);
  await tester.pump();
  await waitForFinder(tester, expectedState);
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('connected native runtime shows degraded/UNKNOWN-safe real backend evidence and survives theme/style rebuilds', (tester) async {
    expect(connectedApiBaseUrl, isNotEmpty);
    expect(connectedApiKey, isNotEmpty);
    final session = OperatorSession();
    await openOperationsHealth(tester, session);

    await connectAndWait(
      tester,
      connectedApiKey,
      find.byKey(const ValueKey('health-summary')),
    );

    expect(find.text('PAPER'), findsWidgets);
    expect(find.text('UNAVAILABLE'), findsWidgets);

    final globalStyleControl = find.byKey(const ValueKey('global-design-style-control'));
    final globalClean = find.descendant(of: globalStyleControl, matching: find.text('Clean'));
    expect(globalClean, findsOneWidget);
    await tester.ensureVisible(globalClean);
    await tester.tap(globalClean);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('health-summary')), findsOneWidget);

    final globalThemeControl = find.byKey(const ValueKey('global-theme-mode-control'));
    expect(globalThemeControl, findsOneWidget);
    await tester.ensureVisible(globalThemeControl);
    await tester.tap(globalThemeControl);
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
    await connectAndWait(
      tester,
      badKey,
      find.byKey(const ValueKey('health-error')),
    );
    expect(find.text('AUTHENTICATION_REJECTED'), findsOneWidget);

    // The obscured input necessarily owns the entered value in its EditableText.
    // Prove that it is the only widget containing the credential, so the value is
    // not echoed into error text, diagnostics, or any other rendered surface.
    final allCredentialText = find.textContaining(badKey);
    final credentialInsideInput = find.descendant(of: operatorField, matching: allCredentialText);
    expect(allCredentialText, findsOneWidget);
    expect(credentialInsideInput, findsOneWidget);
  });
}
