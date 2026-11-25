class OperatorSession {
  String? _operatorKey;
  bool _customerAuthenticated = false;

  bool get isCustomerAuthenticated => _customerAuthenticated;

  void markCustomerAuthenticated() => _customerAuthenticated = true;

  String? get operatorKey => _operatorKey;
  bool get isAuthenticated => _customerAuthenticated || (_operatorKey != null && _operatorKey!.isNotEmpty);

  void setOperatorKey(String value) {
    final trimmed = value.trim();
    _operatorKey = trimmed.isEmpty ? null : trimmed;
  }

  void clear() {
    _operatorKey = null;
    _customerAuthenticated = false;
  }
}
