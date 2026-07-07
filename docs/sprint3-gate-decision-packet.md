# Sprint 3 Gate Decision Packet

Generated: 2026-07-07T02:43:39Z

## Obligation / domain theme

Decide whether the Sprint 3 mobile gate can move forward from source/build/backend evidence without over-claiming fresh hardware proof. Otoxan Mobile remains an explicit-session, audio-first Android/Ray-Ban bridge. Build proof and backend smoke proof support release readiness; only a real phone + Ray-Ban Meta run can satisfy or renew hardware proof.

## Code or system surface

- Android app: `app/src/main/java/com/otoxan/mobile/`
- Android build: `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`
- Repo-local voice-turn backend: `tools/voice_turn_server.py`
- Backend smoke client: `tools/smoke_voice_turn.py`
- Hardware gate packet: `docs/hardware-validation-gate.md`
- Pass 1 hardware closeout: `docs/pass1-closeout.md`

## Current evidence

### Source and build evidence

- Python backend unit tests passed: `make test-backend test-realtime test-moonshine-wrapper`
  - `test_voice_turn_server.py`: 42 tests passed.
  - `test_realtime_voice_server.py`: 4 tests passed.
  - `test_moonshine_stt_command.py`: 4 tests passed.
- Android JVM tests passed: `./gradlew :app:testDebugUnitTest`
  - Gradle result: `BUILD SUCCESSFUL in 6s`.
  - 24 actionable tasks executed.
- Debug APK assembled: `./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"`
  - Gradle result: `BUILD SUCCESSFUL in 5s`.
  - APK path: `app/build/outputs/apk/debug/app-debug.apk`
  - APK size: `24M`
  - APK sha256: `0c802657dcddebd9ae7445a786387333da93ab8ac0d83b85c504a8e3424e4607`

### Backend smoke evidence

Command:

```bash
OTOXAN_VOICE_PROVIDER=proof python tools/voice_turn_server.py --host 127.0.0.1 --port 8789
python tools/smoke_voice_turn.py http://127.0.0.1:8789/voice-turn --expect-provider proof --timeout 10
```

Smoke result:

- `ok=true`
- `provider=proof`
- `bytesReceived=320`
- `audioFormat=pcm_s16le_16khz_mono`
- `audioStats.bytes=320`
- `audioStats.samples=160`
- `audioStats.durationMs=10`
- `audioStats.peak=513`
- `audioStats.rms=513.0`
- `transcriptSource=proof`
- `sttProvider=not-run`
- `sttStatus=not-run`
- `backendTotalMs=2`
- `pass1Status=proof-mode-not-real-speech`
- `pass1Ready=false`
- `tts_bytes=25600`

Interpretation: the local `/voice-turn` contract accepts the Ray-Ban-shaped route packet, validates PCM fields, returns assistant text and TTS bytes, and correctly refuses to mark proof mode as real hardware speech.

### Existing hardware evidence

`docs/pass1-closeout.md` and `docs/hardware-validation-gate.md` record prior real phone + Ray-Ban evidence:

- input route: `RB Meta 03YS`
- route type: `TYPE_BLUETOOTH_SCO`
- capture size: `160000` bytes
- capture duration: `5000` ms
- transcript source: `hermes-stt`
- STT status: `success`
- backend provider: `xander-session`
- observed non-silent speech peaks around `4815`, `5990`, and `6397`
- operator confirmation that the app answered spoken questions through the voice loop

## Gap

No fresh physical phone + Ray-Ban Meta run was executed in this dispatch. The current packet proves source health, APK assembly, and backend contract health. It does not create new runtime hardware evidence, route evidence, logcat evidence, or operator proof-card readback.

## Risk

- Over-claiming source/build evidence as hardware readiness would weaken the gate discipline.
- The proof backend intentionally reports `pass1Ready=false`; treating it as a hardware pass would hide STT/route regressions.
- The generated APK is pullable build evidence, but it has not been installed or run on the target phone in this dispatch.
- Existing Pass 1 hardware proof remains valid historical evidence, but Sprint 3 should not treat it as a fresh regression pass after code or environment drift.

## Remediation

Decision: proceed to the Sprint 3 physical gate with this APK/backend as the candidate, but keep hardware status at `requires fresh phone + Ray-Ban run` until a real route turn is recorded.

Gate operator checklist:

1. Start the real backend lane: `make backend` or `make backend-moonshine`.
2. Install `app/build/outputs/apk/debug/app-debug.apk` on the phone.
3. Pair Ray-Ban Meta Wayfarers and open Otoxan Mobile.
4. Tap `Check audio route`; record route name/type.
5. Run one semantic turn, for example: `Xander, say pineapple if you heard me.`
6. Accept hardware only if the proof card shows:
   - `pass1Ready=true`
   - `pass1Status=real-speech-proven`
   - `transcriptSource=hermes-stt` or `moonshine-stt`
   - `sttStatus=success`
   - `provider=xander-session` or `mobile-fast`
   - Ray-Ban route evidence via `TYPE_BLUETOOTH_SCO` or `TYPE_BLE_HEADSET`
   - non-silent backend audio stats
   - assistant response answers the semantic phrase
7. Record canonical timing separately from hardware proof: `ttfaMs`, `postCaptureAckDelayMs`, `backendRoundTripMs`, and `turnTotalMs`.

## Verification

Verified in this dispatch:

```bash
make test-backend test-realtime test-moonshine-wrapper
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"
OTOXAN_VOICE_PROVIDER=proof python tools/voice_turn_server.py --host 127.0.0.1 --port 8789
python tools/smoke_voice_turn.py http://127.0.0.1:8789/voice-turn --expect-provider proof --timeout 10
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Not verified in this dispatch:

```text
adb install/launch on target phone
fresh Ray-Ban Meta route check
fresh real-speech STT turn
fresh proof-card/timing readback
```
