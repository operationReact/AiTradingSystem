import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trading_client/src/data/authenticated_api_client.dart';
import 'package:trading_client/src/data/model_emergency_control_repository.dart';
import 'package:trading_client/src/presentation/model_emergency_controls_page.dart';

class _Repository implements ModelEmergencyControlRepository {
  _Repository({this.denied = false});
  final bool denied;
  int mutatingCalls = 0;
  int loads = 0;

  @override
  Future<List<ModelEmergencyControl>> list() async {
    loads++;
    if (denied) throw const ApiHttpFailure('AUTHENTICATION_REJECTED', null, 403);
    return [
      ModelEmergencyControl(
        type: 'STATIC_FALLBACK', scopeKey: 'GLOBAL',
        active: mutatingCalls > 0,
        reason: mutatingCalls > 0 ? 'Source feed degraded severely' : 'No override',
        stateFingerprint: 'a' * 64,
      ),
    ];
  }

  @override
  Future<ModelEmergencyControl> disableFamily(String family, String reason) async {
    mutatingCalls++;
    return ModelEmergencyControl(
      type: 'MODEL_FAMILY_DISABLED', scopeKey: family,
      active: true, reason: reason, stateFingerprint: 'b' * 64);
  }

  @override
  Future<ModelEmergencyControl> disableVersion(String versionId, String reason) async {
    mutatingCalls++;
    return ModelEmergencyControl(
      type: 'MODEL_VERSION_DISABLED', scopeKey: versionId,
      active: true, reason: reason, stateFingerprint: 'b' * 64);
  }

  @override
  Future<ModelEmergencyControl> enableStaticFallback(String reason) async {
    mutatingCalls++;
    return ModelEmergencyControl(
      type: 'STATIC_FALLBACK', scopeKey: 'GLOBAL',
      active: true, reason: reason, stateFingerprint: 'b' * 64);
  }
}

void main() {
  testWidgets('emergency action requires nontrivial reason and explicit confirmation', (tester) async {
    final repo = _Repository();
    await tester.pumpWidget(MaterialApp(home:
      ModelEmergencyControlsPage(repository: repo)));
    await tester.pumpAndSettle();

    expect(find.text('Emergency model controls'), findsOneWidget);
    expect(find.text('LIVE unavailable'), findsNothing);
    final reason = find.byKey(const ValueKey('emergency-reason'));
    await tester.ensureVisible(reason);
    await tester.enterText(reason, 'Source feed degraded severely');
    final review = find.byKey(const ValueKey('emergency-review'));
    await tester.ensureVisible(review);
    await tester.tap(review);
    await tester.pumpAndSettle();

    expect(repo.mutatingCalls, 0);
    expect(find.text('Confirm emergency reduction'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('emergency-cancel')));
    await tester.pumpAndSettle();
    expect(repo.mutatingCalls, 0);

    await tester.ensureVisible(review);
    await tester.tap(review);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('emergency-confirm')));
    await tester.pumpAndSettle();
    expect(repo.mutatingCalls, 1);
    await tester.drag(find.byKey(const ValueKey('emergency-controls-page')),
      const Offset(0, 700));
    await tester.pumpAndSettle();
    expect(find.textContaining('1 active safety overrides'), findsOneWidget);
    expect(find.text('Enable LIVE'), findsNothing);
  });

  testWidgets('server 403 shows denied state and never exposes controls', (tester) async {
    final repo = _Repository(denied: true);
    await tester.pumpWidget(MaterialApp(home:
      ModelEmergencyControlsPage(repository: repo)));
    await tester.pumpAndSettle();

    expect(find.text('Permission denied'), findsOneWidget);
    expect(find.byKey(const ValueKey('emergency-review')), findsNothing);
    expect(repo.mutatingCalls, 0);
  });

  testWidgets('empty reason blocks request before confirmation', (tester) async {
    final repo = _Repository();
    await tester.pumpWidget(MaterialApp(home:
      ModelEmergencyControlsPage(repository: repo)));
    await tester.pumpAndSettle();
    final review = find.byKey(const ValueKey('emergency-review'));
    await tester.scrollUntilVisible(review, 180,
      scrollable: find.byType(Scrollable).first);
    await tester.tap(review);
    await tester.pumpAndSettle();
    await tester.drag(find.byKey(const ValueKey('emergency-controls-page')),
      const Offset(0, 700));
    await tester.pumpAndSettle();
    expect(find.textContaining('between 10 and 512 characters'), findsWidgets);
    expect(find.byKey(const ValueKey('emergency-confirm')), findsNothing);
    expect(repo.mutatingCalls, 0);
  });
}
