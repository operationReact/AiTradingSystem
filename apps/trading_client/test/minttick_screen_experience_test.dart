import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/presentation/sign_in_page.dart';
import 'package:trading_client/src/presentation/paper_portfolio_page.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/paper_trading_repository.dart';
import 'package:trading_client/src/theme/app_theme.dart';

class _GuestPortfolio implements PaperTradingRepository {
  @override
  Future<PaperPortfolio> fetchPortfolio() async =>
      throw const ApiAuthenticationRequired();

  @override
  Future<PaperOrderPage> fetchOrders({
    int page = 0,
    int size = 20,
    String? status,
    String? symbol,
  }) async => throw const ApiAuthenticationRequired();

  @override
  Future<PaperOrderResult> createOrder(
    CreatePaperOrderCommand command, {
    required String idempotencyKey,
  }) async => throw const ApiAuthenticationRequired();

  @override
  Future<PaperOrderResult> cancelOrder(String orderId) async =>
      throw const ApiAuthenticationRequired();
}

void main() {
  for (final brightness in Brightness.values) {
    for (final width in [320.0, 390.0, 411.0]) {
      testWidgets('Sign-in is readable and fail-closed at ${width.toInt()}dp ${brightness.name}', (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = Size(width, 844);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);

        await tester.pumpWidget(MaterialApp(
          theme: buildTheme(brightness),
          home: const SignInPage(),
        ));
        await tester.pumpAndSettle();
        expect(find.text('Welcome to MintTick'), findsOneWidget);
        expect(find.byKey(const ValueKey('secure-sign-in-continue')), findsOneWidget);
        expect(
          tester.widget<FilledButton>(
            find.byKey(const ValueKey('secure-sign-in-continue')),
          ).onPressed,
          isNull,
          reason: 'A provider-name flag must never create an unauthenticated login',
        );
        expect(find.byKey(const ValueKey('sign-in-explore-first')), findsOneWidget);
        expect(tester.takeException(), isNull);
      });
    }
  }

  testWidgets('Guest Portfolio shows a sign-in action rather than invented balances', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(390, 844);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(Brightness.dark),
      home: PaperPortfolioPage(repository: _GuestPortfolio()),
      routes: {'/sign-in': (_) => const SignInPage()},
    ));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('paper-portfolio-error')), findsOneWidget);
    expect(find.text('Your PAPER portfolio'), findsOneWidget);
    final action = find.byKey(const ValueKey('portfolio-state-action'));
    await tester.ensureVisible(action);
    await tester.tap(action);
    await tester.pumpAndSettle();
    expect(find.text('Welcome to MintTick'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
