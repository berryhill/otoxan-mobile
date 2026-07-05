# MVP 0 Route Proof Verification

This packet is the acceptance gate for the first Otoxan Mobile Android slice.

## Scope implemented

- Kotlin + Jetpack Compose + Material 3 single `:app` module.
- Single `MainActivity`.
- One route-check/talk screen.
- Explicit route truth UI: selected input/output name, selected device type, wearable active flag, and evidence text.
- Android communication-device routing with `AudioManager.setCommunicationDevice()`.
- Preference order: `TYPE_BLE_HEADSET`, then `TYPE_BLUETOOTH_SCO`.
- Local 5-second PCM capture using `VOICE_COMMUNICATION` source.
- Local proof-tone playback using `USAGE_VOICE_COMMUNICATION`.
- Stubbed `XanderVoiceClient` seam for MVP 1 backend integration.

## Explicit non-scope

- No DAT camera/photo capture.
- No `Hey Meta` interception.
- No Google Assistant or Gemini activation path.
- No always-on/background recording.
- No foreground service.
- No durable audio/transcript storage.

## Required device QA

1. Pair Ray-Ban Meta Wayfarers to the Pixel.
2. Open Otoxan Mobile.
3. Grant microphone and Bluetooth permissions.
4. Tap `Check audio route`.
5. Confirm the UI reports `TYPE_BLE_HEADSET` or `TYPE_BLUETOOTH_SCO`.
6. If the UI reports default/phone route, do not claim glasses capture.
7. Tap `Record 5 second route proof` and speak through the glasses.
8. Confirm the stub transcript reports captured bytes through the selected route.
9. Tap `Play route proof` and confirm the tone plays through the glasses speaker.
10. Capture screenshots/logcat for final MVP 0 evidence.

## Local verification limitation

This workstation does not currently provide a verified Android SDK/device environment. Source-level checks can run here; Gradle/device build verification must run on an Android-configured machine or CI.
