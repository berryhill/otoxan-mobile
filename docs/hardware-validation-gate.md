# Hardware Validation Gate and Closure Evidence Packet

Generated: 2026-07-06T23:48:05Z

## Obligation / domain theme

Close MVP 1 hardware validation only on real phone + Ray-Ban Meta evidence. Source-level tests and proof/debug modes can support the gate, but they cannot satisfy it by themselves.

## Code or system surface

- Android app: `app/src/main/java/com/otoxan/mobile/`
- Backend adapter: `tools/voice_turn_server.py`
- Phone UI proof card: `Voice loop proof` in `OtoxanScreen.kt`
- Existing route packet: `docs/mvp0-route-proof-verification.md`
- Existing Pass 1 closeout: `docs/pass1-closeout.md`

## Closure status

Status: hardware gate satisfied for Pass 1 audio loop, with residual latency/quality risks still open.

The accepted hardware loop is:

```text
Ray-Ban Meta mic
-> Android Otoxan Mobile capture
-> repo-local /voice-turn adapter
-> Hermes STT lane
-> Hermes profile xander model lane
-> Android/Ray-Ban playback
```

Do not claim DAT/camera proof from this packet. Do not claim always-on or background capture. This packet closes the explicit-session audio loop only.

## Current evidence

Physical Ray-Ban/phone turns recorded in `docs/pass1-closeout.md` showed:

- input route: `RB Meta 03YS`
- route type: `TYPE_BLUETOOTH_SCO`
- capture size: `160000` bytes
- capture duration: `5000` ms
- transcript source: `hermes-stt`
- STT status: `success`
- backend provider: `xander-session`
- observed non-silent speech peaks around `4815`, `5990`, and `6397`
- operator confirmation that the app answered spoken questions through the voice loop

The backend gate fields are first-class response fields in `tools/voice_turn_server.py`:

- `pass1Ready`
- `pass1Status`
- `transcriptSource`
- `sttStatus`
- `provider`
- `routeEvidence`
- `audioStats`
- latency/timing fields

The Android proof card displays the same gate fields:

- `Pass 1: REAL SPEECH PROVEN` only when `pass1Ready == true`
- provider
- transcript source
- STT provider/status/latency
- captured bytes and backend bytes
- backend audio duration/peak/rms
- TTS PCM bytes

## Acceptance contract

A Pass 1 hardware turn is accepted only when all of these are true on a real phone/Ray-Ban run:

1. `pass1Ready=true`
2. `pass1Status=real-speech-proven`
3. `transcriptSource=hermes-stt` or `transcriptSource=moonshine-stt`
4. `sttStatus=success`
5. `provider=xander-session` or `provider=mobile-fast`
6. `routeEvidence.inputName` or `routeEvidence.outputName` identifies the Ray-Ban device
7. `routeEvidence.inputType` or `routeEvidence.outputType` is `TYPE_BLE_HEADSET` or `TYPE_BLUETOOTH_SCO`
8. captured/backend bytes match the expected 16 kHz mono PCM turn size for the test duration
9. backend audio peak/rms prove non-silent audio
10. assistant response answers the semantic phrase, not route fallback text

## Explicit non-evidence

The following are diagnostics, not closure evidence:

- `provider=proof`
- `transcriptSource=debug`
- `transcriptSource=route-evidence-fallback`
- `pass1Status=proof-mode-not-real-speech`
- `pass1Status=debug-transcript-not-real-speech`
- `pass1Status=stt-empty` or other STT fallback statuses
- phone/default route while claiming glasses capture
- APK build success without a real Ray-Ban route turn

## Gap

The codebase does not store screenshots, raw logcat, or raw audio. That is intentional for privacy, but it means future hardware regressions need a structured operator transcript/log summary instead of relying on durable raw artifacts.

## Risk

- The gate can be over-claimed if build proof is confused with hardware proof.
- STT latency and voice quality still vary.
- Ray-Ban SCO/communication routing can regress across Android or Meta firmware updates.
- Very quiet captures can still produce weak turns.

## Remediation

Use this packet as the closure/readback checklist for future hardware sessions:

1. Run backend: `make backend` or `make backend-moonshine`.
2. Build/install phone app against the phone-reachable endpoint.
3. Pair Ray-Ban Meta Wayfarers and open Otoxan Mobile.
4. Tap `Check audio route`; verify Ray-Ban route evidence.
5. Run a single semantic phrase turn, for example: `Xander, say pineapple if you heard me.`
6. Confirm the phone proof card reports `REAL SPEECH PROVEN`.
7. Record a text summary of the proof card fields and route evidence.
8. Treat proof/debug/fallback turns as failed closure, even if the rest of the app works.

## Verification

Source verification commands from repo root:

```bash
python3 app/src/test/python/test_voice_turn_server.py
python3 app/src/test/python/test_realtime_voice_server.py
python3 app/src/test/python/test_moonshine_stt_command.py
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"
```

Hardware verification command/path:

```bash
make backend
make build VOICE_ENDPOINT=http://100.126.0.110:8787/voice-turn
make reinstall
make launch
```

Closure readback requirement: separate source/build evidence from hardware evidence. A clean test/build result proves package health; only the real Ray-Ban/phone turn proves the hardware gate.
