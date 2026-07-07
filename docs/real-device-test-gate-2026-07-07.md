# Real-device test gate packet — 2026-07-07

Generated: 2026-07-07T16:54:04Z

## Obligation / domain theme

Prepare Otoxan Mobile for the next real phone + Ray-Ban Meta hardware test without over-claiming source/build proof as hardware proof.

## Code or system surface

- Android app: `app/src/main/java/com/otoxan/mobile/`
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Repo-local voice-turn backend: `tools/voice_turn_server.py`
- Smoke client: `tools/smoke_voice_turn.py`
- Make workflow: `Makefile`
- Hardware closure checklist: `docs/hardware-validation-gate.md`

## Current evidence

Source/build verification completed on the task worktree at `2026-07-07T16:54:04Z`:

```bash
JAVA_HOME=/home/silas/.hermes/toolchains/jdk-17 \
ANDROID_HOME=/home/silas/.hermes/toolchains/android-sdk \
make test-all
```

Result: pass.

Covered checks:

- `./gradlew :app:testDebugUnitTest`
- `python -m unittest app/src/test/python/test_voice_turn_server.py -v` — 66 tests passed.
- `python -m unittest app/src/test/python/test_realtime_voice_server.py -v` — 5 tests passed.
- `python -m unittest app/src/test/python/test_moonshine_stt_command.py -v` — 4 tests passed.
- `./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://100.126.0.110:8787/voice-turn"`

Built APK readback:

```text
path=app/build/outputs/apk/debug/app-debug.apk
size=24625766
sha256=605ab0475a2e151ac36c407934af144f5fd34188ecf55fbf88fc2738c969fa79
endpoint=http://100.126.0.110:8787/voice-turn
```

Repo-local backend smoke completed against proof mode on an alternate local port because `0.0.0.0:8787` was already occupied by an existing Python listener:

```bash
make backend-proof VOICE_HOST=127.0.0.1 VOICE_PORT=8877
make smoke-backend VOICE_ENDPOINT=http://127.0.0.1:8877/voice-turn OTOXAN_EXPECT_PROVIDER=proof
```

Smoke result:

```text
ok=true
provider=proof
bytesReceived=320
audioFormat=pcm_s16le_16khz_mono
peak=513
rms=513.0
pass1Ready=false
pass1Status=proof-mode-not-real-speech
assistantText="Xander heard you. The Ray-Ban voice route is live on Ray-Ban Meta."
tts_bytes=25600
```

Device readiness readback:

```bash
make devices ADB=/home/silas/.hermes/toolchains/android-sdk/platform-tools/adb
```

Result: adb is available, but no Android device was attached in this worker session.

## Gap

No physical phone + Ray-Ban Meta device was available to this worker. The task therefore prepared the real-device gate and produced a signed build artifact, but did not close the hardware gate.

## Risk

- A clean APK build can be mistaken for real route proof.
- Proof-mode backend smoke can validate payload plumbing while still explicitly failing the hardware gate.
- The default test endpoint `http://100.126.0.110:8787/voice-turn` must be reachable from the phone network during the physical run.
- Port `8787` was already occupied in this environment; the operator should confirm which backend process owns the physical-test endpoint before starting the phone run.

## Remediation

Run the physical test gate on the Android/Ray-Ban workstation:

1. Confirm the phone can reach `http://100.126.0.110:8787/voice-turn`, or rebuild with the correct phone-reachable LAN/Tailscale endpoint:

   ```bash
   JAVA_HOME=/home/silas/.hermes/toolchains/jdk-17 \
   ANDROID_HOME=/home/silas/.hermes/toolchains/android-sdk \
   make build VOICE_ENDPOINT=http://<PHONE-REACHABLE-HOST>:8787/voice-turn
   ```

2. Start the real backend mode intended for the test:

   ```bash
   make backend
   # or, for the explicit Moonshine-first STT run:
   make backend-moonshine
   ```

3. Install and launch the APK:

   ```bash
   make reinstall ADB=<path-to-adb>
   make launch ADB=<path-to-adb>
   ```

4. Pair Ray-Ban Meta Wayfarers to the phone.
5. In Otoxan Mobile, tap `Check audio route`.
6. Verify route evidence names the Ray-Ban device and reports `TYPE_BLE_HEADSET` or `TYPE_BLUETOOTH_SCO`.
7. Run one explicit push-to-talk semantic phrase, for example: `Xander, say pineapple if you heard me.`
8. Record only text evidence from the proof card; do not store raw audio.

## Verification

The hardware gate can be marked ready for operator test, not closed, only if the physical run records:

- `pass1Ready=true`
- `pass1Status=real-speech-proven`
- `transcriptSource=hermes-stt` or `transcriptSource=moonshine-stt`
- `sttStatus=success`
- `provider=xander-session` or `provider=mobile-fast`
- Ray-Ban route name on input or output
- `TYPE_BLE_HEADSET` or `TYPE_BLUETOOTH_SCO` on input or output
- non-zero captured/backend bytes, peak, and RMS for the expected turn duration
- semantic assistant response to the phrase, not proof/debug fallback text
- canonical timing fields: `ttfaMs`, `postCaptureAckDelayMs`, `backendRoundTripMs`, `turnTotalMs`, with pass/miss labels against the v1 baselines

Source/build evidence from this packet remains candidate readiness evidence only. It is not hardware-gate closure.
