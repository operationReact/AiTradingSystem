# Mobile delivery baseline

The React app now includes a compact bottom navigation, viewport safe-area handling and standalone web-app manifest. This is a **mobile web/PWA preparation**, not a released Android or iOS app.

## Android

- Serve the built frontend over HTTPS and verify `/manifest.webmanifest` and `/signalforge-icon.svg` return correct content types (not SPA HTML fallback).
- For a Play Store build, choose a verified Trusted Web Activity (TWA) or a reviewed native wrapper; configure Android Digital Asset Links and signing identity before release.
- Verify install, full-screen behavior, back navigation, deep links, screen reader labels and all device sizes.

## iOS

- Verify Safari Add to Home Screen and standalone behavior on a physical iPhone.
- For App Store distribution, build a native iOS shell with sufficient native functionality and review Apple requirements. PWA metadata alone is not an App Store package.
- Generate production raster PNG app icons (including 180x180 Apple touch icon); SVG fallback in this initial change is not a substitute for store-ready assets.

## Safety

- No service worker or offline order queue is enabled: stale/offline data must never be used to submit trades.
- No LIVE order path is enabled. Keep PAPER default until separate authorization and safety gates pass.
- Validate HTTPS, operator auth/session handling and broker secrets before shipping to stores.
