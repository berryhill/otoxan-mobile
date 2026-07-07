# Sprint 5 Speculative Readiness Policy

Generated: 2026-07-07T06:09:22Z

## Obligation / domain theme

Lock Sprint 5 planning to what Sprint 4 actually proved. Sprint 5 may prepare speculative readiness work for the Otoxan Mobile explicit-session audio loop, but it must not convert Sprint 4 source/build/backend-smoke evidence into product, hardware, or Ray-Ban route closure. Normal Ray-Ban Meta Wayfarers remain an audio-first phone peripheral for MVP work: push-to-talk, short assistant responses, visible phone transcript, no DAT/camera, no wake word, no hidden always-on capture, and no persistent raw-audio storage.

## Code or system surface

- Sprint 4 closeout evidence: `docs/sprint4-stt-closeout-packet.md`
- Sprint 4 STT budget lock: `docs/sprint4-stt-budget-lock.md`
- Realtime/STT telemetry contract: `docs/stream-event-protocol.md`
- Android proof and scorecard UI: `app/src/main/java/com/otoxan/mobile/ui/*`
- Android voice timing contract: `app/src/main/java/com/otoxan/mobile/voice/XanderVoiceClient.kt`
- Repo-local voice-turn backend: `tools/voice_turn_server.py`
- Repo README evidence-class summary: `README.md`

## Current evidence

Sprint 4 produced source/build/backend-smoke closeout evidence only:

- Python backend/realtime/Moonshine-wrapper tests passed: 59 total tests.
- Android JVM tests passed.
- Debug APK assembled with endpoint `http://100.126.0.110:8787/voice-turn`.
- APK readback was recorded as `app/build/outputs/apk/debug/app-debug.apk`, `24576614` bytes, SHA-256 `8d0052023b1530a1527abc99d2509f675991f416bff87697726386a7a7bf9824`.
- Repo-local backend smoke returned `ok=true`, `provider=mobile-fast`, sanitized audio stats, STT split-budget fields, and honest `pass1Ready=false` / `pass1Status=stt-empty` for synthetic no-speech audio.
- Smoke STT latency was `1821 ms`, which missed the Sprint 4 target of `sttLatencyMs <= 1500 ms`.
- No fresh physical phone + Ray-Ban Meta run was executed in the Sprint 4 worktree.

Therefore Sprint 5 starts from this policy boundary:

```text
Sprint 5 can claim: candidate readiness for the next measured physical gate.
Sprint 5 cannot claim: hardware proof, Ray-Ban route proof, semantic real-speech proof, or latency pass from Sprint 4 smoke alone.
```

## Gap

Sprint 4 did not produce real HFP routing, real wearer speech, real Moonshine package decode, semantic answer quality, Ray-Ban speaker playback, or a passing STT latency result. The backend smoke used synthetic 10 ms PCM, so it validates plumbing and honest fallback behavior but not the physical product loop.

## Risk

- If Sprint 5 uses Sprint 4 smoke as closure evidence, the project can ship with unverified Ray-Ban route behavior.
- If Sprint 5 treats the `1821 ms` smoke STT latency as acceptable without a measured policy decision, the Sprint 4 `1500 ms` budget loses force.
- If Sprint 5 starts broad platform work before physical-gate proof, it can distract from the narrow Android/Ray-Ban audio loop.
- If speculative work adds DAT/camera, wake word, background recording, or transcript persistence, it violates the MVP privacy boundary.

## Remediation

Sprint 5 speculative readiness is allowed only under these constraints:

1. Evidence labels are mandatory. Every Sprint 5 report must classify each result as one of: `source/build proof`, `backend smoke proof`, `latency tuning/readback`, `candidate readiness`, or `hardware gate proof`.
2. Hardware claims require a fresh physical run. `hardware gate proof` requires real phone + Ray-Ban Meta pairing, real wearer speech, visible route evidence, successful STT, semantic assistant response, playback route readback, and `pass1Ready=true` / `pass1Status=real-speech-proven` or an explicitly updated equivalent.
3. Sprint 4 STT defaults remain locked. Default `sttLatencyMs` target stays `1500 ms`; repo-local total STT budget stays `1.5 s`; Moonshine/local primary command default stays `0.75 s`; fallback reserve stays `0.25 s` unless an evidence run explicitly records the override and reason.
4. A tuning miss is not a gate pass. Any Sprint 5 run with `sttLatencyMs > 1500` must be reported as `latency scorecard=miss/stt` unless the target is deliberately revised in a separate reviewed budget lock.
5. No-fake-transcript behavior is non-negotiable. Empty STT must surface `Audio arrived, but words did not decode.` and must not send route metadata to Xander as if it were user speech.
6. MVP privacy and scope boundaries stay closed by default: no DAT/camera permissions, no wake word, no always-on/background recording, no raw-audio persistence, and no AR/lens-overlay UI.
7. Speculative readiness work should prepare the next physical gate, not replace it. Valid speculative work includes clearer runbooks, endpoint/port ownership checks, build reproducibility, telemetry labels, backend smoke hardening, and route-readback UI polish.

## Verification

Source verification for this policy change:

```bash
python3 - <<'PY'
from pathlib import Path
required = [
    'Sprint 5 Speculative Readiness Policy',
    'candidate readiness for the next measured physical gate',
    'hardware gate proof',
    'sttLatencyMs > 1500',
    'No-fake-transcript behavior is non-negotiable',
    'no DAT/camera permissions',
]
text = Path('docs/sprint5-speculative-readiness-policy.md').read_text()
missing = [item for item in required if item not in text]
if missing:
    raise SystemExit(f'missing policy terms: {missing}')
print('sprint5 policy terms present')
PY
```

Sprint 5 gate verification still requires a real phone + Ray-Ban Meta run. A valid physical report must include input/output route evidence, `pass1Ready`, `pass1Status`, `transcriptSource`, `sttProvider`, `sttStatus`, `sttLatencyMs`, `primarySttStatus`, `fallbackSttStatus`, `backendRoundTripMs`, `turnTotalMs`, playback route, semantic assistant response, and separate evidence-class labels.
