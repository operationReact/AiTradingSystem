import 'package:flutter/foundation.dart';
import 'package:local_auth/local_auth.dart';
import 'package:shared_preferences/shared_preferences.dart';

class BiometricAuthService {
  BiometricAuthService({LocalAuthentication? localAuthentication})
      : _localAuthentication = localAuthentication ?? LocalAuthentication();

  static const _enabledKey = 'auth.fingerprint.enabled';
  final LocalAuthentication _localAuthentication;

  Future<bool> isFingerprintAvailable() async {
    if (kIsWeb) return false;
    try {
      final supported = await _localAuthentication.isDeviceSupported();
      if (!supported || !await _localAuthentication.canCheckBiometrics) return false;
      final available = await _localAuthentication.getAvailableBiometrics();
      return available.contains(BiometricType.fingerprint);
    } catch (_) {
      return false;
    }
  }

  Future<bool> isFingerprintEnabledForApp() async {
    try {
      final preferences = await SharedPreferences.getInstance();
      return preferences.getBool(_enabledKey) ?? false;
    } catch (_) {
      return false;
    }
  }

  Future<void> setFingerprintEnabledForApp(bool enabled) async {
    try {
      final preferences = await SharedPreferences.getInstance();
      await preferences.setBool(_enabledKey, enabled);
    } catch (_) {
      // Preference persistence is non-secret convenience state only. If the
      // platform store is unavailable, fingerprint sign-in simply stays off.
    }
  }

  Future<bool> authenticateFingerprint({required String reason}) async {
    if (kIsWeb) return false;
    try {
      if (!await isFingerprintAvailable()) return false;
      return await _localAuthentication.authenticate(
        localizedReason: reason,
        options: const AuthenticationOptions(
          biometricOnly: true,
          stickyAuth: true,
          useErrorDialogs: true,
        ),
      );
    } catch (_) {
      return false;
    }
  }
}
