# Sprint 4 STT Closeout Packet

Generated: 2026-07-07T05:34:01Z

## Obligation / domain theme

Close Sprint 4 source/build/backend-smoke work for the Otoxan Mobile explicit-session STT path without claiming a fresh Ray-Ban Meta hardware pass. Sprint 4 keeps the product loop audio-first: phone app, push-to-talk, bounded STT, short assistant response, Android/Ray-Ban playback fallback, no DAT/camera, no wake word, and no hidden always-on capture.

## Code or system surface

- Android app build: `app/build/outputs/apk/debug/app-debug.apk`
- Android route/proof UI and scorecard: `app/src/main/java/com/otoxan/mobile/ui/*`
- Android voice client timing contract: `app/src/main/java/com/otoxan/mobile/voice/XanderVoiceClient.kt`
- Repo-local voice-turn backend: `tools/voice_turn_server.py`
- Repo-local Moonshine-compatible wrapper: `tools/moonshine_stt_command.py`
- Smoke client: `tools/smoke_voice_turn.py`
- Sprint 4 budget contract: `docs/sprint4-stt-budget-lock.md`
- Realtime/STT telemetry contract: `docs/stream-event-protocol.md`

## Current evidence

Source and build verification completed in the task worktree.

```bash
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
make test-backend test-realtime test-moonshine-wrapper
```

Result:

- `test_voice_turn_server.py`: 51 tests passed.
- `test_realtime_voice_server.py`: 4 tests passed.
- `test_moonshine_stt_command.py`: 4 tests passed.

```bash
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
./gradlew :app:testDebugUnitTest
```

Result: `BUILD SUCCESSFUL in 4s`.

```bash
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"
```

Result: `BUILD SUCCESSFUL in 2s`.

APK readback:

```text
path: app/build/outputs/apk/debug/app-debug.apk
size_bytes: 24576614
sha256: 8d0052023b1530a1527abc99d2509f675991f416bff87697726386a7a7bf9824
baked_debug_endpoint: http://100.126.0.110:8787/voice-turn
```

Repo-local backend smoke used the Moonshine-first target on an alternate localhost port because port `8787` was already occupied by an existing Python listener:

```bash
make backend-moonshine VOICE_HOST=127.0.0.1 VOICE_PORT=8789
OTOXAN_EXPECT_PROVIDER=mobile-fast make smoke-backend VOICE_ENDPOINT=http://127.0.0.1:8789/voice-turn
```

Smoke result:

```json
{
  "ok": true,
  "provider": "mobile-fast",
  "bytesReceived": 320,
  "audioFormat": "pcm_s16le_16khz_mono",
  "transcriptSource": "route-evidence-fallback",
  "sttProvider": "hermes-stt",
  "sttStatus": "empty",
  "sttLatencyMs": 1821,
  "primarySttStatus": "command-error",
  "primarySttMs": 25,
  "primarySttProvider": "moonshine-stt",
  "fallbackSttStatus": "empty",
  "fallbackSttMs": 1795,
  "fallbackSttProvider": "hermes-stt",
  "sttBudgetRemainingMs": 0,
  "pass1Status": "stt-empty",
  "pass1Ready": false,
  "assistantText": "Audio arrived, but words did not decode."
}
```

## Gap

No fresh physical phone + Ray-Ban Meta run was executed in this worktree. The backend smoke used synthetic 10 ms PCM and therefore validates request/response plumbing, STT fallback readback, timing fields, and no-fake-transcript behavior; it does not validate real HFP routing, real wearer speech, real Moonshine package decode, semantic answer quality, or Ray-Ban speaker playback.

The smoke STT latency was `1821 ms`, above the Sprint 4 `sttLatencyMs <= 1500 ms` target. That is evidence that the synthetic no-speech fallback path can exceed the target in this environment, not evidence of hardware failure. It should be treated as a tuning/readback miss for the smoke lane.

## Risk

- Over-claiming this packet as hardware closure would hide the missing real Ray-Ban route proof.
- Moonshine command mode is wired, but the local command returned `command-error` in smoke, so the smoke path exercised Hermes fallback rather than a successful local Moonshine decode.
- Fallback STT consumed the full budget and exceeded the locked 1500 ms target in the synthetic no-speech case.
- Port `8787` was already occupied; physical operators should confirm the intended backend process before installing/running the phone APK.

## Remediation

- Keep this packet classified as source/build/backend-smoke closeout only.
- For the next physical gate, start the intended real backend lane explicitly and verify port ownership before phone testing.
- If Moonshine local decode is required for the run, install/configure the Moonshine-compatible provider and rerun `make backend-moonshine` until smoke shows `transcriptSource=moonshine-stt`, `sttStatus=success`, and `sttLatencyMs <= 1500` on a real speech sample.
- Preserve no-fake-transcript behavior: when STT is empty, surface `Audio arrived, but words did not decode.` and do not call route metadata user speech.

## Verification

Completed verification:

- Python backend/realtime/Moonshine-wrapper tests passed: 59 total tests.
- Android JVM tests passed.
- Debug APK assembled with the phone-reachable default endpoint.
- APK hash and size recorded above.
- Repo-local backend smoke returned `ok=true`, `provider=mobile-fast`, sanitized audio stats, STT split-budget fields, and honest `pass1Ready=false` / `pass1Status=stt-empty` for synthetic no-speech audio.

Required hardware verification before product/hardware closure:

1. Pair Ray-Ban Meta Wayfarers to the Android phone as Bluetooth audio.
2. Start `make backend-moonshine` or the selected real backend lane and verify the phone-reachable endpoint.
3. Install `app/build/outputs/apk/debug/app-debug.apk`.
4. Press/hold to talk in Otoxan Mobile using real wearer speech.
5. Record visible UI/backend fields: input/output route, `pass1Ready`, `pass1Status`, `transcriptSource`, `sttProvider`, `sttStatus`, `sttLatencyMs`, `primarySttStatus`, `fallbackSttStatus`, `backendRoundTripMs`, `turnTotalMs`, playback route, and semantic assistant response.
6. Report evidence classes separately: hardware gate, capture reliability, backend turn reliability, latency scorecard, and source/build proof.
