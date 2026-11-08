import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:trading_client/src/auth/cognito_reachability.dart';

void main() {
  const region = 'ap-south-1';
  const pool = 'ap-south-1_jz2T8qbiY';

  test('checks only public Cognito JWKS with no sign-in data', () async {
    var requests = 0;
    final client = MockClient((request) async {
      requests++;
      expect(request.method, 'GET');
      expect(request.url.toString(),
          'https://cognito-idp.ap-south-1.amazonaws.com/'
          'ap-south-1_jz2T8qbiY/.well-known/jwks.json');
      expect(request.headers.keys, contains('Accept'));
      expect(request.headers, isNot(contains('Authorization')));
      expect(request.body, isEmpty);
      return http.Response('{"keys":[{"kty":"RSA"}]}', 200);
    });
    final healthy = await MintTickCognitoReachability.check(
        client: client, regionOverride: region, poolIdOverride: pool);
    expect(healthy, isTrue);
    expect(requests, 1);
  });

  test('401 or malformed JWKS is not reported as reachable', () async {
    final denied = MockClient((_) async => http.Response('', 401));
    expect(await MintTickCognitoReachability.check(
      client: denied, regionOverride: region, poolIdOverride: pool), isFalse);
    final bad = MockClient((_) async => http.Response('invalid', 200));
    expect(await MintTickCognitoReachability.check(
      client: bad, regionOverride: region, poolIdOverride: pool), isFalse);
  });

  test('client network failure does not expose exception details', () async {
    final failed = MockClient((_) async =>
        throw StateError('sensitive-proxy-details'));
    expect(await MintTickCognitoReachability.check(
      client: failed, regionOverride: region, poolIdOverride: pool), isFalse);
  });

  test('wrong region/pool identity fails before any network request', () async {
    var requests = 0;
    final client = MockClient((_) async {
      requests++;
      return http.Response('{"keys":[]}', 200);
    });
    expect(await MintTickCognitoReachability.check(
        client: client, regionOverride: region,
        poolIdOverride: 'eu-west-1_fake'), isFalse);
    expect(requests, 0);
  });
}
