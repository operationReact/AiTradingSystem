# MintTick — understated welcome and original sonic identity

## Product direction
- Uses the **existing approved rising-M vector mark and MintTick wordmark**
  from `minttick_brand.dart`. No new symbol, arrow, candles, typography or spelling.
- Only a subtle 1.45-second fade/translate/perspective settle with a muted teal
  halo. No particle field, lens flare, strobe or metallic 3D re-render.
- Original ~1.55-second two-note sound locally bundled as PCM WAV. Playback
  requires explicit opt-in, uses a conservative volume, respects silent mode,
  and is never a requirement for authentication.
- On first launch a guest sees the introduction. It is marked as seen
  only after continuing; subsequent launches go directly to the sign-in
  screen (until real identity sessions are integrated). Returning authenticated
  users will be routed to Home when session restoration exists.
- Replay is available through Settings -> Brand introduction. Never open
  unlicensed market-data endpoints or request secrets on the welcome screen.
- Reduced-motion platform preference removes the visual movement.
- Silent-device behavior and Android/iOS/web audio playback still require
  real-device acceptance. Browser autoplay is intentionally avoided.
- Sign-in backend remains a separate implementation milestone; this PR
  does not invent a working identity provider or enable real-money trading.

## Why understated
The visual identity should remain legible at small sizes and the sound should
be recognizable without competing with the user's music or system focus.
High-frequency chirps, aggressive bass, rising-volume sweeps, and automated
sound on every opening would make this look like a game rather than a financial
tool. Audio preference is stored only on device.
