class OperatorSession {
  String? _operatorKey;

  String? get operatorKey => _operatorKey;
  bool get isAuthenticated => _operatorKey != null && _operatorKey!.isNotEmpty;

  void setOperatorKey(String value) {
    final trimmed = value.trim();
    _operatorKey = trimmed.isEmpty ? null : trimmed;
  }

  void clear() => _operatorKey = null;
}
