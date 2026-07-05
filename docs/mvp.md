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

## Current repo-local backend contract

For the physical proof loop, the Android app talks to the repo-local helper rather than a dashboard/runtime endpoint:

```http
POST /voice-turn
Content-Type: application/json

{
  "format": "pcm_s16le_16khz_mono",
  "pcm16Mono16kBase64": "...",
  "routeEvidence": {
    "inputName": "Ray-Ban Meta",
    "inputType": "TYPE_BLE_HEADSET",
    "outputName": "Ray-Ban Meta",
    "outputType": "TYPE_BLE_HEADSET",
    "wearableActive": true,
    "message": "setCommunicationDevice=true"
  }
}
```

Response:

```json
{
  "ok": true,
  "provider": "proof",
  "transcript": "Received 320 bytes from Ray-Ban Meta (TYPE_BLE_HEADSET).",
  "assistantText": "Xander heard you. The Ray-Ban voice route is live on Ray-Ban Meta.",
  "ttsPcm16Mono16kBase64": "...",
  "audioFormat": "pcm_s16le_16khz_mono",
  "bytesReceived": 320
}
```

The prior `/api/mobile/voice-turn` text/audio-url shape was a draft for a later hosted runtime endpoint, not the current proof helper.

## DAT later

After audio loop works:

- add Meta DAT registration;
- add session lifecycle display;
- add camera permission flow;
- add still-photo capture for “look at this”;
- only then consider live frame sampling.
