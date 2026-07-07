# Hardware Threshold Comparison Packet and Next-Sprint Recommendation

Generated: 2026-07-07T01:46:31Z

## Obligation / domain theme

Keep Otoxan Mobile's Ray-Ban voice loop honest after Pass 1: compare the current capture/VAD/latency thresholds against the real hardware evidence, identify which thresholds are gate criteria versus tuning baselines, and recommend the next sprint without confusing build proof with hardware proof.

## Code or system surface

- Pass 1 closeout evidence: `docs/pass1-closeout.md`
- Hardware gate packet: `docs/hardware-validation-gate.md`
- Android capture thresholds: `app/src/main/java/com/otoxan/mobile/voice/MicCapture.kt`
- Client timing contract: `app/src/main/java/com/otoxan/mobile/voice/XanderVoiceClient.kt`
- Realtime VAD skeleton thresholds: `tools/realtime_voice_server.py`
- Phone proof/telemetry UI: `app/src/main/java/com/otoxan/mobile/ui/OtoxanScreen.kt`

## Current evidence

Pass 1 real phone + Ray-Ban Meta runs proved the explicit-session audio loop with:

- input route: `RB Meta 03YS`
- route type: `TYPE_BLUETOOTH_SCO`
- capture size: `160000` bytes for a 5 second, 16 kHz mono PCM16 turn
- transcript source: `hermes-stt`
- STT status: `success`
- backend provider: `xander-session`
- `pass1Ready=true` / `pass1Status=real-speech-proven`
- observed non-silent speech peaks around `4815`, `5990`, and `6397`

Current threshold/readback values:

| Surface | Threshold | Current value | Purpose | Evidence class |
| --- | ---: | ---: | --- | --- |
| `isUsableVoiceCapture` minimum expected bytes ratio | 80% of expected bytes | `0.8` | Reject short/truncated captures before sending | client guardrail |
| `isUsableVoiceCapture` minimum peak amplitude | peak >= `128` | `128` | Reject silent/near-empty PCM | client guardrail |
| fixed route proof speech detector | peak >= `256` | `256` | Mark speech during fixed-duration proof recording | client diagnostic |
| conversation capture speech detector | peak >= `900` | `900` | Start utterance capture only after stronger speech evidence | client turn-control threshold |
| realtime WebSocket energy VAD | peak >= `700` | `700` | Emit provisional `user.speech.started/ended` events | transport/VAD diagnostic |
| realtime end silence chunks | quiet chunks >= `3` | `3` x 100 ms chunks | Close provisional speech boundary | transport/VAD diagnostic |
| canonical TTFA target | <= `1500` ms | `1500` | First audible feedback baseline | tuning baseline, not gate proof |
| canonical post-capture ack target | <= `250` ms | `250` | Local feedback after capture | tuning baseline, not gate proof |
| canonical backend round-trip target | <= `4000` ms | `4000` | HTTP/backend wait baseline | tuning baseline, not gate proof |
| canonical total turn target | <= `8000` ms | `8000` | End-to-end turn baseline | tuning baseline, not gate proof |
| Sprint 4 STT budget target | <= `1500` ms | `1500` | STT lane budget/readback target derived from Sprint 3 closeout discipline | tuning baseline, not gate proof |

Observed Pass 1 peak margin against current speech thresholds:

| Threshold | Value | Min observed peak multiple | Max observed peak multiple |
| --- | ---: | ---: | ---: |
| client usability | `128` | `37.62x` | `49.98x` |
| fixed route proof speech | `256` | `18.81x` | `24.99x` |
| realtime VAD | `700` | `6.88x` | `9.14x` |
| conversation speech | `900` | `5.35x` | `7.11x` |

## Gap

- The real hardware evidence proves that the current thresholds catch normal spoken Ray-Ban turns with large margin, but the repo does not yet contain a structured multi-run threshold sweep for quiet speech, noisy rooms, or different Meta/Android firmware states.
- The source contains several threshold classes with different meanings. Without a comparison packet, operators can misread `128`, `256`, `700`, and `900` as conflicting pass/fail criteria.
- Timing targets are visible in the client and gate packet, but there is not yet a sprint-level decision tying those targets to concrete work sequencing.
- Realtime VAD is still a skeleton diagnostic. It must not become the default commit policy until it has hardware evidence at least as strong as the existing push-to-talk loop.

## Risk

- Lowering speech thresholds based on convenience could send silence or room noise to STT, increasing false turns and latency.
- Raising thresholds based only on the observed strong Pass 1 peaks could exclude quiet operators or different glasses fit/noise conditions.
- Treating latency targets as hardware-gate closure could over-claim the product; they are tuning baselines until measured across repeated real turns.
- Promoting realtime/VAD before proving the explicit push-to-talk loop quality could reintroduce hidden/always-on capture behavior the MVP explicitly rejects.

## Remediation

Use this threshold classification going forward:

1. Gate criteria: `pass1Ready`, `pass1Status`, real Ray-Ban route evidence, successful real STT source, non-silent backend stats, semantic assistant answer.
2. Capture guardrails: expected bytes ratio and minimum peak amplitude prevent obviously bad submissions but do not prove real speech by themselves.
3. Turn-control thresholds: conversation capture `speechPeakAmplitude=900` and silence window shape the utterance boundaries; tune only from hardware runs.
4. Transport diagnostics: realtime energy VAD `700` / 3 quiet chunks can support future streaming UX but must remain non-authoritative until hardware-proven.
5. Latency baselines: TTFA/ack/backend/total targets are sprint scorecards, not proof that the Ray-Ban loop worked.

## Next-sprint recommendation

Recommendation: run a narrow reliability-and-latency sprint before DAT/camera or backend reorganization.

Sprint objective:

- Preserve the proven `/voice-turn` push-to-talk loop.
- Collect repeated hardware telemetry against the current thresholds.
- Tune only where evidence shows a user-visible failure.
- Keep realtime/VAD behind the diagnostic seam until it has Ray-Ban hardware evidence.

Concrete sprint backlog:

1. Hardware threshold sweep packet
   - Execute `docs/hardware-sweep-protocol.md`.
   - Run at least 10 real Ray-Ban turns: normal speech, quiet speech, noisy room, clipped/too-short speech, and silence.
   - Record only text summaries: route, bytes, peak, rms, stop reason, transcript source/status, pass1 status, TTFA, ack gap, backend round trip, total turn.
   - Do not persist raw audio by default.

2. Telemetry acceptance summary
   - Add an operator-readable summary that labels each target as `pass`, `miss`, or `unknown` for the canonical v1 timing fields.
   - Keep this as readback evidence; do not let it override `pass1Ready`.

3. Conversation capture tuning
   - Leave `conversationVoiceCaptureConfig().speechPeakAmplitude=900` unchanged for the first sweep because proven peaks were at least `5.35x` above it.
   - If quiet-speech failures appear, lower in one measured step only, with before/after hardware evidence.

4. Realtime/VAD containment
   - Keep realtime VAD as a WebSocket diagnostic transport, not the default phone UX.
   - Compare its `700` threshold against the same hardware sweep before using it to auto-start/auto-stop turns.
   - Use `/hardware-sweep/recent` `realtimeVadDiagnostic` and `realtimeVadComparison` readbacks for that comparison; they are marked `diagnosticOnly=true` and do not override push-to-talk `/voice-turn` acceptance.

5. Latency work ordering
   - First reduce perceived latency with local ack and shorter spoken responses.
   - Then tune STT/Xander backend time. Sprint 4 STT tuning starts from `docs/sprint4-stt-budget-lock.md`: `sttLatencyMs <= 1500ms`, repo-local total STT budget `1.5s`, Moonshine/local primary budget `0.75s`, and fallback reserve `0.25s`.
   - Only after repeated turns hit the scorecard should the sprint consider backend service extraction.

Do not start DAT/camera in the next sprint unless the repeated audio-loop telemetry stays stable. DAT is still version 2; the MVP remains explicit-session audio.

## Verification

Source/build verification for this packet:

```bash
python3 app/src/test/python/test_voice_turn_server.py
python3 app/src/test/python/test_realtime_voice_server.py
python3 app/src/test/python/test_moonshine_stt_command.py
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"
```

Hardware verification still requires a real phone + Ray-Ban Meta run. A clean source/build result verifies package health only; it does not replace hardware proof.
