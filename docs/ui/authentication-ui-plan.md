# Authentication UI Plan — Locked Mockup Contract

Reference: the approved 8-panel AiTradingSystem authentication mockup generated on 2026-11-01.

## Product rule

The authentication UI must stay visually aligned with the approved mockup: deep navy background, cyan/purple neon accents, compact premium mobile spacing, centered AI Trading branding, rounded dark inputs, gradient primary actions, and no product bottom navigation inside the auth journey.

Google and Apple authentication are explicitly out of scope. The customer signs in with email or phone number plus password. Fingerprint sign-in is available only after the user has enabled it for this app on a device that already has biometrics enrolled.

## Screen sequence

- [ ] 1. **Sign In** — email or phone number, password, remember me, forgot password, primary Sign In, conditional Sign in with Fingerprint, Sign Up entry.
- [ ] 2. **Sign Up** — full name, email, phone number, password, confirm password, password-quality checklist, Create Account, Sign In return.
- [ ] 3. **Verify Phone** — six-digit OTP, resend countdown, Verify & Continue.
- [ ] 4. **Verify Email** — six-digit OTP, resend countdown, Verify & Continue.
- [ ] 5. **Enable Fingerprint** — benefit cards, device-only explanation, Enable Fingerprint, Maybe Later.
- [ ] 6. **Fingerprint Sign In** — fingerprint hero, biometric prompt, Use Password Instead fallback.
- [ ] 7. **Sign-in Success** — success confirmation and workspace-loading transition surface.
- [x] 8. **Home handoff** — existing product Home remains the destination after production authentication is wired; Home itself is not redesigned as part of this auth slice.

## Functional boundaries

- Application must open on Sign In when there is no authenticated customer session.
- Sign In username accepts either email address or phone number.
- Registration collects both email and phone so both contact channels can be verified and either may later be used to sign in.
- Fingerprint UI must use the device biometric API; the app never stores fingerprint data.
- Enabling fingerprint means opting this app into device biometric re-authentication. It does not enroll a fingerprint into Android/iOS itself.
- A successful fingerprint challenge is not by itself a server session. Production implementation must bind it to a securely stored/revocable auth token; the UI slice must not fabricate a real trading session.
- PAPER/LIVE trading behavior, Angel One connectivity, market data, and trading execution are not changed by this UI work.

## Acceptance

Each screen must pass Flutter analyzer/tests, render without overflow at 390x844, preserve the approved visual hierarchy, and remain usable at the existing mobile responsive test widths. The PR stays draft until the user visually approves the auth flow on a physical Android build.
