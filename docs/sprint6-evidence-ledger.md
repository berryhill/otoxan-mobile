# Sprint 6 Evidence Ledger

Generated: 2026-07-07T16:14:50Z

## Obligation / domain theme

Lock Sprint 6 as source/build/runtime-contract evidence for the Otoxan Mobile explicit-session audio loop. Sprint 6 must not claim physical Ray-Ban Meta hardware closure unless a fresh phone + Ray-Ban run provides route, capture, STT, assistant, playback, and latency readback.

## Code or system surface

- MiniMax/mobile-fast runtime contract: `docs/mobile-fast-minimax-runtime-contract.md`
- Backend runtime descriptor: `tools/voice_turn_server.py::mobile_fast_runtime_contract()`
- Voice-turn response readback: `/voice-turn` field `mobileFastRuntimeContract`
- Backend tests: `app/src/test/python/test_voice_turn_server.py`
- Android response/timing parser: `app/src/main/java/com/otoxan/mobile/voice/XanderVoiceClient.kt`
- Prior closeout boundary: `docs/sprint5-closeout-packet.md`
- Evidence-class policy: `docs/sprint5-speculative-readiness-policy.md`
- STT budget policy: `docs/sprint4-stt-budget-lock.md`

## Current evidence

Sprint 6 adds a secret-free runtime contract for the mobile-fast/MiniMax-compatible lane:

```text
contract.name=otoxan-mobile-minimax-runtime
contract.version=1
providerMode=mobile-fast
apiCompatibility=openai_chat_completions
endpointSuffix=/chat/completions
reasoningSplit=true
defaultRequestTimeoutSeconds=4
defaultHardTimeoutSeconds=4.0
evidenceClass=runtime_contract_readback_not_hardware_proof
```

The contract is now included in every `/voice-turn` response as `mobileFastRuntimeContract`. Existing mobile-fast telemetry remains top-level and in `timing` where already applicable:

```text
provider
mobileFastProvider
mobileFastModel
mobileFastTimeoutSeconds
mobileFastHardTimeoutSeconds
xanderFastMs
xanderFastStatus
xanderFastTimedOut
xanderFallbackSessionStatus
xanderFallbackSkipped
```

This locks the reporting rule: MiniMax/mobile-fast evidence must name the underlying provider/model and timeout/readiness fields per turn. `provider=mobile-fast` alone is not enough evidence.

## Gap

No fresh physical phone + Ray-Ban Meta hardware turn is produced by this source task. This ledger proves code/test contract readback only. It does not prove Bluetooth HFP routing, real wearer speech decode, real MiniMax semantic answer quality, Ray-Ban speaker playback, or hardware-gate closure.

## Risk

- Runtime/model evidence can be over-claimed as hardware proof if the evidence class is omitted.
- A `mobile-fast` provider label can hide which underlying provider/model actually ran.
- Without explicit timeout/readback fields, a slow or failed provider can be mistaken for app, route, or STT failure.
- Without the STT gate, route evidence could be converted into fake transcript content; Sprint 6 preserves the no-fake-transcript behavior.

## Remediation

- Require future smoke and hardware reports to include `mobileFastRuntimeContract.evidenceClass` plus the provider/model/timing fields above.
- Keep the Sprint 5 rule: source/build/backend-smoke evidence supports candidate readiness only; physical closure requires a fresh real phone + Ray-Ban turn.
- Keep the Sprint 4 STT budget labels separate from hardware gate labels.
- Do not store or print provider API keys in mobile telemetry, docs, or smoke output.

## Verification

Completed verification in this worktree:

```bash
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
make test-backend test-realtime test-moonshine-wrapper
```

Result:

- `test_voice_turn_server.py`: 62 tests passed, including `test_mobile_fast_runtime_contract_is_secret_free_and_minimax_compatible` and `test_voice_turn_response_includes_mobile_fast_runtime_contract`.
- `test_realtime_voice_server.py`: 5 tests passed.
- `test_moonshine_stt_command.py`: 4 tests passed.
- Python backend/realtime/Moonshine-wrapper total: 71 tests passed.

```bash
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
./gradlew :app:testDebugUnitTest
```

Result: `BUILD SUCCESSFUL in 19s`.

```bash
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"
```

Result: `BUILD SUCCESSFUL in 5s`.

APK readback:

```text
path: app/build/outputs/apk/debug/app-debug.apk
size_bytes: 24592998
sha256: 8cd77494fd7c62a7881381d64be6c96207a72fbeb1c706b30824fd9271a016c0
baked_debug_endpoint: http://100.126.0.110:8787/voice-turn
```

No DAT/camera, wake word, always-on/background recording, raw-audio persistence, AR/lens overlay, or separate glasses app scope was added.

Reference verification commands:

```bash
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
make test-backend test-realtime test-moonshine-wrapper

JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
./gradlew :app:testDebugUnitTest
```

Expected proof points:

- Backend tests pass, including contract descriptor and response readback tests.
- Realtime and Moonshine-wrapper tests remain green.
- Android JVM tests remain green with the existing top-level mobile-fast fields.
- No DAT/camera, wake word, always-on/background recording, raw-audio persistence, AR/lens overlay, or separate glasses app scope is added.

## Operator reporting template

Use this shape for Sprint 6+ evidence reports:

```text
evidence_class=runtime_contract_readback_not_hardware_proof | backend_smoke | hardware_gate
provider=<provider from /voice-turn>
mobileFastProvider=<underlying provider>
mobileFastModel=<underlying model>
mobileFastTimeoutSeconds=<seconds>
mobileFastHardTimeoutSeconds=<seconds>
xanderFastStatus=<1 success, 0 failed, null not called>
xanderFastTimedOut=<1 timed out, 0 not timed out, null not called>
sttProvider=<stt provider>
sttStatus=<stt status>
transcriptSource=<source>
pass1Ready=<true|false>
pass1Status=<status>
latency=<pass|miss|unknown with field names>
hardware_gate=<proven|not_proven|not_run>
```
