# Sprint 4 STT Budget Lock

Generated: 2026-07-07T04:55:26Z

## Obligation / domain theme

Lock Sprint 4 STT work to a narrow latency budget derived from Sprint 3 closeout evidence without weakening the Otoxan Mobile hardware gate. The budget is a tuning/readback contract for the explicit-session Ray-Ban/phone voice loop; it is not a substitute for a fresh real phone + Ray-Ban Meta run.

## Code or system surface

- Android timing contract constants: `app/src/main/java/com/otoxan/mobile/voice/XanderVoiceClient.kt`
- Android latency scorecard/readback: `app/src/main/java/com/otoxan/mobile/ui/OtoxanUiState.kt` and `app/src/main/java/com/otoxan/mobile/ui/OtoxanScreen.kt`
- Repo-local voice-turn STT dispatcher: `tools/voice_turn_server.py`
- Sprint 3 closeout evidence: `docs/sprint3-streaming-closeout-packet.md`
- Sprint 3 scope lock: `docs/sprint3-gate-scope.md`
- Historical physical proof: `docs/pass1-closeout.md` and `docs/hardware-validation-gate.md`

## Current evidence

Sprint 3 closeout keeps `/voice-turn` as the canonical fallback and hardware baseline, with source/build/backend smoke evidence only. The closeout explicitly requires a fresh physical phone + Ray-Ban Meta run before claiming hardware closure.

The accepted timing targets already used by the mobile proof surface are:

| Field | Locked target | Evidence class |
| --- | ---: | --- |
| `ttfaMs` | `1500` ms | perceived-latency tuning/readback |
| `postCaptureAckDelayMs` | `250` ms | local feedback tuning/readback |
| `backendRoundTripMs` | `4000` ms | backend turn tuning/readback |
| `turnTotalMs` | `8000` ms | end-to-end tuning/readback |
| `sttLatencyMs` | `1500` ms | Sprint 4 STT budget/readback |

The Sprint 4 STT lock is therefore:

```text
sttLatencyMs target: 1500 ms
repo-local total STT budget default: 1.5 s
Moonshine/local primary command budget default: 0.75 s
Hermes fallback minimum reserve default: 0.25 s
Hermes fallback lane max default: 0.75 s
```

This preserves a bounded local-STT-first path while leaving room for fallback before the backend turn consumes the 4000 ms backend target.

## Gap

Sprint 3 did not produce fresh hardware STT latency distribution data. The lock uses the existing scorecard target and closeout discipline as the Sprint 4 starting budget, not as proof that every real turn now meets it.

## Risk

- A loose STT timeout can consume the backend turn budget and increase perceived latency.
- A tight STT timeout can cause fallback or empty-transcript outcomes on weak/quiet captures.
- Treating the STT budget as a hardware pass/fail gate would over-claim tuning evidence.
- Changing STT defaults without visible readback would hide which lane consumed the budget.

## Remediation

Sprint 4 STT work must use the locked `1500` ms STT target as the default budget and readback target. The repo-local backend now derives its default `OTOXAN_STT_TOTAL_BUDGET_SECONDS` from `TIMING_CONTRACT_TARGETS["sttLatencyMs"]`. Android metrics now publish `sttLatencyMs` in the timing contract targets, and the UI scorecard uses the same shared constant instead of a hard-coded STT bar value.

Override environment variables remain available for evidence runs only:

```bash
OTOXAN_STT_TOTAL_BUDGET_SECONDS=<seconds>
OTOXAN_MOONSHINE_STT_TIMEOUT_SECONDS=<seconds>
OTOXAN_STT_FALLBACK_MIN_SECONDS=<seconds>
OTOXAN_HERMES_STT_FALLBACK_TIMEOUT_SECONDS=<seconds>
```

The fallback max is a hard upper bound for the Hermes STT fallback lane after
Moonshine/local STT returns empty or unavailable. If that bounded fallback cannot
produce text, the backend must keep the no-fake-transcript behavior: it reports
the route-evidence fallback transcript source and says the words did not decode
instead of sending route metadata to Xander as if it were user speech.

Treat tuned builds as measurement artifacts until repeated real Ray-Ban/phone turns justify promoting a new default.

## Verification

Source verification for this lock:

```bash
make test-backend test-moonshine-wrapper
./gradlew :app:testDebugUnitTest
```

Hardware verification still requires a real phone + Ray-Ban Meta run. A valid Sprint 4 STT report must include route evidence, `pass1Ready`, `pass1Status`, transcript source/status, `sttLatencyMs`, STT budget/fallback fields if present, `backendRoundTripMs`, `turnTotalMs`, and semantic response confirmation.
