import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// The active workspace view. This selects UI and API read scope, not an
/// order-submission permission. Each Trading Room stores its own fixed mode.
class TradingWorkspaceMode extends ChangeNotifier {
  TradingWorkspaceMode({SharedPreferencesAsync? preferences})
      : _preferences = preferences ?? SharedPreferencesAsync();

  final SharedPreferencesAsync _preferences;
  static const paper = 'PAPER';
  static const live = 'LIVE';
  static const _prefix = 'minttick.customer.trading_mode.v1.';
  String _selected = paper;
  String? _customerId;
  int _generation = 0;

  String get selected => _selected;
  bool get isPaper => _selected == paper;

  /// Called only with the verified /api/auth/cognito/me app-user UUID.
  /// Every customer's last mode is stored under that customer's own key.
  Future<void> loadForCustomer(String customerId) async {
    if (customerId.isEmpty) throw ArgumentError('Verified user ID required');
    final generation = ++_generation;
    _customerId = customerId;
    _selected = paper;
    notifyListeners();
    try {
      final saved = await _preferences.getString('$_prefix$customerId');
      if (generation != _generation || _customerId != customerId) return;
      _selected = saved == live ? live : paper;
      notifyListeners();
    } catch (_) {
      // A preference-storage failure cannot grant execution permissions.
    }
  }

  void select(String mode) {
    if (mode != paper && mode != live) {
      throw ArgumentError.value(mode, 'mode', 'PAPER or LIVE required');
    }
    if (mode == _selected) return;
    _selected = mode;
    notifyListeners();
    final user = _customerId;
    if (user != null) {
      // Preference persistence is best effort, never a risk-control input.
      _preferences.setString('$_prefix$user', mode).catchError((Object _) {});
    }
  }

  void reset() {
    ++_generation;
    _customerId = null;
    _selected = paper;
    notifyListeners();
  }
}
