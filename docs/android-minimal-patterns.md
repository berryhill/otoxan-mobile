# Minimal Android Patterns for Otoxan Mobile

## Purpose

This document locks the first Android implementation to the smallest useful native app. The first proof is the Ray-Ban Meta Bluetooth audio loop: Matt talks through the glasses mic on a Pixel 10 and hears Xander through the glasses speakers.

Do not let the first build turn into a general mobile platform, AR app, camera product, or always-on assistant.

## Product order

1. Android/Kotlin first.
2. Audio-first.
3. Route-check UI before assistant complexity.
4. Push-to-talk before wake words.
5. Backend round trip before DAT camera.
6. Response playback through glasses before background/session polish.
7. DAT registration/session lifecycle before DAT camera/photo capture.
8. Camera/“look at this” only after the voice loop works.

## Baseline Android stack

Use the boring modern Android default:

- Kotlin.
- Jetpack Compose.
- Material 3.
- Single `:app` module initially.
- Single `MainActivity` initially.
- Kotlin DSL Gradle files (`build.gradle.kts`).
- Version catalog (`libs.versions.toml`).
- Compose BOM for Compose dependencies.
- Android Studio defaults unless there is a concrete reason to deviate.

Avoid for the first build:

- XML layouts.
- Fragments.
- Multi-module architecture.
- Kotlin Multiplatform.
- Heavy dependency injection frameworks.
- Domain/use-case layer unless logic is reused or complex enough to justify it.
- Complex navigation before there are multiple real screens.

## App architecture

Use two layers only:

1. UI layer:
   - `MainActivity`
   - Compose screen(s)
   - one screen-level `ViewModel`
   - immutable `UiState`
   - unidirectional data flow

2. Data/service layer:
   - `XanderVoiceClient`
   - `AudioRouter`
   - `MicCapture`
   - `SpeechPlayback`
   - optional `SettingsRepository`
   - optional foreground service only when background operation is required

Suggested initial package shape:

```text
app/src/main/java/com/otoxan/mobile/
  MainActivity.kt
  ui/
    OtoxanApp.kt
    OtoxanScreen.kt
    OtoxanViewModel.kt
    OtoxanUiState.kt
  voice/
    VoiceSessionController.kt
    AudioRouter.kt
    MicCapture.kt
    SpeechPlayback.kt
    XanderVoiceClient.kt
```

Add these only when needed:

```text
  service/
    VoiceSessionService.kt
    VoiceNotification.kt
  tile/
    OtoxanTileService.kt
  dat/
    DatSessionController.kt
```

## UI pattern

The first screen is one route-check + talk screen.

Show:

- microphone route status;
- output route status;
- Bluetooth device name when available;
- backend connection status;
- push-to-talk or tap-to-talk control;
- transcript pane;
- Xander response pane;
- replay last response button;
- clear error states.

Do not show:

- AR overlay UI;
- glasses HUD controls;
- camera preview;
- hidden/background recording state;
- complex settings panels before the audio loop works.

## State pattern

Use one `OtoxanViewModel` at first.

Expose immutable UI state:

```kotlin
data class OtoxanUiState(
    val micRoute: RouteState = RouteState.Unknown,
    val outputRoute: RouteState = RouteState.Unknown,
    val backend: BackendState = BackendState.Unknown,
    val session: VoiceSessionState = VoiceSessionState.Idle,
    val transcript: String = "",
    val response: String = "",
    val error: String? = null,
)
```

The UI sends events to the ViewModel:

```kotlin
fun onRouteCheckClicked()
fun onTalkPressed()
fun onTalkReleased()
fun onStopSessionClicked()
fun onReplayClicked()
fun onPermissionResult(result: PermissionResult)
```

The ViewModel talks to repositories/controllers. Composables stay mostly stateless.

## Audio routing pattern

Treat Ray-Ban Meta Wayfarers as a Bluetooth headset-style input/output path for the MVP.

Use Android communication routing:

- set `AudioManager.mode = MODE_IN_COMMUNICATION` during an active voice turn/session;
- inspect `availableCommunicationDevices`;
- prefer `TYPE_BLE_HEADSET` if present;
- otherwise use `TYPE_BLUETOOTH_SCO`;
- call `AudioManager.setCommunicationDevice(device)`;
- verify route state before recording;
- call `clearCommunicationDevice()` and restore `MODE_NORMAL` when done.

Do not use `startBluetoothSco()` unless supporting older API levels requires a fallback. Pixel 10-class work should prefer `setCommunicationDevice()`.

If Bluetooth route is unavailable, show a clear state. Do not silently record from the phone mic while claiming glasses audio is active.

## Microphone capture pattern

Use `AudioRecord` for raw PCM capture.

Defaults for MVP:

- source: `MediaRecorder.AudioSource.VOICE_COMMUNICATION`;
- format: PCM 16-bit;
- channels: mono;
- sample rate: start with 16 kHz if supported;
- interaction: push-to-talk or tap-to-start/tap-to-stop;
- no always-on capture.

The backend contract should tolerate headset-grade audio and route diagnostics.

## Playback pattern

Use `AudioTrack` for streaming PCM responses, or a simple player for backend-provided audio URLs.

Set speech/communication audio attributes where applicable:

- `AudioAttributes.USAGE_VOICE_COMMUNICATION`;
- `AudioAttributes.CONTENT_TYPE_SPEECH`.

Responses should be short by default. This is glasses audio, not podcast playback.

## Permission pattern

Request permissions in context, when the user starts the feature.

Initial likely permissions:

- `RECORD_AUDIO`;
- `BLUETOOTH_CONNECT` on Android 12+;
- `INTERNET`;
- `POST_NOTIFICATIONS` only when notification/session controls are added;
- foreground-service permissions only when a foreground service is added.

Do not request for MVP:

- camera permission;
- background recording permission/workarounds;
- location;
- contacts;
- storage;
- phone/SMS.

DAT/camera permissions wait until the DAT camera milestone.

## Foreground service pattern

Do not start with a foreground service.

MVP 0 and MVP 1 can run while the app is visible.

Add a foreground service only when there is a real requirement for:

- active listening while the app is backgrounded;
- screen-off session continuation;
- persistent notification controls;
- Quick Settings tile controlling an already-running session.

If added:

- use `foregroundServiceType="microphone"` for background mic capture;
- start from explicit user action;
- show a persistent notification;
- provide an obvious stop action;
- stop capture and clear audio route when the session ends.

Do not build hidden always-listening behavior.

## Quick Settings and notification pattern

These are post-route-proof conveniences.

Notification actions:

- `Open`;
- `Talk` or `Start`;
- `Stop`;
- `End session`.

Quick Settings tile:

- `Xander Off`;
- `Xander Ready`;
- `Listening`.

The tile should not be the first implementation path. Build app-button voice first, then add tile/notification controls.

## Backend interaction pattern

Keep the Android client thin.

Current proof-helper endpoint shape follows `docs/mvp.md`:

```http
POST /voice-turn
Content-Type: application/json
```

Request should include:

- format: `pcm_s16le_16khz_mono`;
- `pcm16Mono16kBase64` audio payload;
- route diagnostics: input/output name, type, wearable active flag, and route message.

Response should include:

- `ok`;
- `provider` (`xander-session` for the live Hermes/Xander adapter, or `proof` for deterministic route testing);
- transcript;
- short assistant text response;
- `ttsPcm16Mono16kBase64` PCM response audio;
- `audioFormat`;
- `bytesReceived`.

No Gemini/Google Assistant dependency. The backend may use Otoxan/Hermes/Xander directly.

## Storage and retention pattern

Default: no durable audio storage.

Allowed in MVP:

- transient transcript visible on phone;
- last response text/audio for replay during the session;
- local route diagnostics during testing.

Requires explicit product decision:

- saved transcripts;
- saved raw audio;
- session summaries;
- captured photos/video;
- long-term memory.

## DAT boundary

DAT is real, but not first for audio MVP.

MVP audio uses Android Bluetooth routing.

Add DAT after the voice route works:

1. Meta AI Developer Mode registration.
2. DAT device discovery.
3. DAT session lifecycle display.
4. Pause/resume/stop handling.
5. Only then camera/photo stream.

Do not add camera preview, camera permission, or DAT stream setup to the first route-check build.

## Non-goals for first implementation

- No “Hey Meta” interception.
- No Gemini/Google Assistant activation.
- No wake word in MVP 0/1.
- No always-on recording.
- No hidden background capture.
- No Ray-Ban camera stream.
- No glasses HUD.
- No standalone glasses app.
- No public App Store / Play Store release work.
- No multi-module architecture.
- No generalized mobile platform.

## Acceptance checks: MVP 0 route proof

MVP 0 is complete only when:

- app builds locally;
- app opens to the route-check screen;
- app requests only necessary permissions;
- app displays current mic/output route;
- app can identify whether Ray-Ban Meta route is available;
- app can record a short test clip from the selected route;
- app can play a test phrase/beep through the selected output route;
- missing glasses/permission/backend states are visible;
- no camera/DAT permission is requested.

## Acceptance checks: MVP 1 Talk to Xander

MVP 1 is complete only when:

- user can press/tap to talk;
- app records wearer speech through the glasses route;
- app sends a turn to Xander backend;
- app displays transcript or recognized text;
- app receives Xander response;
- app plays response through glasses speakers;
- user can stop/end the session;
- no audio is durably stored by default;
- app does not depend on Gemini/Google Assistant.

## Implementation doctrine

When in doubt, choose the boring path:

- visible app control before background automation;
- route proof before assistant polish;
- push-to-talk before wake word;
- Android Bluetooth audio before DAT camera;
- one screen before navigation;
- one module before modularization;
- explicit errors before magic fallback.
