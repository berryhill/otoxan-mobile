#!/usr/bin/env python3
"""Tiny local Otoxan Mobile voice-turn server.

Purpose: let the Android MVP call a repo-local backend without depending on the
Hermes/dashboard runtime. This is intentionally small and deterministic: it
validates the mobile PCM payload, echoes route evidence in transcript text, and
returns a short PCM proof tone so phone -> backend -> phone -> Ray-Ban playback
can be tested.

Run:
    python3 tools/voice_turn_server.py --host 0.0.0.0 --port 8787

Android build example:
    ./gradlew :app:assembleDebug -PXANDER_VOICE_ENDPOINT="http://<LAN-IP>:8787/voice-turn"
"""

from __future__ import annotations

import argparse
import base64
import json
import math
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Mapping

SUPPORTED_FORMAT = "pcm_s16le_16khz_mono"
MAX_PCM_BYTES = 512_000
SAMPLE_RATE = 16_000


class VoiceTurnError(Exception):
    def __init__(self, message: str, status: int = 400) -> None:
        super().__init__(message)
        self.status = status


def handle_voice_turn(payload: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(payload, Mapping):
        raise VoiceTurnError("JSON object payload required", 400)

    audio_format = str(payload.get("format", "")).strip()
    if audio_format != SUPPORTED_FORMAT:
        raise VoiceTurnError(f"format must be {SUPPORTED_FORMAT}", 400)

    pcm = _decode_pcm(payload.get("pcm16Mono16kBase64"))
    route = payload.get("routeEvidence") or {}
    if not isinstance(route, Mapping):
        raise VoiceTurnError("routeEvidence must be an object", 400)

    input_name = _clean(route.get("inputName"), "selected input")
    input_type = _clean(route.get("inputType"), "unknown input type")
    output_name = _clean(route.get("outputName"), "selected output")
    output_type = _clean(route.get("outputType"), "unknown output type")

    transcript = f"Received {len(pcm)} bytes from {input_name} ({input_type})."
    assistant_text = (
        "Otoxan local voice server is online. "
        f"I received the audio turn and returned this proof response for {output_name} ({output_type})."
    )

    return {
        "ok": True,
        "transcript": transcript,
        "assistantText": assistant_text,
        "ttsPcm16Mono16kBase64": base64.b64encode(_proof_tone_pcm()).decode("ascii"),
        "audioFormat": SUPPORTED_FORMAT,
        "bytesReceived": len(pcm),
        "routeEvidence": {
            "inputName": input_name,
            "inputType": input_type,
            "outputName": output_name,
            "outputType": output_type,
            "wearableActive": bool(route.get("wearableActive")),
            "message": _clean(route.get("message"), ""),
        },
    }


def _decode_pcm(value: Any) -> bytes:
    if not isinstance(value, str) or not value.strip():
        raise VoiceTurnError("pcm16Mono16kBase64 is required", 400)
    try:
        pcm = base64.b64decode(value, validate=True)
    except Exception as exc:  # noqa: BLE001 - report validation error, not internals.
        raise VoiceTurnError("pcm16Mono16kBase64 must be valid base64", 400) from exc
    if not pcm:
        raise VoiceTurnError("pcm16Mono16kBase64 decoded to empty audio", 400)
    if len(pcm) > MAX_PCM_BYTES:
        raise VoiceTurnError(f"decoded PCM exceeds {MAX_PCM_BYTES} bytes", 413)
    if len(pcm) % 2 != 0:
        raise VoiceTurnError("decoded PCM must be 16-bit aligned", 400)
    return pcm


def _proof_tone_pcm(duration_seconds: float = 0.8, frequency_hz: float = 660.0) -> bytes:
    frames = int(SAMPLE_RATE * duration_seconds)
    pcm = bytearray(frames * 2)
    for index in range(frames):
        sample = int(math.sin(2.0 * math.pi * frequency_hz * index / SAMPLE_RATE) * 32767 * 0.22)
        pcm[index * 2] = sample & 0xFF
        pcm[index * 2 + 1] = (sample >> 8) & 0xFF
    return bytes(pcm)


def _clean(value: Any, default: str) -> str:
    return str(value if value is not None else default).replace("\x00", "").strip()[:300]


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - stdlib handler API.
        if self.path == "/healthz":
            self._send_json(200, {"ok": True, "service": "otoxan-mobile-voice-turn"})
            return
        self._send_json(404, {"ok": False, "error": "not found"})

    def do_POST(self) -> None:  # noqa: N802 - stdlib handler API.
        if self.path != "/voice-turn":
            self._send_json(404, {"ok": False, "error": "not found"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length).decode("utf-8") if length else "{}"
            payload = json.loads(body)
            response = handle_voice_turn(payload)
            print(
                "voice-turn ok "
                f"bytes={response.get('bytesReceived')} "
                f"input={response.get('routeEvidence', {}).get('inputName')} "
                f"type={response.get('routeEvidence', {}).get('inputType')}",
                flush=True,
            )
            self._send_json(200, response)
        except json.JSONDecodeError:
            self._send_json(400, {"ok": False, "error": "Invalid JSON"})
        except VoiceTurnError as exc:
            self._send_json(exc.status, {"ok": False, "error": str(exc)})
        except Exception as exc:  # noqa: BLE001 - dinky dev server; return concise failure.
            self._send_json(500, {"ok": False, "error": str(exc)})

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"{self.address_string()} - {fmt % args}")

    def _send_json(self, status: int, payload: Mapping[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Otoxan Mobile voice-turn server -> http://{args.host}:{args.port}/voice-turn")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
