import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:trading_client/src/app.dart';
import 'package:trading_client/src/core/session/operator_session.dart';
import 'package:trading_client/src/features/operations_health/data/health_repository.dart';

const connectedApiBaseUrl = String.fromEnvironment('API_BASE_URL');
const connectedApiKey = String.fromEnvironment('CONNECTED_API_KEY');

Future<void> pumpFrames(
  WidgetTester tester, {
  int frames = 12,
  Duration step = const Duration(milliseconds: 100),
}) async {
  for (var i = 0; i < frames; i++) {
    await tester.pump(step);
  }
}

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

Future<void> disposeConnectedApp(WidgetTester tester) async {
  // The production shell owns animated/global controls. Explicitly dispose the
  // mounted app after each native journey so iOS integration-test teardown does
  // not keep animation/timer work alive after assertions have completed.
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pump();
}

Future<void> openOperationsHealth(WidgetTester tester, OperatorSession session) async {
  await tester.pumpWidget(TradingApp(
    repositoryFactory: () => HttpHealthRepository(session: session, baseUri: Uri.parse(connectedApiBaseUrl)),
    session: session,
  ));
  await pumpFrames(tester);

  if (find.byKey(const ValueKey('operator-key-field')).evaluate().isEmpty) {
    final navigator = tester.state<NavigatorState>(find.byType(Navigator).first);
    navigator.pushNamed('/operations/health');
    await pumpFrames(tester);
  }

  final operatorField = find.byKey(const ValueKey('operator-key-field'));
  expect(operatorField, findsOneWidget);
  await tester.ensureVisible(operatorField);
  await pumpFrames(tester, frames: 4);
}

Future<void> connectAndWait(
  WidgetTester tester,
  String key,
  Finder expectedState,
) async {
  final operatorField = find.byKey(const ValueKey('operator-key-field'));
  await waitForFinder(tester, operatorField);
  await tester.ensureVisible(operatorField);
  await tester.enterText(operatorField, key);
  await pumpFrames(tester, frames: 2);

  // Entering the operator key can rebuild the native form. Resolve and wait for
  // the post-rebuild control before attempting to scroll or tap it.
  final connect = find.byKey(const ValueKey('connect-health'));
  await waitForFinder(tester, connect);
  await tester.ensureVisible(connect);
  await pumpFrames(tester, frames: 4);
  await tester.tap(connect);
  await tester.pump();
  await waitForFinder(tester, expectedState);
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('connected native runtime shows degraded/UNKNOWN-safe real backend evidence and survives theme/style rebuilds', (tester) async {
    addTearDown(() => disposeConnectedApp(tester));
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
    await pumpFrames(tester);
    expect(find.byKey(const ValueKey('health-summary')), findsOneWidget);

    final darkTheme = find.byKey(const ValueKey('global-theme-dark'));
    expect(darkTheme, findsOneWidget);
    await tester.ensureVisible(darkTheme);
    await tester.tap(darkTheme);
    await pumpFrames(tester);
    expect(find.byKey(const ValueKey('health-summary')), findsOneWidget);
  });

  testWidgets('connected native runtime renders authentication rejection without leaking credential text', (tester) async {
    addTearDown(() => disposeConnectedApp(tester));
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

    final allCredentialText = find.textContaining(badKey);
    final credentialInsideInput = find.descendant(of: operatorField, matching: allCredentialText);
    expect(allCredentialText, findsOneWidget);
    expect(credentialInsideInput, findsOneWidget);
  });
}
