# Otoxan Mobile

Mobile app codebase for talking to Silas/Otoxan through Ray-Ban Meta Wayfarers.

## MVP target

The minimum viable product is audio-first:

1. Pair Ray-Ban Meta Wayfarers to the phone as Bluetooth audio.
2. Open the Otoxan Mobile app.
3. Press/hold to talk.
4. Capture speech from the Ray-Ban HFP microphone path.
5. Send transcript/audio to an Otoxan/Hermes assistant backend.
6. Play the short assistant response back through the Ray-Ban speakers.

Camera/DAT visual context is version 2. The first proof should validate that Matt can comfortably talk to Silas from the glasses and hear responses back.

## Platform direction

Start Android/Kotlin first because:

- Meta DAT has an Android CameraAccess sample.
- iOS DAT currently has App Store/ExternalAccessory/MFi constraints in developer preview.
- Android is the faster place to validate Bluetooth HFP routing and a DAT session loop.

## Repository status

This repo is active on GitHub at:

https://github.com/berryhill/otoxan-mobile

Xander's active local clone path:

`/home/silas/.hermes/profiles/xander/workspace/codebases/otoxan-mobile`

The current `main` branch contains the Android route-proof app, repo-local voice-turn helper, Makefile workflow, and proof/real-provider backend seam.

## Architecture sketch

```text
Ray-Ban Meta Wayfarers
  -> Bluetooth HFP mic/speaker
  -> Otoxan Mobile app
      -> push-to-talk / route check / transcript UI
      -> STT adapter
      -> Otoxan/Hermes assistant API
      -> TTS playback
  -> Ray-Ban speakers
```

Later DAT-native path:

```text
Ray-Ban Meta Wayfarers
  -> Meta DAT session
  -> camera stream / still capture / session state
  -> Otoxan Mobile app
  -> Otoxan/Hermes visual assistant backend
```

## First implementation milestones

- M0: phone app skeleton and route-check UI.
- M1: push-to-talk audio capture through selected Bluetooth/HFP input.
- M2: backend text chat round trip.
- M3: TTS response through Ray-Ban speaker route.
- M4: DAT session proof with camera disabled.
- M5: DAT camera/photo capture for “look at this” workflows.

## Local voice-turn helper

For the first physical voice-loop test, use the repo-local Python helper instead of any Hermes/dashboard runtime service:

```bash
python3 tools/voice_turn_server.py --host 0.0.0.0 --port 8787
```

Build the Android app with the default phone-reachable proof host, or override it for another LAN/Tailscale route. The checked-in debug default is `http://100.126.0.110:8787/voice-turn` so a normal debug build does not silently fall back to the stub client. Either pass the Gradle property explicitly or export the environment variable; the Gradle property wins when both are present. The app accepts either the full `/voice-turn` URL or the base server URL and normalizes the capture endpoint to `/voice-turn`.

The endpoint policy is configurable at build time without changing the default behavior:

- `XANDER_VOICE_CONNECT_TIMEOUT_MILLIS` defaults to `10000`.
- `XANDER_VOICE_READ_TIMEOUT_MILLIS` defaults to `60000`.
- `XANDER_VOICE_METRICS_TIMEOUT_MILLIS` defaults to `5000`.
- Conversation capture tuning remains locked to the hardware-proven defaults unless `OTOXAN_CONVERSATION_CAPTURE_TUNING_EVIDENCE_GATE=true` is set for an evidence run. The tunable fields are bounded at build time: `OTOXAN_CONVERSATION_CAPTURE_MAX_MILLIS` (`5000..20000`, default `12000`), `OTOXAN_CONVERSATION_CAPTURE_MIN_MILLIS` (`300..2000`, default `700`), `OTOXAN_CONVERSATION_CAPTURE_SILENCE_AFTER_SPEECH_MILLIS` (`250..2000`, default `450`), `OTOXAN_CONVERSATION_CAPTURE_SPEECH_PEAK_AMPLITUDE` (`256..5000`, default `900`), and `OTOXAN_CONVERSATION_CAPTURE_CHUNK_MILLIS` (`50..200`, default `100`). Treat tuned builds as measurement artifacts, not new product defaults, until real Ray-Ban/phone evidence is accepted.

```bash
# default physical proof endpoint and default endpoint policy:
./gradlew :app:assembleDebug
# override with a different phone-reachable host:
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://<LAN-IP>:8787/voice-turn"
# equivalent shortcut:
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://<LAN-IP>:8787"
# override endpoint policy fields when a deployment needs different HTTP timing:
./gradlew :app:assembleDebug \
  -PXANDER_VOICE_ENDPOINT="http://<LAN-IP>:8787/voice-turn" \
  -PXANDER_VOICE_CONNECT_TIMEOUT_MILLIS=10000 \
  -PXANDER_VOICE_READ_TIMEOUT_MILLIS=60000 \
  -PXANDER_VOICE_METRICS_TIMEOUT_MILLIS=5000
# evidence-gated conversation capture tuning for a measured hardware run:
./gradlew :app:assembleDebug \
  -POTOXAN_CONVERSATION_CAPTURE_TUNING_EVIDENCE_GATE=true \
  -POTOXAN_CONVERSATION_CAPTURE_MAX_MILLIS=15000 \
  -POTOXAN_CONVERSATION_CAPTURE_MIN_MILLIS=900 \
  -POTOXAN_CONVERSATION_CAPTURE_SILENCE_AFTER_SPEECH_MILLIS=650 \
  -POTOXAN_CONVERSATION_CAPTURE_SPEECH_PEAK_AMPLITUDE=1100
# or use environment variables:
XANDER_VOICE_ENDPOINT="http://<LAN-IP>:8787/voice-turn" ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.otoxan.mobile -c android.intent.category.LAUNCHER 1
```

On Matt's Linux laptop the Pixel USB/udev path may require sudo for adb. The Makefile auto-detects `/home/berry/sdk/platform-tools/adb` and uses `sudo /home/berry/sdk/platform-tools/adb`; if needed, pass it explicitly:

```bash
make reinstall ADB="sudo /home/berry/sdk/platform-tools/adb"
make launch ADB="sudo /home/berry/sdk/platform-tools/adb"
```

The helper validates the PCM payload and route evidence, returns visible transcript/assistant text, returns `provider`, `bytesReceived`, `audioFormat`, and may return PCM audio bytes. The app shows captured/backend/TTS counters so a physical test can identify which segment failed. It does not store raw audio.

## Phone telemetry evidence classes

Sprint 3 source/build/backend decision evidence is recorded in `docs/sprint3-gate-decision-packet.md`. Treat that packet as candidate readiness evidence for the next physical gate, not as a replacement for a fresh phone + Ray-Ban Meta turn.

The phone telemetry UI now separates reliability, latency, and build/source proof so operators do not over-claim a run:

| Evidence class | What it can prove | What it must not claim |
| --- | --- | --- |
| Hardware gate | Real phone + Ray-Ban route produced `pass1Ready=true`, `pass1Status=real-speech-proven`, successful STT, and a semantic assistant response. | It is not replaced by Gradle tests, APK build success, or timing bars. |
| Capture reliability | The app captured expected PCM bytes, non-zero peak amplitude, and a usable capture according to client guardrails. | Capture bytes/peak alone do not prove real speech or backend success. |
| Backend turn reliability | A non-stub provider returned a successful HTTP turn without a surfaced client error. | Backend success does not prove the Ray-Ban route was physically used unless hardware-gate fields also pass. |
| Latency scorecard | Canonical timing fields are labeled `pass`, `miss`, or `unknown` against TTFA, ack, backend, total, STT, Xander, and playback targets. | Latency is a tuning/readback baseline, not hardware-gate proof. |
| Source/build proof | Unit tests and APK assembly prove package health. | Build/source proof is not runtime phone/Ray-Ban evidence. |

Use the UI's `Reliability and latency evidence classes` block after every physical run. A valid report should name both the reliability class and the latency class, for example: `hardware gate=proven; capture reliability=proven; backend turn reliability=proven; latency scorecard=miss/backend; source-build=not runtime evidence`.

## Sprint 1 hardware sweep protocol

Use `docs/hardware-sweep-protocol.md` to execute the reliability-and-latency hardware sweep recommended by `docs/hardware-threshold-comparison.md`. The protocol requires at least 10 real phone + Ray-Ban turns across normal speech, quiet speech, noisy room, clipped/too-short speech, and silence. Record text summaries only, keep timing scorecards separate from hardware proof, and do not treat source/build success as a substitute for Ray-Ban route evidence.

## Phase 1 realtime WebSocket skeleton

The first open-source realtime phase is now a repo-local WebSocket transport skeleton. It does not replace `/voice-turn`; it gives the next Android/client slice a persistent transport to stream PCM chunks and commit them through the existing voice-turn contract.

```bash
make backend-realtime
# serves ws://0.0.0.0:8788/realtime
```

Event shape:

```text
server -> {"type":"session.created","audioFormat":"pcm_s16le_16khz_mono"}
client -> {"type":"session.update","routeEvidence":{...}}
client -> binary PCM16 16kHz mono frames
server -> {"type":"input_audio.appended","bufferedBytes":...}
client -> {"type":"input_audio.commit"}
server -> {"type":"response.completed","voiceTurn":{...existing /voice-turn response...}}
```

This is transport-only Phase 1. VAD, partial transcripts, barge-in, and WebRTC stay in later phases.

## Phase 2 event/state contract

The realtime skeleton now has a typed in-process event bus and session state machine. Every server JSON event carries:

```text
sequence: monotonically increasing session event number
state: created | configured | buffering | committing | responding | closed | error
sessionId: stable realtime session id
```

Current state transitions:

```text
connect -> session.created/state=created
session.update -> session.updated/state=configured
audio append -> input_audio.appended/state=buffering
commit -> response.completed/state=responding
clear -> input_audio.cleared/state=configured
close -> session.closed/state=closed
invalid frame/event -> error/state=error
```

This creates the control surface for Phase 3 VAD without changing the mobile `/voice-turn` HTTP fallback.

## Phase 3 VAD boundary events

The realtime server now runs a local energy-VAD boundary detector on every appended PCM chunk. This is a dependency-free Phase 3 seam for the later Silero ONNX provider; it gives the Android/client loop the same event contract before model integration.

New emitted events:

```text
user.speech.started  # first chunk whose peak crosses threshold
user.speech.ended    # after configured consecutive quiet chunks
```

Each `input_audio.appended` event carries a `vad` object:

```text
provider=energy-vad-phase3
peak=<pcm16 peak>
rms=<chunk rms>
speechDetected=true|false
speechActive=true|false
threshold=<peak threshold>
silentChunks=<current quiet run>
```

Important boundary: VAD events do not call Xander and do not commit audio. `/voice-turn` is still invoked only on `input_audio.commit`. The next Silero pass can replace the `EnergyVad` internals while preserving these wire event names.

## Phase 4 local TTS seam

The voice-turn helper now exposes a backend TTS provider seam while preserving Android TextToSpeech as the default fallback.

Default behavior:

```text
OTOXAN_TTS_PROVIDER=android  # default
assistantText -> Android/Ray-Ban TextToSpeech playback
ttsPcm16Mono16kBase64 -> empty for Xander/mobile-fast turns
```

Kokoro-compatible command mode:

```bash
OTOXAN_TTS_PROVIDER=kokoro-command \
OTOXAN_KOKORO_TTS_COMMAND='kokoro-tts --text {text} --output {output}' \
make backend
```

The command may either write 16 kHz mono PCM16 WAV/raw PCM to `{output}` or return raw PCM on stdout. The helper reports:

```text
ttsProvider=android|kokoro-command|...
ttsStatus=android-fallback|success|not-configured|error|unsupported
ttsLatencyMs=<milliseconds>
```

TTS failure is non-fatal: empty PCM preserves the Android playback fallback so voice turns do not fail because a local TTS model is missing.


## Phase 5 local STT seam

The voice-turn helper now has an optional Moonshine-compatible command STT adapter ahead of the existing Hermes/faster-whisper STT fallback. Default behavior is unchanged: `OTOXAN_STT_PROVIDER=hermes` uses the configured Hermes STT lane.

Moonshine-compatible command mode:

```bash
OTOXAN_STT_PROVIDER=moonshine-command \
OTOXAN_MOONSHINE_STT_COMMAND='python3 tools/moonshine_stt_command.py --backend auto --input {input} --output {output}' \
make backend
```

The command receives a 16 kHz mono PCM16 WAV path through `{input}`. It may print plain transcript text or JSON on stdout, or write JSON/text to `{output}` when the command template uses that placeholder:

```json
{"success": true, "transcript": "decoded words"}
```

If the Moonshine command is missing, times out, errors, or returns an empty transcript, the helper falls back to the existing Hermes STT path instead of failing the turn. Successful local command decoding reports:

```text
transcriptSource=moonshine-stt
sttProvider=moonshine-stt
sttStatus=success
sttLatencyMs=<milliseconds>
```

Fallback and no-speech behavior stay honest: if all STT lanes return empty, the backend says `Audio arrived, but words did not decode.` and does not call the Xander model lane with route evidence as fake speech.

Phase 6 repo-local wrapper:

```bash
# Backend with Moonshine wrapper first, Hermes STT fallback second.
make backend-moonshine

# Wrapper-only tests; no Moonshine package is required for these.
make test-moonshine-wrapper
```

`tools/moonshine_stt_command.py` is intentionally dependency-optional. It tries installed local packages in this order when `--backend auto` is used:

1. `useful-moonshine-onnx` / import `moonshine_onnx`
2. `useful-moonshine` / import `moonshine`
3. `moonshine-voice` / import `moonshine_voice`

Recommended optional local install outside the app dependency graph:

```bash
python3 -m venv .venv-moonshine
. .venv-moonshine/bin/activate
pip install useful-moonshine-onnx
OTOXAN_STT_PROVIDER=moonshine-command \
OTOXAN_MOONSHINE_STT_COMMAND='.venv-moonshine/bin/python tools/moonshine_stt_command.py --backend moonshine-onnx --input {input} --output {output}' \
make backend
```

Provider modes:

```bash
# Xander/Hermes session mode: default next-phase backend.
# Returns provider=xander-session and assistantText spoken by Android TTS.
make backend
# or explicitly:
make backend-xander

# Deterministic proof mode: no Hermes call, returns a short proof response/tone.
make backend-proof
```

Xander/Hermes environment knobs:

```bash
OTOXAN_VOICE_PROVIDER=xander-session
OTOXAN_DEBUG_TRANSCRIPT="optional debug transcript until STT is wired"
OTOXAN_HERMES_BIN=/home/silas/.local/bin/hermes
OTOXAN_HERMES_PYTHON=/home/silas/.hermes/hermes-agent/venv/bin/python
OTOXAN_XANDER_PROFILE=xander
OTOXAN_XANDER_TIMEOUT_SECONDS=25
```

Xander profile STT lane used by this helper:

```bash
hermes --profile xander config set stt.enabled true
hermes --profile xander config set stt.provider local
hermes --profile xander config set stt.local.model base
```

In Xander session mode the backend runs:

```text
Android PCM -> repo-local /voice-turn adapter -> Hermes STT lane -> Hermes profile xander model lane -> assistantText -> Android/Ray-Ban TextToSpeech playback
```

Current scope: this starts the live Xander turn through the configured Hermes model lane and attempts transcription through the configured Hermes STT lane, optionally trying the Moonshine-compatible local STT command first. If STT is unavailable or returns nothing, the helper falls back to route/byte evidence or `OTOXAN_DEBUG_TRANSCRIPT` so the physical loop still stays testable.

Hardware validation closure is captured in [`docs/hardware-validation-gate.md`](docs/hardware-validation-gate.md). Treat source/build proof and real Ray-Ban hardware proof as separate evidence classes: only a real phone/Ray-Ban turn with `pass1Ready=true` and `pass1Status=real-speech-proven` closes the hardware gate.

Threshold comparison and the next-sprint recommendation are captured in [`docs/hardware-threshold-comparison.md`](docs/hardware-threshold-comparison.md). Use that packet to keep capture/VAD thresholds, timing baselines, and hardware-gate evidence separate.

Pass 1 closeout evidence and the current keep-it-simple backend decision are captured in [`docs/pass1-closeout.md`](docs/pass1-closeout.md). The repo-local `/voice-turn` adapter remains the active edge while voice quality, Xander personality, and latency are hardened; avoid a large backend reorganization for now.
