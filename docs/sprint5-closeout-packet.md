# Sprint 5 Closeout Packet

Generated: 2026-07-07T07:10:46Z

## Obligation / domain theme

Close Sprint 5 source/build/backend-smoke readiness for the Otoxan Mobile explicit-session audio loop without claiming physical Ray-Ban Meta hardware closure. Sprint 5 remains bounded by the speculative readiness policy: audio-first Android/Kotlin MVP, push-to-talk, short assistant response, visible phone transcript, no DAT/camera permission expansion, no wake word, no hidden always-on capture, and no raw-audio persistence.

## Code or system surface

- Sprint 5 policy boundary: `docs/sprint5-speculative-readiness-policy.md`
- Android app build: `app/build/outputs/apk/debug/app-debug.apk`
- Android route/proof UI and scorecard: `app/src/main/java/com/otoxan/mobile/ui/*`
- Android voice timing contract: `app/src/main/java/com/otoxan/mobile/voice/XanderVoiceClient.kt`
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

- `test_voice_turn_server.py`: 57 tests passed.
- `test_realtime_voice_server.py`: 5 tests passed.
- `test_moonshine_stt_command.py`: 4 tests passed.
- Python backend/realtime/Moonshine-wrapper total: 66 tests passed.

```bash
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
./gradlew :app:testDebugUnitTest
```

Result: `BUILD SUCCESSFUL in 17s`.

```bash
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"
```

Result: `BUILD SUCCESSFUL in 18s`.

APK readback:

```text
path: app/build/outputs/apk/debug/app-debug.apk
size_bytes: 24592998
sha256: 23e706fe13ae0ad7673a4ec8267a9b33daae82b59592f7e116595c44762045cb
baked_debug_endpoint: http://100.126.0.110:8787/voice-turn
```

Repo-local backend smoke used the Moonshine-first target on alternate localhost port `8789` because port `8787` was already occupied by an existing Python listener:

```bash
ss -ltnp | grep -E ':(8787|8789)\b' || true
# LISTEN 0 5 0.0.0.0:8787 0.0.0.0:* users:(("python",pid=1150914,fd=4))

JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
make backend-moonshine VOICE_HOST=127.0.0.1 VOICE_PORT=8789

OTOXAN_EXPECT_PROVIDER=mobile-fast \
/home/silas/.hermes/hermes-agent/venv/bin/python tools/smoke_voice_turn.py \
  http://127.0.0.1:8789/voice-turn \
  --expect-provider mobile-fast \
  --timeout 30
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
  "sttLatencyMs": 1534,
  "primarySttStatus": "command-error",
  "primarySttMs": 142,
  "primarySttProvider": "moonshine-stt",
  "fallbackSttStatus": "empty",
  "fallbackSttMs": 1391,
  "fallbackSttProvider": "hermes-stt",
  "sttBudgetRemainingMs": 0,
  "pass1Status": "stt-empty",
  "pass1Ready": false,
  "assistantText": "Audio arrived, but words did not decode."
}
```

Evidence classification:

- Source/build proof: passed for Python tests, Android JVM tests, and debug APK assembly.
- Backend smoke proof: passed for repo-local `/voice-turn` request/response plumbing, PCM stats, provider readback, STT split-budget fields, and no-fake-transcript behavior.
- Latency tuning/readback: `sttLatencyMs=1534` is a smoke-lane STT miss against the locked Sprint 4 `<=1500 ms` target.
- Candidate readiness: yes, for the next measured physical gate.
- Hardware gate proof: not produced in this dispatch.

## Gap

No fresh physical phone + Ray-Ban Meta run was executed in this worktree. The backend smoke used synthetic 10 ms PCM and therefore validates request/response plumbing, STT fallback readback, timing fields, and no-fake-transcript behavior; it does not validate real HFP routing, real wearer speech, real Moonshine package decode, semantic answer quality, or Ray-Ban speaker playback.

The first smoke attempt used a 10 second client timeout and timed out before the backend completed the synthetic no-speech STT fallback path. The successful readback required a 30 second smoke timeout and still reported `sttLatencyMs=1534`, above the locked Sprint 4 `1500 ms` target. That is a latency tuning/readback miss for this backend-smoke lane, not hardware evidence.

## Risk

- Over-claiming Sprint 5 as hardware closure would hide missing real Ray-Ban route proof.
- The Moonshine command lane returned `command-error`, so the smoke path exercised Hermes fallback rather than a successful local Moonshine decode.
- The synthetic no-speech STT fallback exceeded the locked 1500 ms target by 34 ms.
- Port `8787` was already occupied; physical operators must verify intended backend ownership before installing/running the phone APK.
- A build/source/APK pass can create false confidence if evidence labels are omitted from hardware reports.

## Remediation

- Keep this packet classified as source/build/backend-smoke closeout and candidate readiness only.
- Before the physical gate, stop or identify the existing `8787` listener and start the intended real backend lane explicitly.
- If Moonshine local decode is required for the run, install/configure the Moonshine-compatible provider and rerun `make backend-moonshine` until smoke or a real speech sample shows `transcriptSource=moonshine-stt`, `sttStatus=success`, and `sttLatencyMs <= 1500`.
- Preserve no-fake-transcript behavior: when STT is empty, surface `Audio arrived, but words did not decode.` and do not call route metadata user speech.
- Report every future run with separate evidence classes: hardware gate, capture reliability, backend turn reliability, latency scorecard, and source/build proof.

## Verification

Completed verification:

- Python backend/realtime/Moonshine-wrapper tests passed: 66 total tests.
- Android JVM tests passed.
- Debug APK assembled with the phone-reachable default endpoint.
- APK hash and size recorded above.
- Repo-local backend smoke returned `ok=true`, `provider=mobile-fast`, sanitized audio stats, STT split-budget fields, and honest `pass1Ready=false` / `pass1Status=stt-empty` for synthetic no-speech audio.
- No DAT/camera, wake word, always-on/background recording, raw-audio persistence, or AR/lens-overlay scope expansion was added.

Required hardware verification before product/hardware closure:

1. Pair Ray-Ban Meta Wayfarers to the Android phone as Bluetooth audio.
2. Start `make backend-moonshine` or the selected real backend lane and verify the phone-reachable endpoint/port owner.
3. Install `app/build/outputs/apk/debug/app-debug.apk`.
4. Press/hold to talk in Otoxan Mobile using real wearer speech.
5. Record visible UI/backend fields: input/output route, `pass1Ready`, `pass1Status`, `transcriptSource`, `sttProvider`, `sttStatus`, `sttLatencyMs`, `primarySttStatus`, `fallbackSttStatus`, `backendRoundTripMs`, `turnTotalMs`, playback route, and semantic assistant response.
6. Report evidence classes separately: hardware gate, capture reliability, backend turn reliability, latency scorecard, and source/build proof.
