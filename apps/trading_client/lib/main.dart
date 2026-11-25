import 'package:flutter/material.dart';

import 'src/bootstrap.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // First paint must not wait for Cognito, cached sessions, shared preferences,
  // or AWS account verification. Startup runs asynchronously behind the
  // visible, temporarily disabled sign-in form.
  runApp(const MintTickStartupApp());
}
