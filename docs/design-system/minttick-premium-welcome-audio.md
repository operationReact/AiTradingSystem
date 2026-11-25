# MintTick: approved brand-first opening screen

**Visual source:** owner-approved opening mockup from 2026-11-06. Use the
repository's canonical `MintTickMark` / `MintTickLockup` unchanged; the
`minttick_brand.dart` mark remains the source of truth. Do not redraw,
restyle, add candles/arrows or change the MintTick wordmark.

## Experience
- Quiet navy surface; faint teal radial ambient glow behind the logo.
- Approved original mark and wordmark centered approximately in the upper
  half, with a nearly invisible grounding line. No headlines, slogans,
  product claims, chart decorations, or extra brand labels.
- Bottom fixed visual hierarchy: **Sign in**, **Create account**, and
  restrained **Sound off · Replay intro** controls.
- The logo animates in over 1.45 seconds with a restrained fade/translate,
  <=0.06 rad perspective settle. The original vectors never change shape.
- Optional locally bundled two-note audio is OFF initially. It only plays
  after a direct user interaction, never on silent unwanted autoplay. It
  respects device silent mode.
- Small screens scroll rather than overflow; reduced motion bypasses
  visible animation. Login controls are never delayed by animation/audio.
- A welcome replay opened from Settings has a Done CTA to return safely,
  rather than signing out an already authenticated account.

## Boundaries and follow-on
- Distinct Sign in and Create account routes are wired, but both remain
  fail-closed placeholders until the production identity-provider flows
  have been implemented. Do not claim login/registration is complete.
- The first-run intro state is stored locally (best effort); returning
  visitors are sent directly to Sign in. Never gate launch on preferences.
- This UI change does not deploy AWS, enable live trading or share Angel
  One market data with unauthenticated users.
