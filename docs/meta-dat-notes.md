# Meta DAT Notes for Otoxan Mobile

## Core distinction

For regular Ray-Ban Meta Wayfarers, use Meta Wearables Device Access Toolkit (DAT) native mobile SDK when glasses-specific camera/session support is needed.

Do not use Web Apps for Wayfarers. Web Apps are for Meta Ray-Ban Display glasses.

## Wayfarers capabilities through DAT

- camera video stream from wearer POV;
- photo capture during an active stream;
- device discovery;
- session lifecycle events;
- pause/resume/stop state;
- microphone through Bluetooth HFP;
- speaker playback through phone Bluetooth audio routing.

## Boundaries

- no lens HUD on regular Wayfarers;
- no standalone app running directly on Wayfarers;
- no continuous always-on camera assumption;
- one session per device at a time;
- sessions are user initiated;
- HFP mic is approximately 8 kHz mono and voice-focused;
- iOS public App Store path is currently constrained in DAT developer preview.

## Recommended build order

1. Bluetooth headset-style audio loop.
2. Native Android app route-check + push-to-talk.
3. Assistant backend round trip.
4. TTS response through glasses.
5. DAT registration/session lifecycle.
6. DAT still photo capture.
7. DAT live camera sampling.
