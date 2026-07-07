# Sprint 1 Hardware Sweep Protocol

Generated: 2026-07-07T02:09:35Z

## Obligation / domain theme

Convert the Sprint 1 threshold packet into an executable operator protocol for real phone + Ray-Ban Meta validation. This protocol preserves the proven explicit-session `/voice-turn` push-to-talk loop, measures reliability and latency against the current thresholds, and prevents build/source proof from being reported as hardware proof.

## Code or system surface

- Sprint packet: `docs/hardware-threshold-comparison.md`
- Hardware gate: `docs/hardware-validation-gate.md`
- Pass 1 closeout: `docs/pass1-closeout.md`
- Android app proof card: `app/src/main/java/com/otoxan/mobile/ui/OtoxanScreen.kt`
- Capture thresholds: `app/src/main/java/com/otoxan/mobile/voice/MicCapture.kt`
- Backend adapter: `tools/voice_turn_server.py`
- Realtime diagnostic VAD: `tools/realtime_voice_server.py`
- Operator commands: `Makefile`

## Current evidence baseline

Pass 1 already proved the core audio loop with real Ray-Ban route evidence:

- route/device: `RB Meta 03YS`
- route type: `TYPE_BLUETOOTH_SCO`
- capture size: `160000` bytes for 5 seconds at 16 kHz mono PCM16
- transcript source/status: `hermes-stt` / `success`
- backend provider: `xander-session`
- `pass1Ready=true` and `pass1Status=real-speech-proven`
- observed peak amplitudes around `4815`, `5990`, and `6397`

Sprint 1 does not reopen the Pass 1 closure claim. It checks whether that loop stays reliable across repeated real-world cases and whether threshold or latency tuning is justified by hardware evidence.

## Gap this protocol closes

`docs/hardware-threshold-comparison.md` identified the need for at least 10 real Ray-Ban turns across normal speech, quiet speech, noisy room, clipped/too-short speech, and silence. This document turns that recommendation into a runbook with prerequisites, exact commands, run matrix, evidence fields, pass/fail rules, and closeout criteria.

## Non-goals and safety boundaries

- Do not add DAT/camera testing to this sweep.
- Do not use wake word, always-on recording, background recording, or hidden capture.
- Do not persist raw audio by default.
- Do not count `provider=proof`, debug transcript, route fallback transcript, phone/default route, or APK build success as hardware proof.
- Do not tune thresholds during the run. Record evidence first; tune only in a separate change with before/after hardware evidence.
- Keep realtime/VAD diagnostic-only. It can be compared against the same run data, but it must not become the default commit policy from this sweep alone.

Realtime/VAD diagnostic comparison is now exposed in the summary endpoint rather than as a gate. `GET /hardware-sweep/recent?limit=20` returns per-run `realtimeVadDiagnostic` fields and a top-level `realtimeVadComparison`. These fields compare the diagnostic `energy-vad-phase3` threshold (`peakThreshold=700`, `endSilenceChunks=3`) against the same sweep peaks, but `diagnosticOnly=true` means the result cannot promote realtime/VAD into default turn commit policy or replace `/voice-turn` hardware proof.

## Prerequisites

Hardware:

- Android phone with Otoxan Mobile installed or ready to install.
- Ray-Ban Meta Wayfarers paired to the phone as Bluetooth audio.
- Test operator able to speak normal, quiet, clipped/short, and silence turns.
- Optional noisy-room condition, or controlled speaker/background noise if available.

Workstation/backend:

- Repo root is `otoxan-mobile`.
- Phone can reach the backend endpoint, defaulting to `http://100.126.0.110:8787/voice-turn` unless overridden.
- Android SDK/adb is available on the workstation used for install/launch.
- Backend provider is real enough for the gate: `mobile-fast` or `xander-session`; not `proof` for accepted hardware turns.

Privacy:

- Capture only the text run sheet in this protocol.
- Do not save raw PCM/audio or screenshots unless the operator explicitly chooses to capture a debugging artifact.
- If a raw artifact is temporarily captured for debugging, record it as a separate exceptional artifact and delete it after extracting the text fields needed for the run sheet.

## Preflight source/build verification

Run these from the repo root before touching hardware:

```bash
python3 app/src/test/python/test_voice_turn_server.py
python3 app/src/test/python/test_realtime_voice_server.py
python3 app/src/test/python/test_moonshine_stt_command.py
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"
```

Expected source/build result:

- Python backend tests pass.
- Android JVM tests pass.
- Debug APK builds with the intended endpoint.

Source/build success is package health only. It does not satisfy any hardware run.

## Hardware setup commands

Use these in separate terminals as needed.

1. Print configuration:

```bash
make endpoint VOICE_ENDPOINT=http://100.126.0.110:8787/voice-turn
make doctor VOICE_ENDPOINT=http://100.126.0.110:8787/voice-turn
```

2. Start real backend provider:

```bash
make backend VOICE_HOST=0.0.0.0 VOICE_PORT=8787
```

Alternative if the test explicitly requires the legacy Xander/Hermes provider:

```bash
make backend-xander VOICE_HOST=0.0.0.0 VOICE_PORT=8787
```

3. Build, install, and launch the app against the phone-reachable endpoint:

```bash
make build VOICE_ENDPOINT=http://100.126.0.110:8787/voice-turn
make reinstall
make launch
```

4. Optional log observation during the sweep:

```bash
make logs
```

Logcat is diagnostic only. The accepted evidence packet is the run sheet below.

## Required route check before every scenario group

At minimum before each scenario group, and after any Bluetooth disconnect/reconnect:

1. Pair/connect Ray-Ban Meta Wayfarers.
2. Open Otoxan Mobile.
3. Tap `Check audio route`.
4. Record route fields in the run sheet.
5. Continue only if at least one of input/output identifies the Ray-Ban device and at least one route type is `TYPE_BLE_HEADSET` or `TYPE_BLUETOOTH_SCO`.

If the app reports phone/default route, stop the group and record the run disposition as `invalid-route`. Do not claim glasses capture.

## Sweep matrix

Run at least 10 turns. Use exactly one explicit push-to-talk/capture action per turn.

| Run | Scenario | Prompt/action | Expected result |
| ---: | --- | --- | --- |
| 1 | normal speech | `Xander, say pineapple if you heard me.` | Real STT success, semantic answer includes or acknowledges pineapple |
| 2 | normal speech repeat | `Xander, what route are you hearing me through?` | Real STT success, assistant answer is semantic rather than route fallback |
| 3 | quiet speech | Quietly say `Xander, say quiet signal.` | Either accepted real speech or measured quiet-speech failure |
| 4 | quiet speech repeat | Quietly ask a short factual phrase | Same as above; do not tune mid-run |
| 5 | noisy room | `Xander, say noise check.` with background noise | Measure STT and threshold behavior |
| 6 | noisy room repeat | Ask a different short phrase with same noise | Measure repeatability |
| 7 | clipped/too-short speech | Start then stop after a very short phrase such as `Hi.` | Prefer rejected/failed usability over false proof |
| 8 | clipped/too-short repeat | Stop before completing `Xander, say...` | Prefer rejected/failed usability over false proof |
| 9 | silence | Capture with no speech | Must not produce `real-speech-proven` |
| 10 | silence repeat | Capture with no speech, route still Ray-Ban | Must not produce `real-speech-proven` |

Optional expansion runs:

- 11-12: move phone/glasses position and repeat normal speech.
- 13-14: reconnect Bluetooth and repeat route check + normal speech.
- 15-16: compare backend mode if explicitly needed, but do not mix providers inside the primary 10-run acceptance set unless noted.

## Per-run evidence fields

Record this text row for every run. `unknown` is better than invented data.

```text
runId:
scenario: normal | quiet | noisy | clipped | silence | optional
operatorIntent:
backendProviderExpected: mobile-fast | xander-session
backendProviderObserved:
inputName:
inputType:
outputName:
outputType:
wearableRouteActive: true | false | unknown
captureDurationMs:
capturedBytes:
backendBytesReceived:
expectedBytesForDuration:
audioFormat:
backendPeak:
backendRms:
clientStopReason:
transcript:
assistantText:
transcriptSource:
sttProvider:
sttStatus:
sttLatencyMs:
pass1Ready: true | false
pass1Status:
ttfaMs:
postCaptureAckDelayMs:
backendRoundTripMs:
turnTotalMs:
canonicalTimingTargetResult: pass | miss | mixed | unknown
operatorHeardPlaybackOnRayBan: true | false | unknown
operatorNotes:
runDisposition: accept | expected-reject | unexpected-fail | invalid-route | invalid-provider | invalid-debug
```

## Timing scorecard rule

For each run with non-empty timing fields, label the canonical timing scorecard:

- `ttfaMs <= 1500`
- `postCaptureAckDelayMs <= 250`
- `backendRoundTripMs <= 4000`
- `turnTotalMs <= 8000`

Disposition:

- `pass`: all present fields meet target and no required timing field is missing.
- `miss`: one or more present fields miss target.
- `mixed`: at least one pass and at least one miss where some fields are missing.
- `unknown`: timing fields are absent or not readable.

Timing affects sprint tuning priority. It does not override `pass1Ready` or hardware-gate evidence.

## Run disposition rules

Use these rules after each run:

- `accept`: real Ray-Ban route, real provider, successful real STT, non-silent backend stats, semantic assistant answer, and `pass1Ready=true` / `pass1Status=real-speech-proven`.
- `expected-reject`: silence or clipped test rejects/does not prove speech without falsely reporting `real-speech-proven`.
- `unexpected-fail`: normal, quiet, or noisy speech fails despite valid Ray-Ban route and real provider.
- `invalid-route`: route evidence does not identify Ray-Ban or route type is not `TYPE_BLE_HEADSET` / `TYPE_BLUETOOTH_SCO`.
- `invalid-provider`: provider is `proof` or another non-accepted provider.
- `invalid-debug`: transcript source/status is debug, fallback, empty STT, or otherwise non-real proof.

## Threshold decision rules after the sweep

Do not change thresholds unless the run sheet supports it.

- Keep `conversationVoiceCaptureConfig().speechPeakAmplitude=900` if normal and quiet runs accept with comfortable peak/rms margin.
- Consider one measured lowering step only if quiet-speech runs show valid route/provider/STT setup but fail specifically on speech detection or stop boundary behavior.
- Do not lower below noise/silence evidence. Silence runs must remain `expected-reject`.
- Do not raise thresholds based only on strong normal-speech peaks; quiet speech must remain protected.
- Treat realtime VAD `700` as diagnostic until it has matching hardware evidence across the same scenarios.
- Read `realtimeVadComparison` only as a comparison aid. `rejectScenarioTriggerCount > 0` or `speechScenarioMissCount > 0` is a realtime/VAD follow-up signal, not a reason to alter the push-to-talk gate inside the same sweep.

## Closeout criteria

The Sprint 1 sweep is complete when:

1. At least 10 real turns are recorded in the run sheet.
2. The run set includes normal, quiet, noisy, clipped/too-short, and silence scenarios.
3. Every accepted speech run has Ray-Ban route evidence and real provider/STT evidence.
4. Silence and clipped tests do not falsely report `real-speech-proven`.
5. Timing scorecard results are summarized separately from hardware proof.
6. Any threshold recommendation cites exact run IDs and before/after risk.
7. The closeout explicitly separates source/build evidence from hardware evidence.

## Closeout summary template

```text
Sweep date:
Device/OS:
Ray-Ban firmware/app state if known:
Backend provider:
Endpoint:
APK/build identifier or git commit:
Total runs:
Accepted speech runs:
Expected rejects:
Unexpected failures:
Invalid route/provider/debug runs:
Normal speech result:
Quiet speech result:
Noisy room result:
Clipped/short result:
Silence result:
Timing scorecard summary:
Threshold recommendation:
Realtime/VAD recommendation:
Residual risks:
Operator conclusion: pass | pass-with-followups | fail
```

## Verification

Protocol verification is complete when this file exists in the repo and the linked Sprint packet points operators here for execution. Hardware verification remains pending until an operator runs this protocol on a real phone + Ray-Ban Meta pair and records the run sheet.
