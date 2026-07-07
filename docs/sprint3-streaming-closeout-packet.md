# Sprint 3 Streaming Closeout Packet

Generated: 2026-07-07T04:39:32Z

## Obligation / domain theme

Close out Sprint 3 streaming work without weakening the Otoxan Mobile MVP boundary. The stream surfaces are allowed only as explicit-session transport and diagnostics for the same audio-first Ray-Ban/phone assistant turn. They must not become always-on capture, background recording, camera/DAT scope, AR UI, or a replacement for fresh phone + Ray-Ban hardware proof.

## Code or system surface

- Android debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Android client stream/fallback seam: `app/src/main/java/com/otoxan/mobile/voice/XanderVoiceClient.kt`
- Android proof/telemetry UI: `app/src/main/java/com/otoxan/mobile/ui/OtoxanScreen.kt`
- Canonical HTTP backend: `tools/voice_turn_server.py` `/voice-turn`
- Experimental backend NDJSON stream shim: `tools/voice_turn_server.py` `/voice-stream`
- Realtime WebSocket skeleton: `tools/realtime_voice_server.py` `/realtime`
- Protocol document: `docs/stream-event-protocol.md`
- Scope guardrail: `docs/sprint3-gate-scope.md`

## Current evidence

### Source and build evidence

Commands executed in this dispatch after provisioning repo-local toolchain dependencies for this worktree:

```bash
make test-backend test-realtime test-moonshine-wrapper
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
./gradlew :app:testDebugUnitTest
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"
```

Results:

- Python backend unit tests passed: `make test-backend test-realtime test-moonshine-wrapper`.
  - `test_voice_turn_server.py`: 46 tests passed.
  - `test_realtime_voice_server.py`: 4 tests passed.
  - `test_moonshine_stt_command.py`: 4 tests passed.
- Android JVM tests passed: `./gradlew :app:testDebugUnitTest`.
  - Gradle result: `BUILD SUCCESSFUL in 12s`.
  - 24 actionable tasks executed.
- Debug APK assembled: `./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"`.
  - Gradle result: `BUILD SUCCESSFUL in 5s`.
  - APK path: `app/build/outputs/apk/debug/app-debug.apk`
  - APK size: `24M`
  - APK sha256: `27dd53b903bda287b5268670a86cf19debf2bba6e4dcb9c2d61acf41bde83f98`

### Canonical HTTP fallback smoke evidence

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

Interpretation: the canonical `/voice-turn` fallback still accepts the Ray-Ban-shaped route packet, validates PCM, returns assistant text/TTS bytes, and correctly refuses to mark proof-mode smoke as real hardware speech.

### Experimental NDJSON stream smoke evidence

Command:

```bash
OTOXAN_VOICE_PROVIDER=proof OTOXAN_EXPERIMENTAL_STREAM_TRANSPORT=1 \
  python tools/voice_turn_server.py --host 127.0.0.1 --port 8790
# POST one fake Ray-Ban-shaped PCM turn to http://127.0.0.1:8790/voice-stream
```

Smoke result:

- HTTP status: `200`
- content type: `application/x-ndjson`
- event types: `stream.started`, `response.completed`, `stream.completed`
- sequences: `1`, `2`, `3`
- wrapped `voiceTurn.provider=proof`
- wrapped `voiceTurn.bytesReceived=320`
- wrapped `voiceTurn.pass1Status=proof-mode-not-real-speech`
- wrapped `voiceTurn.pass1Ready=false`
- canonical fallback advertised: `POST /voice-turn`, `same_request_response_contract`
- privacy flags advertised: `explicitSessionOnly=true`, `rawAudioPersisted=false`, `alwaysOnRecording=false`

Interpretation: `/voice-stream` is only a stream-shaped transport shim over the existing `/voice-turn` contract. It does not create a second assistant authority surface and does not promote proof-mode transport success into hardware proof.

## Gap

No fresh physical phone + Ray-Ban Meta run was executed in this dispatch. No `adb install`, app launch, route-check readback, logcat proof, real STT transcript, or operator semantic-turn confirmation was produced. Streaming is therefore source/build/backend-smoke ready only; hardware status remains `requires fresh phone + Ray-Ban run`.

## Risk

- Treating stream smoke as hardware proof would hide routing, Bluetooth SCO/BLE headset, microphone, speaker, STT, and phone-network regressions.
- Treating `/voice-stream` as the new canonical assistant path before hardware proof could fragment evidence and fallback behavior.
- The proof backend intentionally reports `pass1Ready=false`; promoting it would violate the Sprint 3 gate discipline.
- The generated APK is pullable build evidence, but it has not been installed or run on the target phone in this dispatch.

## Remediation

Decision: Sprint 3 streaming is closed as candidate-ready source/build/backend evidence, with `/voice-turn` retained as canonical fallback and hardware baseline. The next gate must be a real phone + Ray-Ban Meta run using this APK/backend candidate.

Operator checklist for the physical gate:

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
7. Record latency separately from hardware proof: `ttfaMs`, `postCaptureAckDelayMs`, `backendRoundTripMs`, and `turnTotalMs`.
8. Keep stream/VAD diagnostics non-authoritative until they are compared against the same real hardware run.

## Verification

Verified in this dispatch:

```bash
make test-backend test-realtime test-moonshine-wrapper
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"
python tools/smoke_voice_turn.py http://127.0.0.1:8789/voice-turn --expect-provider proof --timeout 10
# POST /voice-stream with OTOXAN_EXPERIMENTAL_STREAM_TRANSPORT=1
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Not verified in this dispatch:

```text
adb install/launch on target phone
fresh Ray-Ban Meta route check
fresh real-speech STT turn
fresh proof-card/timing readback
```
