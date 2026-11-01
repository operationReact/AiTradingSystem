# Android PAPER physical-device acceptance

This gate must run on a real, attached Android phone. Emulator CI and APK packaging are prerequisites, not physical-device acceptance.

## Safety boundary

- Start the backend locally in `PAPER` mode with LIVE unavailable.
- Use an ephemeral local operator key. Do not commit it, paste it into an issue, or upload the generated debug APK.
- The runner accepts only `http://localhost` or `http://127.0.0.1` and reaches it through `adb reverse`.
- Cleartext networking is enabled only by the Android debug manifest. Release builds retain the platform default.
- The runner fails if backend health does not report `executionMode=PAPER` and `liveAvailable=false`.

## Prerequisites

1. PostgreSQL and the Spring Boot backend are running locally on port 8080.
2. The backend's `APP_API_KEY` is an ephemeral test value.
3. Android platform tools and Flutter 3.47.x are installed.
4. Exactly one phone is attached with USB debugging authorized.

Confirm the phone before starting:

```bash
adb devices -l
curl --fail http://127.0.0.1:8080/actuator/health
```

## Connected prerequisite run

From the repository root:

```bash
export CONNECTED_API_KEY='<same ephemeral APP_API_KEY used by the local backend>'
python scripts/run_android_device_acceptance.py
```

The runner verifies the backend safety state, installs/runs the existing connected Flutter integration test on the selected phone, and writes:

- `build/device-acceptance/android-paper-device.json`
- `build/device-acceptance/flutter-device-test.log`

The JSON deliberately records `paperLifecycleJourney=PENDING_MANUAL_DEVICE_EVIDENCE`. A passing health journey must not be misreported as complete lifecycle acceptance.

## Required manual product evidence

After the connected prerequisite passes, run the customer build against the same local backend and record phone screenshots or screen recording for all of the following:

1. Home shows `PAPER · MANUAL/AUTOMATED`, Risk Engine active, automation state, and LIVE locked.
2. AI shows the seeded opportunity in customer language.
3. Orders shows the server-proven automated BUY as `Auto executed`.
4. Portfolio shows the open position and protective levels.
5. Orders/history shows the automated protective SELL reason and `Position closed`.
6. Portfolio/history shows the persisted realized P&L.
7. Emergency Stop pauses automation and the authoritative state survives refresh.
8. No screen offers LIVE activation or broker-order submission.

Link the exact backend commit, Flutter commit, device model/Android version, evidence files, and reviewer result in the acceptance record.

## Completion rule

V2-PAPER-09 remains pending until both conditions hold on the same reviewed candidate:

- the automated backend lifecycle acceptance is green; and
- the customer-visible lifecycle checklist above is captured on a physical Android device.

Do not promote packaging, emulator evidence, connected health, or this runbook itself to physical-device acceptance.
