# Mobile-fast MiniMax Runtime Contract

Generated: 2026-07-07T16:14:50Z

## Obligation / domain theme

Lock the wearable-safe runtime contract for the Otoxan Mobile `mobile-fast` assistant lane. This contract makes the MiniMax/OpenAI-compatible model call explicit without moving controller authority into the phone app or treating backend/model readback as Ray-Ban hardware proof.

## Code or system surface

- Contract descriptor: `tools/voice_turn_server.py::mobile_fast_runtime_contract()`
- Runtime readback fields: `/voice-turn` response `mobileFastRuntimeContract`, `mobileFastProvider`, `mobileFastModel`, `mobileFastTimeoutSeconds`, `mobileFastHardTimeoutSeconds`, `xanderFastMs`, `xanderFastStatus`, `xanderFastTimedOut`, `xanderFallbackSessionStatus`, and `xanderFallbackSkipped`
- Provider mode: `OTOXAN_VOICE_PROVIDER=mobile-fast`
- Provider selector: `OTOXAN_MOBILE_FAST_PROVIDER`
- Model selector: `OTOXAN_MOBILE_FAST_MODEL`
- Deadline knobs: `OTOXAN_MOBILE_FAST_TIMEOUT_SECONDS` and `OTOXAN_MOBILE_FAST_HARD_TIMEOUT_SECONDS`

## Current evidence

The repo-local backend now emits a secret-free contract object with:

```text
name=otoxan-mobile-minimax-runtime
version=1
providerMode=mobile-fast
apiCompatibility=openai_chat_completions
endpointSuffix=/chat/completions
reasoningSplit=true
adapterParser=minimax-m3-chat-completions-parser
emptyContentEvidence=true
defaultRequestTimeoutSeconds=4
defaultHardTimeoutSeconds=4.0
maxSpokenWords=16
secretMaterialInTelemetry=false
evidenceClass=runtime_contract_readback_not_hardware_proof
```

The runtime request body remains intentionally bounded for glasses audio:

```text
model=<configured provider model, e.g. MiniMax-M3>
messages=[system voice contract, user route/transcript]
max_tokens=OTOXAN_MOBILE_FAST_MAX_TOKENS or 96
temperature=OTOXAN_MOBILE_FAST_TEMPERATURE or 0.2
reasoning_split=true
```

The response parser is named `minimax-m3-chat-completions-parser`. It reads speakable audio only from `choices[0].message.content`, strips `<think>...</think>` markup before shaping, records whether MiniMax-style reasoning evidence was present (`reasoning_content`, `reasoningContent`, `reasoning`, `reasoning_details`, or think markup), and reports sanitized empty-content evidence without copying private reasoning text into spoken output or error telemetry.

The provider call is gated behind real STT success or an explicit debug transcript. If STT is empty and only route evidence exists, the backend returns `Audio arrived, but words did not decode.` and does not call the model with route metadata as fake user speech.

## Gap

This contract locks runtime shape and readback. It does not prove live MiniMax availability, semantic answer quality, phone/Ray-Ban routing, real wearer speech, playback, or hardware-gate closure. A configured provider may still be absent or unhealthy at runtime; that must be detected by `xanderFastStatus`, `xanderFastTimedOut`, and smoke/physical run evidence.

## Risk

- Without explicit readback, a physical report can confuse `provider=mobile-fast` with the actual underlying model/provider.
- Without the STT gate, route evidence could be over-used as fake transcript content.
- Without hard deadlines, a cloud/provider stall can make the glasses loop unusable.
- Without `reasoning_split=true` and output shaping, MiniMax-compatible reasoning output can leak into spoken audio.

## Remediation

- Treat `mobileFastRuntimeContract` as the stable contract readback for Sprint 6 and later smoke reports.
- Require every MiniMax/mobile-fast evidence report to include the underlying `mobileFastProvider`, `mobileFastModel`, timeout values, `xanderFastStatus`, `xanderFastTimedOut`, STT status/source, and evidence class.
- Keep secrets out of telemetry: never print provider API keys or config values beyond provider/model names.
- Preserve the fallback behavior: provider failures produce a short degraded spoken reply; empty STT produces the no-decode message.

## Verification

Completed verification in this worktree:

```bash
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
make test-backend test-realtime test-moonshine-wrapper
```

Result: 71 Python tests passed: 62 backend, 5 realtime, and 4 Moonshine-wrapper tests.

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

Result: `BUILD SUCCESSFUL in 5s`; APK `app/build/outputs/apk/debug/app-debug.apk`, size `24592998`, sha256 `8cd77494fd7c62a7881381d64be6c96207a72fbeb1c706b30824fd9271a016c0`.

Reference verification commands:

```bash
JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
make test-backend test-realtime test-moonshine-wrapper

JAVA_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/jdks/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/profiles/xander/workspace/tools/android-sdk \
./gradlew :app:testDebugUnitTest
```

Required readback:

- Python backend tests include `test_mobile_fast_runtime_contract_is_secret_free_and_minimax_compatible`.
- Python backend tests include `test_voice_turn_response_includes_mobile_fast_runtime_contract`.
- Android JVM tests continue to parse existing mobile-fast response fields without requiring the app to own provider credentials.
