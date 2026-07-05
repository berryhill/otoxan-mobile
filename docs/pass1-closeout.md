# Otoxan Mobile Pass 1 Closeout

Status: core voice loop proven.

## Proven loop

```text
Ray-Ban Meta mic
-> Android Otoxan Mobile capture
-> repo-local /voice-turn adapter
-> Hermes STT lane
-> Hermes profile xander model lane
-> Android/Ray-Ban playback
```

## Evidence

Real physical turns showed:

- input route: `RB Meta 03YS`
- route type: `TYPE_BLUETOOTH_SCO`
- capture size: `160000` bytes
- capture duration: `5000` ms
- transcript source: `hermes-stt`
- STT status: `success`
- backend provider: `xander-session`

Observed speech captures included peaks around `4815`, `5990`, and `6397`, which proves non-silent Ray-Ban microphone audio reached the backend. Matt confirmed the app answered spoken questions through the voice loop.

## Pass 1 success contract

Pass 1 is proven only when a real phone/Ray-Ban turn reports:

- `pass1Ready=true`
- `pass1Status=real-speech-proven`
- `transcriptSource=hermes-stt`
- `sttStatus=success`
- wearable route evidence points at the Ray-Ban device

`proof` mode, debug transcripts, and route-evidence fallback are useful diagnostics, but they are not real-speech proof.

## Current scope decision

Keep the backend simple for now. The repo-local `tools/voice_turn_server.py` adapter remains the active mobile edge while voice quality is hardened. Do not spend this phase on a large backend/service reorganization.

Near-term priority order:

1. Keep the voice loop reliable.
2. Make the response sound like Xander.
3. Reduce perceived latency and avoid client disconnects.
4. Preserve the simple `/voice-turn` helper until the mobile loop earns a heavier service boundary.
5. Start DAT/camera only after the audio loop feels good.

## Known residual risks

- Android/backend turn latency can exceed the old 30-second read timeout; the client now uses a 60-second read timeout.
- STT latency still varies and needs measurement/tuning.
- Xander response style needs mobile-specific operator phrasing, not generic assistant tone.
- Android currently speaks `assistantText` through local TextToSpeech in `xander-session` mode; backend/Hermes TTS PCM can be added later.
- Very quiet captures can still produce weak or ambiguous turns.

## Verification commands

From the repo root:

```bash
python3 app/src/test/python/test_voice_turn_server.py
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"
python3 tools/smoke_voice_turn.py --expect-provider xander-session --timeout 60
```

For physical proof, use a semantic phrase such as:

```text
Xander, say pineapple if you heard me.
```

Success means the phone proof card reports `REAL SPEECH PROVEN` and Xander answers the actual phrase, not route fallback text.
