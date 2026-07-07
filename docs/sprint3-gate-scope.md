# Sprint 3 Gate Scope Lock

Generated: 2026-07-07T03:07:50Z

## Obligation / domain theme

Lock Sprint 3 work to the evidence-backed mobile gate that follows Sprint 2 / Pass 1 closeout. Otoxan Mobile remains an explicit-session, audio-first Android bridge for Ray-Ban Meta Wayfarers. Sprint 3 must harden the proven voice loop and produce fresh physical gate evidence; it must not expand into DAT/camera, always-on capture, AR UI, or a broad backend reorganization before the audio loop is reliable.

## Code or system surface

- Android app: `app/src/main/java/com/otoxan/mobile/`
- Route/proof UI: `app/src/main/java/com/otoxan/mobile/ui/OtoxanScreen.kt`
- Capture/playback surfaces: `MicCapture.kt`, `AudioRouter.kt`, `SpeechPlayback.kt`
- Repo-local voice-turn helper: `tools/voice_turn_server.py`
- Realtime transport skeleton: `tools/realtime_voice_server.py`
- Sprint 2 / Pass 1 closeout evidence: `docs/pass1-closeout.md`
- Hardware gate contract: `docs/hardware-validation-gate.md`
- Sprint 3 source/build/backend candidate packet: `docs/sprint3-gate-decision-packet.md`
- Minimal Android doctrine: `docs/android-minimal-patterns.md`

## Current evidence

Sprint 2 / Pass 1 proved the core explicit-session voice loop with real phone + Ray-Ban Meta evidence:

```text
Ray-Ban Meta mic
-> Android Otoxan Mobile capture
-> repo-local /voice-turn adapter
-> Hermes STT lane
-> Hermes profile xander model lane
-> Android/Ray-Ban playback
```

Recorded closeout fields:

- input route: `RB Meta 03YS`
- route type: `TYPE_BLUETOOTH_SCO`
- capture size: `160000` bytes
- capture duration: `5000` ms
- transcript source: `hermes-stt`
- STT status: `success`
- backend provider: `xander-session`
- non-silent speech peaks around `4815`, `5990`, and `6397`
- operator confirmation that the app answered spoken questions through the voice loop

The Sprint 3 decision packet adds current source/build/backend candidate evidence:

- backend tests passed for voice-turn, realtime, and Moonshine wrapper surfaces
- Android JVM tests passed
- debug APK assembled for `http://100.126.0.110:8787/voice-turn`
- proof-mode backend smoke passed while correctly reporting `pass1Ready=false`

## Locked Sprint 3 gate scope

Sprint 3 is locked to these gate obligations:

1. Preserve the Pass 1 hardware contract.
   - A passing hardware gate still requires a real phone + Ray-Ban Meta turn.
   - The phone proof card/backend response must show `pass1Ready=true`, `pass1Status=real-speech-proven`, successful STT, Ray-Ban route evidence, non-silent audio stats, and a semantic assistant response.

2. Harden the push-to-talk voice loop.
   - Improve capture reliability, route readback, client disconnect behavior, and playback confidence without changing the MVP into an always-on listener.
   - Keep the visible phone transcript/proof-card readback as the operator evidence surface.

3. Improve Xander turn quality inside the current backend edge.
   - Keep `tools/voice_turn_server.py` as the active mobile edge while voice quality, personality, and latency are hardened.
   - Moonshine/local STT and local TTS seams may be used as bounded improvements, but they must preserve the existing `/voice-turn` contract and honest fallback statuses.

4. Measure latency as a separate tuning class.
   - Record `ttfaMs`, `postCaptureAckDelayMs`, `backendRoundTripMs`, and `turnTotalMs` for physical runs.
   - Treat timing scorecards as tuning/readback evidence, not as a substitute for hardware proof.

5. Keep source/build/backend proof separate from physical proof.
   - Gradle tests, APK assembly, and proof backend smoke can qualify a candidate for a hardware session.
   - They cannot close the Sprint 3 physical gate without a fresh phone + Ray-Ban turn.

## Explicit non-scope

Sprint 3 must not claim or prioritize:

- DAT camera/photo capture
- AR overlay or glasses display UI
- standalone glasses app behavior
- wake-word, always-on, or background recording
- hidden capture or persistent raw audio storage
- broad backend/service reorganization before the audio loop is hardened
- treating proof/debug/fallback transcripts as real speech proof
- treating APK build success as hardware gate closure

## Gap

No fresh physical phone + Ray-Ban Meta regression run is recorded in this dispatch. Sprint 2 closeout remains valid historical proof of the architecture, and the Sprint 3 decision packet proves candidate readiness, but Sprint 3 gate closure still needs a new physical proof-card readback after the current candidate APK/backend is installed and exercised.

## Risk

- Scope creep into DAT/camera/realtime platform work could delay hardening of the voice loop that is already product-critical.
- Over-claiming source/build proof as hardware proof would weaken the gate and hide Ray-Ban routing regressions.
- Backend reorganization before the route/capture/playback loop is stable could create migration risk without improving the user-visible gate.
- Latency improvements could be misreported as hardware proof if timing and proof classes are not kept separate.

## Remediation

Use this locked scope as the Sprint 3 execution boundary:

1. Prepare candidate APK/backend from the current mainline.
2. Run the physical gate with real phone + Ray-Ban Meta Wayfarers.
3. Record a text proof-card summary containing route, STT, provider, pass status, audio stats, semantic response, and canonical timing fields.
4. Accept Sprint 3 gate closure only if the hardware acceptance contract in `docs/hardware-validation-gate.md` passes.
5. Queue any DAT/camera, always-on, backend reorganization, or broad realtime platform work outside the Sprint 3 gate unless explicitly re-approved by the operator.

## Verification

Document verification performed in this dispatch:

```bash
git status --short --branch
read docs/pass1-closeout.md
read docs/hardware-validation-gate.md
read docs/sprint3-gate-decision-packet.md
```

Required next verification for gate closure:

```text
Install candidate APK on target phone.
Start real backend lane: make backend or make backend-moonshine.
Pair Ray-Ban Meta Wayfarers.
Run route check and one semantic push-to-talk turn.
Record proof-card fields and timing readback.
Confirm pass1Ready=true and pass1Status=real-speech-proven.
```
