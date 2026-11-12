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
    this.mode = 'PAPER',
    this.status = 'DRAFT',
  });

  final String id;
  final String name;
  final String focus;
  // Existing field name retained for compatibility with V2 design widgets.
  // It represents a PLANNED amount in either mode, never funded capital.
  final int paperBudget;
  final String riskPreference;
  final String mode;
  final String status;
}

/// Persisted customer plans, never broker orders or active trading rooms.
abstract class RoomDraftPersistence {
  Future<List<TradingRoomDraft>> list();
  Future<TradingRoomDraft> create(TradingRoomDraft draft);
}

/// Optional authenticated display-name mutation. Existing persistence
/// fixtures remain read-only unless they explicitly support room renaming.
abstract class RoomDraftRenaming {
  Future<TradingRoomDraft> rename(String roomId, String name);
}

/// Store backed by the customer API in production. The optional in-memory
/// mode is reserved for pure widget tests and must never be used for a real
/// customer account without an authenticated repository.
class TradingRoomDraftStore extends ChangeNotifier {
  TradingRoomDraftStore({this.repository});
  final RoomDraftPersistence? repository;
  final List<TradingRoomDraft> _drafts = [];
  bool _isLoading = false;
  bool _loaded = false;
  String? _lastError;

  List<TradingRoomDraft> get drafts => List.unmodifiable(_drafts);
  bool get isLoading => _isLoading;
  bool get loaded => _loaded || repository == null;
  String? get lastError => _lastError;

  Future<void> load() async {
    if (repository == null) return;
    _isLoading = true;
    _lastError = null;
    notifyListeners();
    try {
      final rooms = await repository!.list();
      _drafts
        ..clear()
        ..addAll(rooms);
      _loaded = true;
    } catch (_) {
      _drafts.clear();
      _loaded = false;
      _lastError = 'Could not retrieve your Trading Rooms securely.';
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<void> create(TradingRoomDraft draft) async {
    if (repository == null) throw StateError('No authenticated room API');
    final stored = await repository!.create(draft);
    _drafts.insert(0, stored);
    _loaded = true;
    _lastError = null;
    notifyListeners();
  }

  /// Renames the same persisted room, preserving its stable ID and
  /// downstream Paper/LIVE state. No optimistic change if the request fails.
  Future<TradingRoomDraft> rename(String roomId, String name) async {
    final index = _drafts.indexWhere((room) => room.id == roomId);
    if (index < 0) throw StateError('Trading Room not found');
    final remote = repository;
    if (remote is! RoomDraftRenaming) {
      throw StateError('Authenticated room renaming is unavailable');
    }
    final old = _drafts[index];
    final renamed = await remote.rename(roomId, name);
    if (renamed.id != old.id || renamed.mode != old.mode ||
        renamed.status != old.status ||
        renamed.focus != old.focus ||
        renamed.riskPreference != old.riskPreference ||
        renamed.paperBudget != old.paperBudget) {
      throw const FormatException('Unexpected room rename response');
    }
    // Concurrent list refreshes may replace the collection during the write.
    final current = _drafts.indexWhere((room) => room.id == roomId);
    if (current >= 0) {
      _drafts[current] = renamed;
    }
    notifyListeners();
    return renamed;
  }

  /// Widget test utility only. Production MUST use create().
  void add(TradingRoomDraft draft) {
    if (repository != null) throw StateError('Use create() for authenticated rooms');
    _drafts.add(draft);
    notifyListeners();
  }

  void clear() {
    _drafts.clear();
    _loaded = false;
    _lastError = null;
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
        (draft.paperBudget * portion).round().clamp(1, draft.paperBudget).toInt();
    return IllustrativeRoomLimits(
      daily: sample(risk.$1),
      trade: sample(risk.$2),
      position: sample(risk.$3),
    );
  }
}
