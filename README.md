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

Build the Android app with the phone-reachable host IP. Either pass the Gradle property explicitly or export the environment variable; the Gradle property wins when both are present:

```bash
./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://<LAN-IP>:8787/voice-turn"
# or:
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

Current scope: this starts the live Xander turn through the configured Hermes model lane and attempts transcription through the configured Hermes STT lane. If STT is unavailable or returns nothing, the helper falls back to route/byte evidence or `OTOXAN_DEBUG_TRANSCRIPT` so the physical loop still stays testable.

Pass 1 closeout evidence and the current keep-it-simple backend decision are captured in [`docs/pass1-closeout.md`](docs/pass1-closeout.md). The repo-local `/voice-turn` adapter remains the active edge while voice quality, Xander personality, and latency are hardened; avoid a large backend reorganization for now.
