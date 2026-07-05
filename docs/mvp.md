# Minimal Viable Otoxan Mobile

## Goal

Matt can talk to Silas/Otoxan from Ray-Ban Meta Wayfarers and hear the response through the glasses.

## Non-goals for MVP

- no Ray-Ban camera stream;
- no Meta DAT camera integration;
- no heads-up display;
- no wake word;
- no always-on capture;
- no App Store/public release path;
- no replacing Meta AI globally.

## MVP interaction

1. Matt pairs Ray-Ban Meta Wayfarers to Android phone.
2. Matt opens Otoxan Mobile.
3. App shows input/output route status.
4. Matt presses and holds Talk.
5. App captures audio from Bluetooth/HFP route.
6. App sends audio or transcript to Otoxan assistant endpoint.
7. App receives text + optional TTS audio.
8. App plays response through glasses.

## First UI

- Route status: mic route, output route, Bluetooth device name if available.
- Hold to talk button.
- Transcript pane.
- Assistant response pane.
- Replay last response button.

## Backend contract draft

```http
POST /api/mobile/voice-turn
Content-Type: application/json

{
  "session_id": "...",
  "device": "rayban_meta_wayfarers",
  "input_mode": "push_to_talk",
  "transcript": "Silas, what should I do next?"
}
```

Response:

```json
{
  "text": "Start audio-only. Prove the Ray-Ban mic and speaker route first.",
  "audio_url": "https://.../tts.mp3",
  "conversation_id": "..."
}
```

## DAT later

After audio loop works:

- add Meta DAT registration;
- add session lifecycle display;
- add camera permission flow;
- add still-photo capture for “look at this”;
- only then consider live frame sampling.
