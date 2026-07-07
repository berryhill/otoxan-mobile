# Otoxan Mobile stream event protocol and HTTP fallback semantics

## Control obligation

Otoxan Mobile remains an explicit-session, audio-first Ray-Ban Meta phone bridge. The stream protocol is a transport upgrade for the same push-to-talk turn contract; it is not an always-on recorder, not an AR/display protocol, and not a replacement for the canonical `/voice-turn` fallback until the stream path is hardware-proven.

## Protocol identity

- Protocol name: `otoxan-mobile-realtime-stream`
- Protocol version: `1`
- Primary realtime endpoint: `GET /realtime` WebSocket upgrade
- Experimental backend stream endpoint: `POST /voice-stream` NDJSON, enabled only when `OTOXAN_EXPERIMENTAL_STREAM_TRANSPORT` is truthy (`1`, `true`, `yes`, `on`)
- HTTP fallback endpoint: `POST /voice-turn`
- Audio format: `pcm_s16le_16khz_mono`
- Session model: one explicit user session per WebSocket connection
- Ordering: server events carry monotonically increasing `sequence` values scoped to `sessionId`

Every server JSON event has this envelope:

```json
{
  "type": "session.created",
  "sessionId": "rt_...",
  "sequence": 1,
  "state": "created"
}
```

`session.created` is the protocol discovery event. It MUST include:

```json
{
  "protocol": {
    "name": "otoxan-mobile-realtime-stream",
    "version": 1
  },
  "audioFormat": "pcm_s16le_16khz_mono",
  "transport": "websocket",
  "httpFallback": {
    "endpoint": "/voice-turn",
    "method": "POST",
    "requestAudioField": "pcm16Mono16kBase64",
    "responseShape": "existing voice-turn response",
    "useWhen": [
      "websocket_unavailable",
      "handshake_failed",
      "stream_error_before_commit",
      "client_policy_prefers_http_for_turn"
    ]
  }
}
```

## Client-to-server events

### `session.update`

Configures route evidence for the explicit session. Route evidence is required before a real hardware proof can be claimed, but the dev server accepts missing route evidence as `unknown` for local diagnostics.

```json
{
  "type": "session.update",
  "audioFormat": "pcm_s16le_16khz_mono",
  "routeEvidence": {
    "inputName": "Ray-Ban Meta",
    "inputType": "TYPE_BLUETOOTH_SCO",
    "outputName": "Ray-Ban Meta",
    "outputType": "TYPE_BLUETOOTH_SCO",
    "wearableActive": true,
    "message": "setCommunicationDevice=true"
  }
}
```

Server response: `session.updated`, state `configured`.

### `input_audio.append`

Appends a base64 PCM chunk over a JSON frame. Binary WebSocket frames are equivalent and are preferred for real streaming.

```json
{
  "type": "input_audio.append",
  "pcm16Mono16kBase64": "..."
}
```

Server response: one or more events. VAD boundary events may precede `input_audio.appended`:

- `user.speech.started`
- `user.speech.ended`
- `input_audio.appended`

VAD events are diagnostics only. They do not call Xander, do not commit a turn, and do not prove real speech by themselves.

### Binary PCM frame

A binary WebSocket frame is raw PCM in the protocol audio format. The server treats it as `input_audio.append` and returns the same event sequence.

### `input_audio.commit`

Commits the buffered explicit-session audio as one assistant turn. This is the only realtime event that invokes the existing voice-turn handler.

```json
{"type": "input_audio.commit"}
```

Server response: `response.completed`, state `responding`, with:

```json
{
  "turnIndex": 1,
  "audioFormat": "pcm_s16le_16khz_mono",
  "bytesCommitted": 320,
  "fallback": {
    "source": "websocket_commit",
    "canonicalHttpEndpoint": "/voice-turn",
    "semantics": "same_request_response_contract"
  },
  "voiceTurn": {
    "ok": true,
    "transcript": "...",
    "assistantText": "..."
  }
}
```

### `input_audio.clear`

Clears buffered audio without invoking the assistant. Server response: `input_audio.cleared`, state `configured`.

### `control.ping`

Liveness check. Server response: `control.pong`.

### `session.close`

Closes the explicit realtime session. Server response: `session.closed`, state `closed`.

## Server-to-client event registry

| Event | State | Meaning |
| --- | --- | --- |
| `session.created` | `created` | WebSocket accepted and protocol/fallback discovery is available. |
| `session.updated` | `configured` | Route/session settings accepted. |
| `user.speech.started` | `buffering` | Energy VAD crossed speech threshold. Diagnostic only. |
| `user.speech.ended` | `buffering` | Energy VAD saw configured quiet chunks. Diagnostic only. |
| `input_audio.appended` | `buffering` | PCM bytes accepted into the explicit-session buffer. |
| `input_audio.cleared` | `configured` | Buffered PCM discarded. |
| `response.completed` | `responding` | Buffered PCM was committed through the voice-turn contract. |
| `control.pong` | current open state | Liveness reply. |
| `session.closed` | `closed` | Session closed by client. |
| `error` | `error` | Protocol or server error; client should stop using that stream. |

## Backend NDJSON stream endpoint

`POST /voice-stream` is an experimental backend transport shim for the same explicit push-to-talk turn that `/voice-turn` serves. It is disabled by default and returns `404` unless `OTOXAN_EXPERIMENTAL_STREAM_TRANSPORT` is truthy. When enabled, the server replies as `application/x-ndjson` with one JSON event per line:

1. `stream.started` — includes protocol descriptor, fallback pointer, flag name, privacy defaults, STT event schema, STT budget model, and the optional Moonshine streaming adapter seam.
2. `stt.partial` — optional diagnostic transcript-state readback: provider/status/source plus transcript length only; no standalone raw transcript text persistence.
3. `stt.final` — final transcript-state readback before the wrapped voice turn: provider/status/source plus final transcript length only.
4. `stt.completed` — emits STT provider/status/latency/budget readback without raw audio or transcript text persistence.
5. `response.completed` — wraps the existing `/voice-turn` response as `voiceTurn`.
6. `stream.completed` — closes the stream and repeats the canonical `/voice-turn` fallback semantics and STT budget model.

This endpoint does not add always-on capture, raw-audio persistence, or a new assistant authority surface. It is a backend transport experiment so the Android client can test stream-shaped parsing while retaining the proven `/voice-turn` contract and fallback.

### Optional Moonshine streaming adapter seam

`stream.started.transport.moonshineStreamingAdapter` advertises a disabled-by-default seam for a local Moonshine streaming command adapter. It is a readback contract, not a Python package dependency. The server does not import `moonshine`, `moonshine_onnx`, or `moonshine_voice` at startup.

Default clean-clone state:

```json
{
  "name": "moonshine-streaming-adapter",
  "version": 1,
  "provider": "moonshine-stt",
  "enabled": false,
  "status": "disabled",
  "mode": "disabled",
  "hardDependency": false,
  "importPolicy": "no Moonshine package import at server startup",
  "commandEnv": "OTOXAN_MOONSHINE_STREAMING_COMMAND",
  "adapterFlag": "OTOXAN_MOONSHINE_STREAMING_ADAPTER",
  "inputAudioFormat": "pcm_s16le_16khz_mono",
  "events": ["stt.partial", "stt.final", "stt.completed"],
  "evidenceClass": "adapter_seam_readback_not_hardware_proof"
}
```

To opt in for an evidence run, set `OTOXAN_MOONSHINE_STREAMING_ADAPTER=command` and provide `OTOXAN_MOONSHINE_STREAMING_COMMAND`. If the flag is set without the command, the descriptor reports `status: command-not-configured` and remains disabled. The canonical fallback remains `/voice-turn` with the same request/response semantics.

### STT stream event schema

STT stream telemetry is a readback contract for the Sprint 4 latency budget. It is not a hardware pass/fail gate and must not persist raw audio or full transcript text as standalone stream metadata.

Schema identity:

```json
{
  "name": "otoxan-mobile-stt-stream-events",
  "version": 1,
  "audioFormat": "pcm_s16le_16khz_mono"
}
```

Budget model published on `stream.started`, `stt.completed`, and `stream.completed`:

```json
{
  "name": "sprint4-stt-budget",
  "version": 1,
  "targetField": "sttLatencyMs",
  "targetMs": 1500,
  "totalBudgetMs": 1500,
  "primaryLocalBudgetMs": 750,
  "fallbackReserveMs": 250,
  "fallbackBudgetMs": 750,
  "primaryProvider": "moonshine-stt",
  "fallbackProvider": "hermes-stt",
  "evidenceClass": "latency_budget_readback_not_hardware_proof",
  "hardwareGate": "requires_fresh_phone_rayban_turn"
}
```

`stt.partial` / `stt.final` transcript-state event shape (diagnostic state only; raw transcript text omitted):

```json
{
  "type": "stt.final",
  "streamId": "vs_...",
  "sequence": 3,
  "schema": {
    "name": "otoxan-mobile-stt-stream-events",
    "version": 1
  },
  "stt": {
    "provider": "moonshine-stt|hermes-stt|not-run",
    "status": "success|empty|timeout|not-run|...",
    "transcriptLength": 42,
    "isFinal": true,
    "transcriptSource": "moonshine-stt|hermes-stt|route-evidence-fallback|proof|debug",
    "textOmitted": true,
    "evidenceClass": "transcript_state_readback_not_hardware_proof"
  },
  "privacy": {
    "rawTranscriptPersistedByEvent": false,
    "rawAudioPersisted": false
  }
}
```

`stt.completed` event shape:

```json
{
  "type": "stt.completed",
  "streamId": "vs_...",
  "sequence": 2,
  "schema": {
    "name": "otoxan-mobile-stt-stream-events",
    "version": 1
  },
  "stt": {
    "provider": "moonshine-stt|hermes-stt|not-run",
    "status": "success|empty|timeout|not-run|...",
    "latencyMs": 123,
    "primaryStatus": "success|empty|timeout|not-run|...",
    "primaryLatencyMs": 80,
    "primaryProvider": "moonshine-stt",
    "fallbackStatus": "success|empty|timeout|not-run|...",
    "fallbackLatencyMs": 43,
    "fallbackProvider": "hermes-stt",
    "budgetRemainingMs": 1377,
    "transcriptSource": "moonshine-stt|hermes-stt|route-evidence-fallback|proof|debug",
    "evidenceClass": "latency_budget_readback_not_hardware_proof"
  }
}
```

Budget environment overrides are allowed for evidence runs only: `OTOXAN_STT_TOTAL_BUDGET_SECONDS`, `OTOXAN_MOONSHINE_STT_TIMEOUT_SECONDS`, and `OTOXAN_STT_FALLBACK_MIN_SECONDS`. Overrides must be reported with the run evidence and do not replace the locked default target.

## HTTP fallback semantics

The fallback is not a weaker assistant mode. It is the canonical single-turn contract:

```http
POST /voice-turn
Content-Type: application/json
Accept: application/json
```

```json
{
  "format": "pcm_s16le_16khz_mono",
  "pcm16Mono16kBase64": "...",
  "routeEvidence": {"...": "..."}
}
```

Use HTTP fallback when:

1. the WebSocket endpoint is unreachable or the upgrade fails;
2. the stream errors before `input_audio.commit` returns `response.completed`;
3. the client is in a conservative/debug build that intentionally uses one-shot HTTP;
4. an operator needs a comparable baseline for hardware validation.

Do not double-submit after a successful `response.completed`. If the stream fails after commit but before the client receives the response, the current protocol has no idempotency key; retrying may create a second assistant turn. The safe MVP behavior is to surface an explicit error and let the user press-to-talk again.

HTTP fallback responses must preserve the existing voice-turn evidence fields (`provider`, `transcriptSource`, `sttStatus`, `pass1Status`, timing fields, route evidence). A successful HTTP response does not prove Ray-Ban hardware use unless the hardware-gate fields also pass.

## Privacy and safety boundaries

- No always-on recording.
- No hidden background capture.
- No camera/DAT permissions in this protocol.
- Buffered audio is explicit-session memory only; it is cleared after commit or clear.
- VAD boundaries are transport UX hints, not authority to auto-capture or auto-send.
