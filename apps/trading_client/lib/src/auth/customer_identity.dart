import 'dart:convert';

import 'package:flutter/foundation.dart' show kDebugMode, kIsWeb;

import 'package:amplify_auth_cognito/amplify_auth_cognito.dart';
import 'package:amplify_flutter/amplify_flutter.dart' show Amplify, AuthenticationFlowType;
import 'package:http/http.dart' as http;

/// Authentication is deliberately independent of MintTick app-user IDs.
abstract class CustomerIdentity {
  bool get isConfigured;

  Future<RegistrationStep> register({
    required String username,
    required String email,
    required String password,
  });

  Future<void> confirmEmail({required String username, required String code});
  Future<void> resendEmailCode(String username);
  Future<void> requestPasswordReset(String usernameOrEmail);
  Future<void> confirmPasswordReset({
    required String usernameOrEmail,
    required String code,
    required String newPassword,
  });
  Future<LoginStep> signIn({required String username, required String password});
  Future<bool> hasSession();
  Future<String?> accessToken();
  Future<void> signOut();
}

enum RegistrationStep { emailCodeRequired, complete }
enum LoginStep { complete, emailConfirmationRequired, additionalChallenge }

/// Available only for an explicitly opted-in Flutter WEB DEBUG session.
/// The normal Android/release sign-in remains USER_SRP_AUTH.
abstract final class MintTickLocalAuthFlow {
  static const requested = bool.fromEnvironment(
    'MINTTICK_LOCAL_COGNITO_PASSWORD_AUTH', defaultValue: false);

  static bool usePasswordAuth({
    required bool debugMode,
    required bool web,
    required bool optIn,
  }) => debugMode && web && optIn;

  static bool get enabled => usePasswordAuth(
    debugMode: kDebugMode, web: kIsWeb, optIn: requested);
}


/// Allows screens and unit tests to swap authentication implementations.
abstract final class MintTickIdentity {
  static CustomerIdentity current = const UnavailableCustomerIdentity();

  static Future<void> initialize() async {
    const region = String.fromEnvironment(
      'MINTTICK_COGNITO_REGION',
      defaultValue: 'ap-south-1',
    );
    const poolId = String.fromEnvironment('MINTTICK_COGNITO_USER_POOL_ID');
    const clientId = String.fromEnvironment('MINTTICK_COGNITO_CLIENT_ID');
    if (poolId.isEmpty || clientId.isEmpty) return; // Fail closed, not a demo identity.

    try {
      // Public user-pool app client: no client secret or broker credentials.
      // Use the stable Amplify v2 JSON schema rather than package-internal
      // generated output classes (which differ between SDK releases).
      final config = jsonEncode({
        'version': '1.5',
        'auth': {
          'aws_region': region,
          'user_pool_id': poolId,
          'user_pool_client_id': clientId,
          'standard_required_attributes': ['email'],
          'user_verification_types': ['email'],
        },
      });
      await Amplify.addPlugin(AmplifyAuthCognito());
      await Amplify.configure(config);
      current = const CognitoCustomerIdentity();
    } catch (_) {
      // Do not log tokens, identifiers, credentials, or SDK exception payloads.
      current = const UnavailableCustomerIdentity();
    }
  }
}

class CustomerAuthenticationUnavailable implements Exception {
  const CustomerAuthenticationUnavailable();
}

class CustomerAuthenticationFailed implements Exception {
  const CustomerAuthenticationFailed();
}

/// There is no fallback local password store or fake successful login.
class UnavailableCustomerIdentity implements CustomerIdentity {
  const UnavailableCustomerIdentity();

  @override
  bool get isConfigured => false;

  Never _unavailable() => throw const CustomerAuthenticationUnavailable();

  @override
  Future<RegistrationStep> register({
    required String username,
    required String email,
    required String password,
  }) async => _unavailable();

  @override
  Future<void> confirmEmail({required String username, required String code}) async =>
      _unavailable();

  @override
  Future<void> resendEmailCode(String username) async => _unavailable();

  @override
  Future<void> requestPasswordReset(String usernameOrEmail) async => _unavailable();

  @override
  Future<void> confirmPasswordReset({
    required String usernameOrEmail,
    required String code,
    required String newPassword,
  }) async => _unavailable();

  @override
  Future<LoginStep> signIn({required String username, required String password}) async =>
      _unavailable();

  @override
  Future<bool> hasSession() async => false;

  @override
  Future<String?> accessToken() async => null;

  @override
  Future<void> signOut() async {}
}

/// Cognito owns password verification and session token refresh.
/// Never logs or persists passwords, and never stores Cognito tokens in
/// SharedPreferences or MintTick PostgreSQL.
class CognitoCustomerIdentity implements CustomerIdentity {
  const CognitoCustomerIdentity();

  @override
  bool get isConfigured => true;

  @override
  Future<RegistrationStep> register({
    required String username,
    required String email,
    required String password,
  }) async {
    try {
      final result = await Amplify.Auth.signUp(
        username: username,
        password: password,
        options: SignUpOptions(
          userAttributes: {AuthUserAttributeKey.email: email},
        ),
      );
      return result.nextStep.signUpStep == AuthSignUpStep.done
          ? RegistrationStep.complete
          : RegistrationStep.emailCodeRequired;
    } on AuthException {
      throw const CustomerAuthenticationFailed();
    }
  }

  @override
  Future<void> confirmEmail({required String username, required String code}) async {
    try {
      final result = await Amplify.Auth.confirmSignUp(
        username: username,
        confirmationCode: code,
      );
      if (result.nextStep.signUpStep != AuthSignUpStep.done) {
        throw const CustomerAuthenticationFailed();
      }
    } on AuthException {
      throw const CustomerAuthenticationFailed();
    }
  }

  @override
  Future<void> resendEmailCode(String username) async {
    try {
      await Amplify.Auth.resendSignUpCode(username: username);
    } on AuthException {
      throw const CustomerAuthenticationFailed();
    }
  }

  @override
  Future<void> requestPasswordReset(String usernameOrEmail) async {
    try {
      await Amplify.Auth.resetPassword(username: usernameOrEmail);
    } on AuthException {
      // Do not leak whether an account exists. UI uses a generic message.
      throw const CustomerAuthenticationFailed();
    }
  }

  @override
  Future<void> confirmPasswordReset({
    required String usernameOrEmail,
    required String code,
    required String newPassword,
  }) async {
    try {
      final result = await Amplify.Auth.confirmResetPassword(
        username: usernameOrEmail,
        confirmationCode: code,
        newPassword: newPassword,
      );
      if (!result.isPasswordReset) {
        throw const CustomerAuthenticationFailed();
      }
    } on AuthException {
      throw const CustomerAuthenticationFailed();
    }
  }

  @override
  Future<LoginStep> signIn({required String username, required String password}) async {
    try {
      // SRP is the default. The opt-in USER_PASSWORD_AUTH path helps isolate
      // a browser-side SRP stall without bypassing Cognito verification.
      // USER_PASSWORD_AUTH sends the password to Cognito over HTTPS; never
      // log, persist, or route it through MintTick's backend.
      final result = await Amplify.Auth.signIn(
        username: username,
        password: password,
        options: SignInOptions(
          pluginOptions: CognitoSignInPluginOptions(
            authFlowType: MintTickLocalAuthFlow.enabled
                ? AuthenticationFlowType.userPasswordAuth
                : AuthenticationFlowType.userSrpAuth,
          ),
        ),
      );
      return switch (result.nextStep.signInStep) {
        AuthSignInStep.done => LoginStep.complete,
        AuthSignInStep.confirmSignUp => LoginStep.emailConfirmationRequired,
        _ => LoginStep.additionalChallenge,
      };
    } on AuthException {
      throw const CustomerAuthenticationFailed();
    }
  }

  @override
  Future<bool> hasSession() async {
    try {
      return (await Amplify.Auth.fetchAuthSession()).isSignedIn;
    } on AuthException {
      return false;
    }
  }

  @override
  Future<String?> accessToken() async {
    try {
      final plugin = Amplify.Auth.getPlugin(AmplifyAuthCognito.pluginKey);
      final session = await plugin.fetchAuthSession();
      if (!session.isSignedIn) return null;
      return session.userPoolTokensResult.value.accessToken.raw;
    } on AuthException {
      return null;
    }
  }

  @override
  Future<void> signOut() async {
    await Amplify.Auth.signOut();
  }
}

/// Only the backend provisions the permanent app-user UUID, after validating
/// the Cognito access JWT and independently checking email verification.
/// This endpoint never accepts a username/password or a broker credential.
class MintTickCustomerAccountApi {
  MintTickCustomerAccountApi({
    CustomerIdentity? identity,
    Uri? baseUri,
    http.Client? client,
  })  : identity = identity ?? MintTickIdentity.current,
        baseUri = baseUri ?? Uri.parse(const String.fromEnvironment(
          'API_BASE_URL',
          defaultValue: 'http://localhost:8080',
        )),
        client = client ?? http.Client();

  final CustomerIdentity identity;
  final Uri baseUri;
  final http.Client client;

  Future<String> provision() async {
    // Don't send a bearer token over insecure HTTP, even in development.
    if (baseUri.scheme != 'https') {
      throw const CustomerAuthenticationUnavailable();
    }
    final token = await identity.accessToken();
    if (token == null || token.isEmpty) {
      throw const CustomerAuthenticationFailed();
    }

    try {
      final response = await client.post(
        baseUri.resolve('/api/auth/cognito/provision'),
        headers: {
          'Authorization': 'Bearer $token',
          'Accept': 'application/json',
        },
      ).timeout(const Duration(seconds: 15));
      if (response.statusCode != 200) {
        throw const CustomerAuthenticationFailed();
      }
      final payload = jsonDecode(response.body);
      if (payload is! Map<String, dynamic> || payload['userId'] is! String) {
        throw const CustomerAuthenticationFailed();
      }
      return payload['userId'] as String;
    } on CustomerAuthenticationFailed {
      rethrow;
    } catch (_) {
      throw const CustomerAuthenticationFailed();
    }
  }
}
