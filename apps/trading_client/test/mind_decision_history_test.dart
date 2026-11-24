import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/features/mintick_mind/data/mind_decision_history_repository.dart';
import 'package:trading_client/src/features/mintick_mind/presentation/mind_workspace_page.dart';

Map<String, dynamic> record({
  String strategy = 'MA_RECLAIM_V1',
  String symbol = 'RELIANCE',
  String fingerprint = 'a1b2c3d4e5f60718',
  String signal = '2026-11-13T09:30:00Z',
  String cutoff = '2026-11-13T09:30:00Z',
  bool orderSubmitted = false,
}) => {
      'scanRunId': '3f2c9d1e-0000-4000-8000-000000000001',
      'runCompletedAt': '2026-11-13T09:31:00Z',
      'strategy': strategy,
      'strategyVersion': 1,
      'catalogueVersion': 'cat-v1',
      'tradingSymbol': symbol,
      'exchange': 'NSE',
      'interval': 'FIVE_MINUTE',
      'signalCandleTime': signal,
      'dataCutoffAt': cutoff,
      'candidateFingerprint': fingerprint,
      'reasons': ['TREND', 'VOLUME'],
      'decisionPosture': 'RECORDED_CANDIDATE',
      'paperOrderSubmitted': orderSubmitted,
      'executionEnabled': false,
      'liveMoney': false,
    };

Map<String, dynamic> history(List<Map<String, dynamic>> rows) => {
      'contract': 'mind.decision-history.v1',
      'generatedAt': '2026-11-16T04:00:00Z',
      'limit': 50,
      'decisions': rows,
      'limitations': ['Abstentions are not persisted per decision.'],
    };

class _HistoryRepo implements MindDecisionHistoryRepository {
  _HistoryRepo(this.payload, {this.fail = false});
  final Map<String, dynamic> payload;
  final bool fail;
  @override
  Future<MindDecisionHistory> load() async {
    if (fail) throw StateError('network');
    return MindDecisionHistory.decode(payload);
  }
}

Future<void> openDecisionLab(WidgetTester tester, MindDecisionHistoryRepository repo) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1440, 2400);
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: MintickMindWorkspacePage(decisionHistoryRepository: repo)));
  await tester.pumpAndSettle();
  final nav = find.byKey(const ValueKey('mind-route-decision-lab'));
  await tester.ensureVisible(nav);
  await tester.tap(nav);
  await tester.pumpAndSettle();
}

void main() {
  test('decodes recorded candidates with provenance and reasons', () {
    final h = MindDecisionHistory.decode(history([record()]));
    expect(h.decisions.single.strategy, 'MA_RECLAIM_V1');
    expect(h.decisions.single.reasons, ['TREND', 'VOLUME']);
    expect(h.decisions.single.catalogueVersion, 'cat-v1');
  });

  test('preserves exact persisted instrument ID and rejects guessed identities', () {
    expect(MindDecisionRecord.decode(record()).instrumentId, isNull);
    expect(MindDecisionRecord.decode(record()..['instrumentId'] = 4242).instrumentId, 4242);
    expect(
      () => MindDecisionRecord.decode(record()..['instrumentId'] = 'RELIANCE'),
      throwsFormatException,
    );
    expect(
      () => MindDecisionRecord.decode(record()..['instrumentId'] = 0),
      throwsFormatException,
    );
  });

  test('a record that claims an order is rejected, never shown', () {
    expect(() => MindDecisionHistory.decode(history([record(orderSubmitted: true)])),
      throwsFormatException);
  });

  test('a record whose data cutoff precedes its signal is treated as future-informed', () {
    expect(() => MindDecisionHistory.decode(history([
      record(signal: '2026-11-13T09:30:00Z', cutoff: '2026-11-13T09:29:00Z'),
    ])), throwsFormatException);
  });

  test('an unknown contract is rejected', () {
    final bad = history([record()])..['contract'] = 'mind.decision-history.v2';
    expect(() => MindDecisionHistory.decode(bad), throwsFormatException);
  });

  testWidgets('Decision Lab lists recorded candidates and search narrows them', (tester) async {
    await openDecisionLab(tester, _HistoryRepo(history([
      record(symbol: 'RELIANCE', fingerprint: 'aaaaaaaaaaaaaaaa'),
      record(symbol: 'INFY', strategy: 'SMA_TREND_VOLUME_V1', fingerprint: 'bbbbbbbbbbbbbbbb'),
    ])));
    expect(find.byKey(const ValueKey('mind-decision-history')), findsOneWidget);
    expect(find.text('RECORDED CANDIDATES · RESEARCH ONLY · NO ORDER'), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-decision-aaaaaaaaaaaaaaaa')), findsOneWidget);
    expect(find.byKey(const ValueKey('mind-decision-bbbbbbbbbbbbbbbb')), findsOneWidget);
    await tester.enterText(find.byKey(const ValueKey('mind-decision-search')), 'infy');
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('mind-decision-aaaaaaaaaaaaaaaa')), findsNothing);
    expect(find.byKey(const ValueKey('mind-decision-bbbbbbbbbbbbbbbb')), findsOneWidget);
  });

  testWidgets('a failed history load shows no candidates', (tester) async {
    await openDecisionLab(tester, _HistoryRepo(history([]), fail: true));
    expect(find.byKey(const ValueKey('mind-decision-history-error')), findsOneWidget);
    expect(find.textContaining('Decision history unavailable'), findsOneWidget);
  });

  group('news impact (#1809)', () {
    test('an absent news field is not recorded, never a no-news verdict', () {
      final h = MindDecisionHistory.decode(history([record()]));
      expect(h.decisions.single.news, isNull);
    });

    test('decodes included, source-attributed evidence with explicit timestamps', () {
      final h = MindDecisionHistory.decode(history([record()..['news'] = newsPayload()]));
      final n = h.decisions.single.news!;
      expect(n.status, MindNewsStatus.includedEvidence);
      expect(n.netDirection, closeTo(0.6, 1e-9));
      expect(n.direction, 'POSITIVE');
      expect(n.evidence.single.source, 'TEST-SOURCE');
      expect(n.evidence.single.analysisAvailableAt.toUtc().toIso8601String(), '2026-11-13T09:01:00.000Z');
      expect(n.evidence.single.included, isTrue);
    });

    test('malformed news is untrustworthy and does not hide the candidate itself', () {
      final bad = newsPayload(net: null, direction: 'POSITIVE');
      final h = MindDecisionHistory.decode(history([record()..['news'] = bad]));
      expect(h.decisions, hasLength(1));
      expect(h.decisions.single.strategy, 'MA_RECLAIM_V1');
      expect(h.decisions.single.news!.status, MindNewsStatus.untrustworthy);
    });

    test('an UNKNOWN direction may never carry a numeric net (unknown is never bullish)', () {
      final bad = newsPayload(status: 'NO_INCLUDED_EVIDENCE', net: 0.5, direction: 'UNKNOWN', included: 0);
      final h = MindDecisionHistory.decode(history([record()..['news'] = bad]));
      expect(h.decisions.single.news!.status, MindNewsStatus.untrustworthy);
    });

    test('a news payload with a different contract is rejected as untrustworthy', () {
      final bad = newsPayload()..['contract'] = 'news.context.v2';
      final h = MindDecisionHistory.decode(history([record()..['news'] = bad]));
      expect(h.decisions.single.news!.status, MindNewsStatus.untrustworthy);
    });

    testWidgets('Decision Lab shows News Impact with source, timestamps and no trade wording',
        (tester) async {
      await openDecisionLab(tester, _HistoryRepo(history([
        record(fingerprint: 'cccccccccccccccc')..['news'] = newsPayload(),
      ])));
      expect(find.byKey(const ValueKey('mind-news-impact-cccccccccccccccc')), findsOneWidget);
      expect(find.text('NEWS IMPACT · ADVISORY · NOT A TRADE SIGNAL'), findsOneWidget);
      expect(find.textContaining('Evidence direction: positive (net 0.60)'), findsOneWidget);
      expect(find.textContaining('TEST-SOURCE: Reliance headline'), findsOneWidget);
      expect(find.textContaining('valid until 2026-11-14T09:00:00.000Z'), findsOneWidget);
    });

    testWidgets('Decision Lab says not recorded when a candidate has no news context', (tester) async {
      await openDecisionLab(tester, _HistoryRepo(history([
        record(fingerprint: 'dddddddddddddddd'),
      ])));
      expect(find.textContaining('News impact: not recorded for this candidate'), findsOneWidget);
    });

    testWidgets('Decision Lab rejects untrustworthy news without showing it as evidence', (tester) async {
      await openDecisionLab(tester, _HistoryRepo(history([
        record(fingerprint: 'eeeeeeeeeeeeeeee')..['news'] = newsPayload(net: null, direction: 'POSITIVE'),
      ])));
      expect(find.textContaining('failed validation and is not used'), findsOneWidget);
      expect(find.textContaining('Evidence direction'), findsNothing);
    });

    // Contradictory or out-of-window payloads must fail closed: untrustworthy, never evidence.
    MindNewsStatus decodedStatus(Map<String, dynamic> news) =>
        MindDecisionHistory.decode(history([record()..['news'] = news])).decisions.single.news!.status;

    test('NO_COVERAGE with counts, a net and a bullish label is untrustworthy (reviewer case)', () {
      final bad = newsPayload(status: 'NO_COVERAGE', net: 0.6, direction: 'POSITIVE', included: 1)
        ..['evidence'] = <Map<String, dynamic>>[]
        ..['uncertainty'] = 'UNKNOWN';
      expect(decodedStatus(bad), MindNewsStatus.untrustworthy);
    });

    test('a genuinely empty NO_COVERAGE decodes as no coverage, distinct from absent', () {
      final ok = newsPayload(status: 'NO_COVERAGE', net: null, direction: 'UNKNOWN', included: 0)
        ..['evidence'] = <Map<String, dynamic>>[]
        ..['uncertainty'] = 'UNKNOWN'
        ..['excludedCount'] = 0;
      expect(decodedStatus(ok), MindNewsStatus.noCoverage);
    });

    test('NO_INCLUDED_EVIDENCE that carries a net is untrustworthy', () {
      final bad = newsPayload(status: 'NO_INCLUDED_EVIDENCE', net: 0.5, direction: 'UNKNOWN', included: 0);
      expect(decodedStatus(bad), MindNewsStatus.untrustworthy);
    });

    test('INCLUDED whose label contradicts its net is untrustworthy', () {
      final bad = newsPayload(net: -0.6, direction: 'POSITIVE');
      expect(decodedStatus(bad), MindNewsStatus.untrustworthy);
    });

    test('included evidence published after asOf is untrustworthy', () {
      final bad = newsPayload();
      (bad['evidence'] as List).first['publishedAt'] = '2026-11-13T10:00:00Z';
      expect(decodedStatus(bad), MindNewsStatus.untrustworthy);
    });

    test('included evidence analyzed after asOf is untrustworthy', () {
      final bad = newsPayload();
      (bad['evidence'] as List).first['analysisAvailableAt'] = '2026-11-13T10:00:00Z';
      expect(decodedStatus(bad), MindNewsStatus.untrustworthy);
    });

    test('included evidence past its validity window is untrustworthy', () {
      final bad = newsPayload();
      (bad['evidence'] as List).first['validUntil'] = '2026-11-13T09:31:00Z';
      expect(decodedStatus(bad), MindNewsStatus.untrustworthy);
    });

    test('an excluded item marked INCLUDED, or an unknown reason code, is untrustworthy', () {
      final excludedMarked = newsPayload();
      (excludedMarked['evidence'] as List).first['included'] = false;
      expect(decodedStatus(excludedMarked), MindNewsStatus.untrustworthy);
      final unknownCode = newsPayload();
      (unknownCode['evidence'] as List).first['status'] = 'EXCLUDED_MADE_UP';
      expect(decodedStatus(unknownCode), MindNewsStatus.untrustworthy);
    });

    test('included rows may never outnumber the included count', () {
      final bad = newsPayload(included: 0, status: 'NO_INCLUDED_EVIDENCE', net: null, direction: 'UNKNOWN');
      expect(decodedStatus(bad), MindNewsStatus.untrustworthy);
    });

    test('an excluded item with EXCLUDED_INVALID_SCORE (#1819) is accepted, not untrustworthy', () {
      final ok = newsPayload(included: 0, status: 'NO_INCLUDED_EVIDENCE', net: null, direction: 'UNKNOWN');
      final row = (ok['evidence'] as List).first as Map<String, dynamic>;
      row['included'] = false;
      row['status'] = 'EXCLUDED_INVALID_SCORE';
      expect(decodedStatus(ok), MindNewsStatus.noIncludedEvidence);
    });
  });
}

Map<String, dynamic> newsPayload({
  String status = 'INCLUDED_EVIDENCE',
  Object? net = 0.6,
  String direction = 'POSITIVE',
  int included = 1,
}) => {
      'contract': 'news.context.v1',
      'instrumentId': 4242,
      'tradingSymbol': 'RELIANCE-EQ',
      'asOf': '2026-11-13T09:31:00Z',
      'status': status,
      'netDirection': net,
      'direction': direction,
      'uncertainty': 'LOW',
      'includedCount': included,
      'excludedCount': 0,
      'evidence': [
        {
          'articleId': '13a66579-33cc-4e88-9699-de272fb5eab3',
          'source': 'TEST-SOURCE',
          'title': 'Reliance headline',
          'url': 'https://news.example.test/a',
          'publishedAt': '2026-11-13T09:00:00Z',
          'analysisAvailableAt': '2026-11-13T09:01:00Z',
          'validUntil': '2026-11-14T09:00:00Z',
          'horizon': 'INTRADAY',
          'sentiment': 'POSITIVE',
          'directionScore': 0.6,
          'confidence': 0.8,
          'materiality': 0.7,
          'status': 'INCLUDED',
          'included': true,
        },
      ],
      'limitations': ['Advisory classification.'],
    };
