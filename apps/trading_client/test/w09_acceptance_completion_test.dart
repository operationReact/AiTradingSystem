import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/backtest_repository.dart';
import 'package:trading_client/src/data/instrument_repository.dart';
import 'package:trading_client/src/data/market_data_repository.dart';
import 'package:trading_client/src/data/risk_approval_repository.dart';
import 'package:trading_client/src/presentation/backtests_page.dart';
import 'package:trading_client/src/presentation/market_data_page.dart';
import 'package:trading_client/src/presentation/risk_approvals_page.dart';

class _BacktestFake implements BacktestRepository {
  final List<BacktestRunSummary> _runs = [];
  int runCalls = 0;

  BacktestRunSummary _summary() => BacktestRunSummary(
        id: 'run-1',
        instrumentId: 42,
        exchange: 'NSE',
        tradingSymbol: 'INFY-EQ',
        interval: 'ONE_DAY',
        fromDate: DateTime.utc(2026, 1, 1),
        toDate: DateTime.utc(2026, 6, 1),
        initialCapital: 100000,
        tradeCount: 12,
        winningTrades: 7,
        losingTrades: 5,
        winRatePercent: 58.33,
        netPnl: 3250,
        finalEquity: 103250,
        totalReturnPercent: 3.25,
        maxDrawdownPercent: 1.8,
        profitFactor: 1.4,
        createdAt: DateTime.utc(2026, 10, 31),
      );

  @override
  Future<List<BacktestRunSummary>> recent({int limit = 20}) async => List.unmodifiable(_runs);

  @override
  Future<BacktestRunSummary> run({
    required int instrumentId,
    required String interval,
    required DateTime fromDate,
    required DateTime toDate,
    required num initialCapital,
  }) async {
    runCalls += 1;
    final summary = _summary();
    _runs
      ..clear()
      ..add(summary);
    return summary;
  }
}

class _DegradedMarketFake implements MarketDataRepository {
  @override
  Future<InstrumentCatalogueStatus> instrumentStatus() async =>
      throw const ApiTransportFailure('MARKET_DATA_DEGRADED', 'corr-market');

  @override
  Future<InstrumentSearchPage> searchInstruments({
    String? query,
    String? exchange,
    String? type,
    int page = 0,
    int size = 20,
  }) async => const InstrumentSearchPage(
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
        hasNext: false,
        catalogueVersion: null,
        activatedAt: null,
      );

  @override
  Future<MarketCandlePage> candles({
    required int instrumentId,
    required String interval,
    required DateTime fromDate,
    required DateTime toDate,
    int page = 0,
    int size = 100,
  }) async => const MarketCandlePage(
        items: [],
        page: 0,
        size: 100,
        totalItems: 0,
        totalPages: 0,
        hasNext: false,
      );

  @override
  Future<void> syncInstruments() async {}
}

class _HaltRiskFake implements RiskApprovalRepository {
  _HaltRiskFake() : snapshot = _snapshot(false);

  RiskApprovalSnapshot snapshot;
  int fetchCalls = 0;
  bool? halted;
  String? reason;

  static RiskApprovalSnapshot _snapshot(bool halted) => RiskApprovalSnapshot(
        config: RiskConfig(
          tradingMode: 'PAPER',
          liveTradingEnabled: false,
          policyVersion: 'v2',
          startingCapital: 100000,
          riskPerTradePercent: 1,
          maxDailyDrawdownPercent: 3,
          maxOpenPositions: 5,
          signalMaxAgeMinutes: 15,
          newOrdersHalted: halted,
        ),
        decisions: const [],
        approvals: const [],
      );

  @override
  Future<RiskApprovalSnapshot> fetch() async {
    fetchCalls += 1;
    return snapshot;
  }

  @override
  Future<RiskConfig> setEmergencyStop({required bool halted, required String reason}) async {
    this.halted = halted;
    this.reason = reason;
    snapshot = _snapshot(halted);
    return snapshot.config;
  }

  @override
  Future<ApprovalView> decideApproval({
    required ApprovalView approval,
    required String decision,
    required String actor,
    String? reason,
  }) async => approval;
}

void _tallViewport(WidgetTester tester) {
  tester.view.physicalSize = const Size(900, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void main() {
  testWidgets('W09 backtest journey runs PAPER simulation then renders authoritative history', (tester) async {
    _tallViewport(tester);
    final repository = _BacktestFake();
    await tester.pumpWidget(MaterialApp(home: BacktestsPage(repository: repository)));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('backtests-empty')), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('backtest-instrument-id')), '42');
    await tester.enterText(find.byKey(const ValueKey('backtest-from-date')), '2026-01-01');
    await tester.enterText(find.byKey(const ValueKey('backtest-to-date')), '2026-06-01');
    await tester.tap(find.byKey(const ValueKey('backtest-run')));
    await tester.pumpAndSettle();

    expect(repository.runCalls, 1);
    expect(find.byKey(const ValueKey('backtest-run-1')), findsOneWidget);
    expect(find.text('INFY-EQ'), findsOneWidget);
    expect(find.textContaining('12 trades'), findsOneWidget);
  });

  testWidgets('W09 degraded market-data journey fails closed with explicit evidence state', (tester) async {
    await tester.pumpWidget(MaterialApp(home: MarketDataPage(repository: _DegradedMarketFake())));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('market-data-error')), findsOneWidget);
    expect(find.text('Market data unavailable: MARKET_DATA_DEGRADED'), findsOneWidget);
    expect(find.textContaining('client-generated prices'), findsOneWidget);
  });

  testWidgets('W09 emergency stop confirms mutation then rereads authoritative backend state', (tester) async {
    _tallViewport(tester);
    final repository = _HaltRiskFake();
    await tester.pumpWidget(MaterialApp(home: RiskApprovalsPage(repository: repository)));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const ValueKey('emergency-stop-reason')), 'operator safety halt');
    await tester.tap(find.byKey(const ValueKey('risk-halt-orders')));
    await tester.pumpAndSettle();
    expect(find.text('Halt new PAPER orders?'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('confirm-risk-halt')));
    await tester.pumpAndSettle();

    expect(repository.halted, isTrue);
    expect(repository.reason, 'operator safety halt');
    expect(repository.fetchCalls, greaterThanOrEqualTo(2));
    expect(find.text('NEW ORDERS HALTED'), findsOneWidget);
  });
}
