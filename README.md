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

The helper validates the PCM payload and route evidence, returns visible transcript/assistant text, returns `provider`, `bytesReceived`, `audioFormat`, and returns PCM audio bytes. The app shows captured/backend/TTS counters so a physical test can identify which segment failed. It does not store raw audio.

Provider modes:

```bash
# Deterministic proof mode: no external API, returns a short proof response/tone.
make backend-proof

# Auto mode: uses OpenAI-compatible STT/chat/TTS when OPENAI_API_KEY is set,
# otherwise falls back to proof mode so the physical route test stays alive.
make backend

# Strict real-provider mode: fail loudly if OPENAI_API_KEY/provider calls fail.
OPENAI_API_KEY=... make backend-openai
```

OpenAI-compatible environment knobs:

```bash
OPENAI_API_KEY=...
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_STT_MODEL=gpt-4o-mini-transcribe
OPENAI_CHAT_MODEL=gpt-4o-mini
OPENAI_TTS_MODEL=gpt-4o-mini-tts
OPENAI_TTS_VOICE=alloy
```

In real-provider mode the backend runs:

```text
Android PCM -> STT -> short Xander chat reply -> TTS audio -> Android/Ray-Ban playback
```
