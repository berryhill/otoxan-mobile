# Otoxan Mobile Context

Otoxan Mobile is the phone-side bridge between Ray-Ban Meta Wayfarers and the Otoxan/Hermes assistant runtime.

## Current product thesis

Normal Ray-Ban Meta Wayfarers have no lens display. Treat them as:

- wearer POV camera/photo source via Meta DAT later;
- Bluetooth HFP voice input;
- Bluetooth speaker output;
- explicit-session wearable peripheral controlled by a phone app.

Do not design the MVP as AR overlay UI or a standalone glasses app.

## MVP scope

Audio-first assistant loop:

- route-check input/output;
- push-to-talk;
- short assistant responses;
- no always-on recording;
- no background camera;
- no persistent storage unless explicitly captured.

## Preferred initial platform

Android/Kotlin first. Keep iOS as a later parallel target after the interaction loop is proven.

## Android implementation doctrine

Use the minimal Android patterns in `docs/android-minimal-patterns.md`.

- Native Android/Kotlin first.
- Jetpack Compose and one `MainActivity` initially.
- Single `:app` module until complexity proves otherwise.
- First screen is route-check + push-to-talk + transcript/response.
- Use Android Bluetooth communication routing for the first audio loop.
- No DAT/camera permissions until the audio loop is proven.
- No wake word or always-on/background recording in MVP 0/1.
- Keep the client thin; backend handles assistant intelligence.

## Safety and privacy defaults

- explicit user session only;
- explicit capture only;
- short retention by default;
- visible transcript on phone;
- no hidden always-on capture.
