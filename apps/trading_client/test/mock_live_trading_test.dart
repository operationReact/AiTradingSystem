import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/presentation/review/mock_live_trading.dart';

// Exact-head acceptance coverage for the backend-free LIVE review experience.
// This suite intentionally runs at a compact Android viewport to catch clipping
// while exercising the complete review-only LIVE safety and broker lifecycle.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<void> pumpLive(WidgetTester tester) async {
    tester.view.physicalSize = const Size(390, 844);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      MaterialApp(
        debugShowCheckedModeBanner: false,
        theme: ThemeData.dark().copyWith(
          scaffoldBackgroundColor: const Color(0xFF020817),
          colorScheme: const ColorScheme.dark(
            primary: Color(0xFF13DFF3),
            secondary: Color(0xFF8B4DFF),
            surface: Color(0xFF08162A),
          ),
          chipTheme: ChipThemeData(
            selectedColor: const Color(0xFF13DFF3),
            backgroundColor: const Color(0xFF0A172A),
            labelStyle: const TextStyle(fontSize: 11),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
            side: const BorderSide(color: Color(0xFF183050)),
          ),
        ),
        home: MockLiveTradingFlow(onExitToPaper: () {}),
      ),
    );
    await tester.pumpAndSettle();
  }

  void expectNoFlutterError(WidgetTester tester) {
    expect(tester.takeException(), isNull);
  }

  testWidgets('LIVE mock journey works at 390x844 without overflow', (tester) async {
    await pumpLive(tester);

    expect(find.text('Activate LIVE Trading'), findsOneWidget);
    expectNoFlutterError(tester);

    await tester.tap(find.byKey(const Key('live-acknowledgement')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('enable-live-button')));
    await tester.tap(find.byKey(const Key('enable-live-button')));
    await tester.pumpAndSettle();

    expect(find.text('Broker & Funds'), findsOneWidget);
    expectNoFlutterError(tester);

    await tester.ensureVisible(find.byKey(const Key('open-live-ticket')));
    await tester.tap(find.byKey(const Key('open-live-ticket')));
    await tester.pumpAndSettle();
    expect(find.text('LIVE Trade Ticket'), findsOneWidget);
    expectNoFlutterError(tester);

    await tester.ensureVisible(find.byKey(const Key('review-live-order')));
    await tester.tap(find.byKey(const Key('review-live-order')));
    await tester.pumpAndSettle();
    expect(find.text('LIVE Order Review'), findsOneWidget);
    expectNoFlutterError(tester);

    await tester.ensureVisible(find.byKey(const Key('place-live-order')));
    await tester.tap(find.byKey(const Key('place-live-order')));
    await tester.pumpAndSettle();
    expect(find.text('LIVE Order Status'), findsOneWidget);

    await tester.ensureVisible(find.widgetWithText(ChoiceChip, 'Filled'));
    await tester.tap(find.widgetWithText(ChoiceChip, 'Filled'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('view-live-position')));
    await tester.tap(find.byKey(const Key('view-live-position')));
    await tester.pumpAndSettle();

    expect(find.text('LIVE Position'), findsOneWidget);
    expectNoFlutterError(tester);

    await tester.ensureVisible(find.text('Emergency / Kill Switch'));
    await tester.tap(find.text('Emergency / Kill Switch'));
    await tester.pumpAndSettle();
    expect(find.text('Emergency Controls'), findsOneWidget);
    expectNoFlutterError(tester);

    await tester.ensureVisible(find.byKey(const Key('activate-kill-switch')));
    await tester.tap(find.byKey(const Key('activate-kill-switch')));
    await tester.pumpAndSettle();
    expect(find.text('LIVE Trading Locked'), findsOneWidget);
    expectNoFlutterError(tester);
  });

  testWidgets('broker session expiry and reconnect states are reviewable', (tester) async {
    await pumpLive(tester);

    await tester.ensureVisible(find.text('Broker Session & Re-authentication'));
    await tester.tap(find.text('Broker Session & Re-authentication'));
    await tester.pumpAndSettle();
    expect(find.text('Session Active'), findsOneWidget);
    expectNoFlutterError(tester);

    await tester.ensureVisible(find.byKey(const Key('expire-live-session')));
    await tester.tap(find.byKey(const Key('expire-live-session')));
    await tester.pumpAndSettle();
    expect(find.text('Session Expired'), findsOneWidget);

    await tester.ensureVisible(find.byKey(const Key('reconnect-live-session')));
    await tester.tap(find.byKey(const Key('reconnect-live-session')));
    await tester.pumpAndSettle();
    expect(find.text('Session Active'), findsOneWidget);
    expectNoFlutterError(tester);
  });
}
