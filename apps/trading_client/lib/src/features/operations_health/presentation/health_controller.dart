import 'package:flutter/foundation.dart';

import '../data/health_repository.dart';
import '../domain/health_snapshot.dart';

class HealthController extends ChangeNotifier {
  HealthController(this.repository);

  final HealthRepository repository;
  HealthSnapshot? snapshot;
  HealthFailure? failure;
  bool loading = false;

  Future<void> refresh() async {
    if (loading) return;
    loading = true;
    failure = null;
    notifyListeners();
    try {
      snapshot = await repository.fetch();
    } on HealthFailure catch (error) {
      failure = error;
    } finally {
      loading = false;
      notifyListeners();
    }
  }

  void clearEvidence() {
    snapshot = null;
    failure = null;
    notifyListeners();
  }
}
