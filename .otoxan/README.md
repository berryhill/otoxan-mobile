# Otoxan Mobile Operating Pack

This repo is for the Ray-Ban Meta / phone-mediated Otoxan Mobile app.

## Rules

- Start audio-first. Do not add DAT camera complexity until the voice loop works.
- Do not assume a glasses HUD exists on regular Ray-Ban Meta Wayfarers.
- Do not design for always-on background camera or hidden recording.
- Keep responses short by default for glasses audio.
- Android/Kotlin is the first implementation target unless Matt redirects.
- Follow `docs/android-minimal-patterns.md` for the first Android implementation.
- Keep the initial Android app boring: Kotlin, single app module, route-check UI, push-to-talk, backend round trip, response playback.
- Do not add DAT/camera permissions, background capture, wake word, or multi-module architecture before the audio loop works.
- Prefer explicit route status and visible user controls over implicit Bluetooth/audio behavior.
- No commits or pushes by agents unless Matt explicitly authorizes them.

## Key docs

- `README.md` — product/MVP overview.
- `docs/mvp.md` — first-build scope.
- `docs/meta-dat-notes.md` — DAT constraints and source summary.
- `docs/android-minimal-patterns.md` — canonical minimal Android implementation doctrine.
