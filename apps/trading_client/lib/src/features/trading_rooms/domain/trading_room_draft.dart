import 'package:flutter/foundation.dart';

/// UI-only draft. Never an executable or broker-backed trading room.
@immutable
class TradingRoomDraft {
  const TradingRoomDraft({
    required this.id,
    required this.name,
    required this.focus,
    required this.paperBudget,
    required this.riskPreference,
  });

  final String id;
  final String name;
  final String focus;
  final int paperBudget;
  final String riskPreference;
}

/// Session-only, in-memory previews. Do not confuse these with persisted
/// broker accounts, paper orders, or server-enforced risk controls.
class TradingRoomDraftStore extends ChangeNotifier {
  final List<TradingRoomDraft> _drafts = [];

  List<TradingRoomDraft> get drafts => List.unmodifiable(_drafts);

  void add(TradingRoomDraft draft) {
    _drafts.add(draft);
    notifyListeners();
  }

  void clear() {
    _drafts.clear();
    notifyListeners();
  }
}

/// Example figures for the design review, not trading advice, a suitability
/// assessment, or actual risk-engine settings. No orders use these numbers.
@immutable
class IllustrativeRoomLimits {
  const IllustrativeRoomLimits({
    required this.daily,
    required this.trade,
    required this.position,
  });

  final int daily;
  final int trade;
  final int position;

  factory IllustrativeRoomLimits.forDraft(TradingRoomDraft draft) {
    final risk = switch (draft.riskPreference) {
      'Cautious' => (0.005, 0.002, 0.10),
      'Aggressive' => (0.02, 0.005, 0.25),
      _ => (0.01, 0.003, 0.15),
    };
    int sample(double portion) =>
        (draft.paperBudget * portion).round().clamp(1, draft.paperBudget);
    return IllustrativeRoomLimits(
      daily: sample(risk.$1),
      trade: sample(risk.$2),
      position: sample(risk.$3),
    );
  }
}
