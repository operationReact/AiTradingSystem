# MintTick Android: permanent signing identity and in-place APK updates

This is a **one-time owner setup**. No signing private keys, passwords,
key.properties or keystore-base64 payloads may enter Git, chat, SSM String,
APK assets, logs, or any public endpoint.

## Why

Previous sideloaded PR builds used Flutter's generated debug keystore on
ephemeral GitHub runners; the certificate could change between releases.
Android installs require the same applicationId, signing certificate, and
a higher versionCode to update in place. The current app ID is
`in.aitradingsystem.trading_client`.

The **first** transition from an older debug-signed APK to the new stable
certificate requires uninstalling the old build once, which clears local
application data and its Cognito session. Sign in once after installing the
first stable signed release. After that, future APKs signed by the **same**
key and carrying higher versionCode can update without clearing that session.

## One-time setup on the owner's Windows PC (PowerShell 7)

1. Install the JDK (`keytool`) and GitHub CLI (`gh`), then execute
   `gh auth login` as an administrator of `operationReact/AiTradingSystem`.
2. Download or clone the reviewed source and run:
   ```powershell
   pwsh ./scripts/setup-minttick-android-signing.ps1
   ```
3. The script creates (or **reuses**) a PKCS12 release keystore under
   `$HOME/.minttick/signing/minttick-release.p12` and prompts for its password.
   **Do not regenerate the keystore** on the next release.
4. The script sends the keystore and passwords through GitHub CLI to encrypted
   repository secrets over its secure API (the values never appear in the
   command line, console or repository) and pins the public certificate hash.
5. Make a separate encrypted offline backup of the keystore and store the
   password in a private password manager. Losing the key can make future
   in-place Android upgrades impossible.

Repository configuration (names only; never post values):
- Secret `MINTTICK_ANDROID_KEYSTORE_BASE64` — same private PKCS12 bytes, base64
- Secret `MINTTICK_ANDROID_KEY_ALIAS` — `minttick`
- Secret `MINTTICK_ANDROID_STORE_PASSWORD`
- Secret `MINTTICK_ANDROID_KEY_PASSWORD` — identical to the store password for PKCS12
- Repository variable `MINTTICK_ANDROID_CERT_SHA256` — **public** SHA-256 certificate fingerprint

Verify the names in GitHub → Settings → Secrets and variables → Actions.
The CI job will **fail closed** if any are missing, if the keystore is corrupt,
or if its certificate differs from the pinned fingerprint.

## Releasing

Use the existing gated `MintTick - verified backend-connected Android release`
request/dispatch process from `dev`, which checks real API HTTPS health,
Cognito settings, and exact release request before dispatching
`Phase 11 - Android APK delivery`. Do not enable CloudFront or deploy the
website; the Android release is independent.

- Approved `workflow_dispatch` or reviewed `push` release uses the stored
  permanent private key.
- Pull request workflows **never use the private key**. Any APK from a PR run
  is an explicitly named **CI-only debug-signed artifact**, not suitable
  for updating a user's installed app.
- Release builds use increasing `--build-number=100000 + github.run_number`.
  The workflow verifies the produced APK certificate using Android
  `apksigner`, compares it to the pinned certificate hash, and records the
  APK SHA-256 before upload.
- The temporary key is stored only in the GitHub runner's ephemeral temp
  directory, mode 600, then removed on success or failure.
- Confirm on a real Android device: install the first stable APK, sign in,
  close/reopen, then install the next stable signed APK over it. Check that
  app data and restored sign-in remain intact.

**Limitations:** A session still requires valid Cognito refresh credentials
and backend account verification. Explicit logout, token expiry, revocation,
clearing app data, or uninstalling will require new sign-in. Stable signing
does not extend Cognito token lifetime and does not bypass authentication.

## Rollback and emergency recovery

Never rotate the signing key casually. Restoring older signed APKs with lower
versionCode may be blocked by Android's downgrade protection; publish a
verified older source revision with a **new higher versionCode and the same
signing key** instead. Do not ship credentials or private keys in artifacts.
