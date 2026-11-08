# MintTick — IntelliJ local Flutter web + AWS backend

This is the full local **frontend** development configuration for the current
MintTick test environment. No local Java, PostgreSQL, Docker, broker SDK
credentials, or AWS credentials are required.

## Prerequisites (Windows)

- IntelliJ IDEA with **Flutter** and **Dart** plugins enabled.
- Flutter **3.47.x**, Chrome installed, and Git.
- Open the **repository root** `AiTradingSystem` in IntelliJ.
- Ensure `flutter doctor` and `flutter devices` work in IntelliJ's
  built-in Terminal (PowerShell). Do not run from a random Dart source file.

## One-click IntelliJ run

1. Checkout / pull the latest `dev` branch in IntelliJ's Git UI.
2. Open `apps/trading_client/pubspec.yaml`; IntelliJ should identify
   the Flutter module. If needed set the Flutter SDK in
   **Settings > Languages & Frameworks > Flutter**.
3. In IntelliJ's Terminal, from repository root, run:
   `cd apps\trading_client` then `flutter pub get`.
4. At the top right, select the shared **MintTick Local Web (AWS Backend)**
   run configuration. Select **Chrome** as the target device. This is
   the normal configuration when live API Gateway CORS permits localhost.
   **While the AWS role is not yet authorized to update API CORS**, select
   **MintTick Local Web (CORS Debug)** instead. That temporary configuration
   passes `--web-browser-flag=--disable-web-security` to the isolated Chrome
   process launched by Flutter. It bypasses Chrome's CORS enforcement for
   **that debugging browser process only**; it does NOT modify AWS CORS.
   Use the isolated debug browser only for MintTick, never unrelated sites.
   Close it when finished, then switch back to the standard configuration
   after AWS CORS deployment.
5. Click **Run** / **Debug**. The browser opens
   `http://localhost:5173`.
6. Use the real Cognito sign-in. `MINTTICK_AUTH_ONLY_TEST=false` is set.
   Calls go to `https://api.minttick.com`; the AWS Java backend,
   PostgreSQL, market feed and encrypted/SSM broker configuration remain on AWS.

IntelliJ's **Run > Edit Configurations** shows the actual parameters in
`.run/MintTick Local Web (AWS Backend).run.xml`. If the config is not
automatically discovered, restart IntelliJ or choose **Run > Edit
Configurations > + > Flutter** and set **Dart entrypoint**
`apps/trading_client/lib/main.dart` and **Additional run args** to
the `additionalArgs` field in that shared file.

### Terminal fallback (still within IntelliJ)

```powershell
cd apps\trading_client
flutter pub get
flutter run -d chrome --web-port=5173 --dart-define=API_BASE_URL=https://api.minttick.com --dart-define=MINTTICK_COGNITO_REGION=ap-south-1 --dart-define=MINTTICK_COGNITO_USER_POOL_ID=ap-south-1_jz2T8qbiY --dart-define=MINTTICK_COGNITO_CLIENT_ID=6jm18v2mdpcfraip92hrjhqjnd --dart-define=MINTTICK_AUTH_ONLY_TEST=false
```

If the temporary **CORS Debug** configuration is not picked up after
`git pull`, use **Run > Edit Configurations > + > Flutter**, set the same
entrypoint and arguments from `.run/MintTick Local Web (CORS Debug).run.xml`,
then select **Chrome**.

If Chrome reports `ERR_CONNECTION_REFUSED`, make sure port 5173
is free and the Flutter run process is still running. If a browser
network request is blocked by CORS, confirm the API Gateway dev CORS
update has been deployed (see below), not merely merged to Git.

## CORS and authentication

The `http://localhost:5173` origin is present in the **repository's**
CloudFormation template. This alone does not guarantee it is already active
in AWS. The one-file deployment workflow requires `apigateway:GET` and
`apigateway:PATCH` permissions on the existing dev HTTP API. The AWS
code-only GitHub deployment role does not yet have these permissions.
The CloudFormation template now declares the narrow permissions, but it
requires a one-time authorized stack update in AWS to activate the new
IAM policy. Until then, select **MintTick Local Web (CORS Debug)** for
local-only Chrome testing as described above. **Never add that flag to
normal production builds or your everyday browser.**

Once the stack role policy and API CORS deployment have succeeded,
the API Gateway dev HTTP API allows only these browser origins:
`https://minttick.com`, `https://www.minttick.com`,
`http://localhost:5173`. It allows preflight `OPTIONS` and
the existing `GET`/`POST` methods with the original
`Authorization`, `Content-Type`, `Idempotency-Key` and
`X-Correlation-Id` headers.

You can verify preflight in the IntelliJ Terminal:

```powershell
curl.exe -i -X OPTIONS "https://api.minttick.com/api/auth/cognito/provision" -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: POST" -H "Access-Control-Request-Headers: authorization,content-type"
```

Expect `access-control-allow-origin: http://localhost:5173`.
A normal unauthenticated API request **must still return HTTP 401**;
CORS is a browser rule, not authorization.

**Do not pass** `CONNECTED_API_KEY`, SmartAPI API keys, client codes,
Angel One PINs or TOTP seeds to the frontend. The customer client uses
a Cognito access token; Angel One credentials stay in AWS SSM. The
local Flutter process does not place live orders.

## Cognito login stalls on localhost

When the sign-in button says **Connecting to Cognito…** and later times out,
the browser is blocked **before** MintTick's account-provisioning API.
A successful homepage or DevTools connection does not establish that Cognito
user-pool requests are working.

Use **IntelliJ's Terminal** (no passwords or tokens required) to verify
basic connectivity to this exact Cognito User Pool's public signing keys:

```powershell
$uri = 'https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_jz2T8qbiY/.well-known/jwks.json'
$keys = Invoke-RestMethod -Uri $uri -TimeoutSec 10
"Public Cognito endpoint reachable: $($keys.keys.Count -gt 0)"
```

- If the command fails, investigate local DNS, VPN, firewall, proxy and
  HTTPS connectivity before attempting another password login.
- If it succeeds, this confirms **only** public Cognito HTTPS connectivity
  from PowerShell. It does **not** guarantee the browser SDK or SRP auth
  operation works. Chrome DevTools → Network → filter
  `cognito-idp.ap-south-1.amazonaws.com`, then sign in **once**.
  Check the request's **status** and **timing**. Pending, blocked, CORS,
  400/403 and 5xx outcomes imply different fixes.
- Do **not** share the Network Headers or Payload tabs, cookies, tokens,
  credentials, or browser screenshots with passwords visible.
- A `Future.timeout` on Flutter's wrapper does not cancel an underlying
  Amplify authentication call. If it times out, close that debug Chrome
  window and restart the Flutter run before retrying.
- For isolating a Chrome debugging flag, try the normal **MintTick Local
  Web (AWS Backend)** configuration, which does *not* disable browser
  security. Cognito login may then succeed even when the separate
  MintTick API provisioning step hits the still-pending localhost CORS
  permission issue.

MintTick now probes the public JWKS document after an authentication
timeout and differentiates **endpoint reachable, SDK operation stalled**
from **endpoint not verifiably reachable**. This is a diagnostic only;
it does not bypass Cognito password validation or mint an app session.

## Optional Cognito password flow (local debug only)

If Cognito's public JWKS endpoint returns HTTP 200, but Amplify's default
SRP login hangs in Chrome **without an InitiateAuth POST**, the app offers
an explicit test of AWS Cognito's standard `USER_PASSWORD_AUTH` flow.

This is **not a bypass**: Cognito still validates the actual password and
issues the same identity tokens, directly over TLS. Unlike SRP, the password
is transmitted to Cognito inside the encrypted request. It is never sent
to MintTick's backend, browser telemetry, or a repository.

1. Ensure Terraform has successfully updated the existing **dev** public
   Cognito user-pool app client to allow `ALLOW_USER_PASSWORD_AUTH` **in
   addition** to `ALLOW_USER_SRP_AUTH`. A source-code merge does not
   activate this AWS client setting.
2. Stop the current Flutter process with `q` and close its Chrome debug
   instance, since the old SRP call cannot be cancelled by `Future.timeout`.
3. From `apps/trading_client` inside IntelliJ's Terminal, launch:

```powershell
flutter run -d chrome --web-port=5173 --dart-define=API_BASE_URL=https://api.minttick.com --dart-define=MINTTICK_COGNITO_REGION=ap-south-1 --dart-define=MINTTICK_COGNITO_USER_POOL_ID=ap-south-1_jz2T8qbiY --dart-define=MINTTICK_COGNITO_CLIENT_ID=6jm18v2mdpcfraip92hrjhqjnd --dart-define=MINTTICK_AUTH_ONLY_TEST=false --dart-define=MINTTICK_LOCAL_COGNITO_PASSWORD_AUTH=true
```

The alternative shared IntelliJ configuration is **MintTick Local Web
(Cognito Password Debug)**. This flag is compiled out of release builds
and never enabled on Android. Do not enable it on public devices.

Open Chrome DevTools → Network → All. On a sign-in attempt, the
`cognito-idp` **InitiateAuth** request should now appear if the previous
delay was inside SRP processing. A 400 `USER_PASSWORD_AUTH flow not
enabled` means the Cognito Terraform update has not yet been applied.
A successful Cognito login followed by a failed `api.minttick.com`
provision call is a **separate localhost API Gateway CORS issue**.

Never copy or share the Auth request payload or tokens. If Cognito still
hangs before a POST in both modes, inspect sanitized Chrome Console
errors and browser extensions/proxy restrictions rather than retrying
the password repeatedly.

## Developer workflow

Run `flutter analyze` and `flutter test` in
`apps/trading_client` before a commit. IntelliJ's Run/Debug toolbar
supports hot reload; changes to `--dart-define` require stopping and
restarting the app. This local setup does not deploy Java backend
source edits to AWS automatically.
