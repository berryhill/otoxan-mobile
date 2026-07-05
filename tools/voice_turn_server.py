#!/usr/bin/env python3
"""Repo-local Otoxan Mobile voice-turn server.

This is the fast-but-real backend seam for the Android/Ray-Ban voice loop:

1. validate Android PCM payload
2. if OPENAI_API_KEY is configured: transcribe -> ask model -> synthesize speech
3. otherwise: return the deterministic local proof response/tone

The server does not persist raw audio. It stays repo-local and intentionally small
so the phone app can prove the end-to-end route before this graduates into the
Otoxan/Hermes runtime.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import math
import os
import subprocess
import tempfile
import wave
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Mapping
from urllib import error as urlerror
from urllib import request as urlrequest

SUPPORTED_FORMAT = "pcm_s16le_16khz_mono"
MAX_PCM_BYTES = 512_000
SAMPLE_RATE = 16_000
OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/v1"


class VoiceTurnError(Exception):
    def __init__(self, message: str, status: int = 400) -> None:
        super().__init__(message)
        self.status = status


@dataclass(frozen=True)
class RouteSummary:
    input_name: str
    input_type: str
    output_name: str
    output_type: str
    wearable_active: bool
    message: str


@dataclass(frozen=True)
class AssistantTurn:
    transcript: str
    assistant_text: str
    tts_pcm: bytes
    provider: str


def handle_voice_turn(payload: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(payload, Mapping):
        raise VoiceTurnError("JSON object payload required", 400)

    audio_format = str(payload.get("format", "")).strip()
    if audio_format != SUPPORTED_FORMAT:
        raise VoiceTurnError(f"format must be {SUPPORTED_FORMAT}", 400)

    pcm = _decode_pcm(payload.get("pcm16Mono16kBase64"))
    route = _parse_route(payload.get("routeEvidence") or {})
    turn = _run_assistant_turn(pcm, route)

    return {
        "ok": True,
        "transcript": turn.transcript,
        "assistantText": turn.assistant_text,
        "ttsPcm16Mono16kBase64": base64.b64encode(turn.tts_pcm).decode("ascii"),
        "audioFormat": SUPPORTED_FORMAT,
        "bytesReceived": len(pcm),
        "provider": turn.provider,
        "routeEvidence": {
            "inputName": route.input_name,
            "inputType": route.input_type,
            "outputName": route.output_name,
            "outputType": route.output_type,
            "wearableActive": route.wearable_active,
            "message": route.message,
        },
    }


def _run_assistant_turn(pcm: bytes, route: RouteSummary) -> AssistantTurn:
    mode = os.environ.get("OTOXAN_VOICE_PROVIDER", "auto").strip().lower()
    if mode not in {"auto", "openai", "proof"}:
        raise VoiceTurnError("OTOXAN_VOICE_PROVIDER must be auto, openai, or proof", 500)

    api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if mode == "proof" or (mode == "auto" and not api_key):
        return _proof_turn(pcm, route, provider="proof")
    if mode == "openai" and not api_key:
        raise VoiceTurnError("OPENAI_API_KEY is required when OTOXAN_VOICE_PROVIDER=openai", 500)

    try:
        return _openai_turn(pcm, route, api_key)
    except Exception as exc:  # noqa: BLE001 - keep the phone loop alive during provider faults.
        if mode == "openai":
            raise VoiceTurnError(f"OpenAI voice provider failed: {exc}", 502) from exc
        fallback = _proof_turn(pcm, route, provider="proof-after-openai-failure")
        return AssistantTurn(
            transcript=fallback.transcript,
            assistant_text=f"Provider fallback active. {fallback.assistant_text}",
            tts_pcm=fallback.tts_pcm,
            provider=fallback.provider,
        )


def _openai_turn(pcm: bytes, route: RouteSummary, api_key: str) -> AssistantTurn:
    transcript = _openai_transcribe(pcm, api_key)
    assistant_text = _openai_chat(transcript, route, api_key)
    tts_pcm = _openai_tts_pcm(assistant_text, api_key)
    return AssistantTurn(
        transcript=transcript,
        assistant_text=assistant_text,
        tts_pcm=tts_pcm,
        provider="openai",
    )


def _openai_transcribe(pcm: bytes, api_key: str) -> str:
    model = os.environ.get("OPENAI_STT_MODEL", "gpt-4o-mini-transcribe")
    wav_bytes = _pcm16_to_wav_bytes(pcm)
    body, content_type = _multipart_form_data(
        fields={"model": model, "response_format": "json"},
        files={"file": ("otoxan-turn.wav", "audio/wav", wav_bytes)},
    )
    data = _http_request_json(
        "POST",
        f"{_openai_base_url()}/audio/transcriptions",
        api_key,
        body,
        content_type,
        timeout=60,
    )
    text = _clean(data.get("text"), "").strip()
    return text or "I heard audio but transcription returned no text."


def _openai_chat(transcript: str, route: RouteSummary, api_key: str) -> str:
    model = os.environ.get("OPENAI_CHAT_MODEL", "gpt-4o-mini")
    payload = {
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are Xander, an Otoxan controller operator speaking through "
                    "Ray-Ban Meta glasses. Reply in one short spoken sentence. "
                    "Be direct, useful, and under 25 words."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"Route: input={route.input_name} ({route.input_type}), "
                    f"output={route.output_name} ({route.output_type}). "
                    f"User said: {transcript}"
                ),
            },
        ],
        "temperature": 0.4,
        "max_tokens": 80,
    }
    data = _http_request_json(
        "POST",
        f"{_openai_base_url()}/chat/completions",
        api_key,
        json.dumps(payload).encode("utf-8"),
        "application/json",
        timeout=60,
    )
    try:
        text = data["choices"][0]["message"]["content"]
    except Exception as exc:  # noqa: BLE001
        raise VoiceTurnError("OpenAI chat response missing assistant text", 502) from exc
    return _clean(text, "Xander heard you.").strip() or "Xander heard you."


def _openai_tts_pcm(text: str, api_key: str) -> bytes:
    model = os.environ.get("OPENAI_TTS_MODEL", "gpt-4o-mini-tts")
    voice = os.environ.get("OPENAI_TTS_VOICE", "alloy")
    payload = {"model": model, "voice": voice, "input": text, "response_format": "mp3"}
    mp3 = _http_request_bytes(
        "POST",
        f"{_openai_base_url()}/audio/speech",
        api_key,
        json.dumps(payload).encode("utf-8"),
        "application/json",
        timeout=90,
    )
    return _ffmpeg_audio_to_pcm16(mp3, suffix=".mp3")


def _proof_turn(pcm: bytes, route: RouteSummary, provider: str) -> AssistantTurn:
    transcript = f"Received {len(pcm)} bytes from {route.input_name} ({route.input_type})."
    assistant_text = f"Xander heard you. The Ray-Ban voice route is live on {route.output_name}."
    return AssistantTurn(
        transcript=transcript,
        assistant_text=assistant_text,
        tts_pcm=_proof_tone_pcm(),
        provider=provider,
    )


def _http_request_json(method: str, url: str, api_key: str, body: bytes, content_type: str, timeout: int) -> Any:
    raw = _http_request_bytes(method, url, api_key, body, content_type, timeout)
    return json.loads(raw.decode("utf-8"))


def _http_request_bytes(method: str, url: str, api_key: str, body: bytes, content_type: str, timeout: int) -> bytes:
    req = urlrequest.Request(
        url,
        data=body,
        method=method,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": content_type,
        },
    )
    try:
        with urlrequest.urlopen(req, timeout=timeout) as response:
            return response.read()
    except urlerror.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:600]
        raise VoiceTurnError(f"provider HTTP {exc.code}: {detail}", 502) from exc


def _openai_base_url() -> str:
    return os.environ.get("OPENAI_BASE_URL", OPENAI_DEFAULT_BASE_URL).rstrip("/")


def _multipart_form_data(fields: Mapping[str, str], files: Mapping[str, tuple[str, str, bytes]]) -> tuple[bytes, str]:
    boundary = "----otoxan-mobile-boundary"
    body = bytearray()
    for name, value in fields.items():
        body.extend(f"--{boundary}\r\n".encode())
        body.extend(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
        body.extend(str(value).encode())
        body.extend(b"\r\n")
    for name, (filename, mime, content) in files.items():
        body.extend(f"--{boundary}\r\n".encode())
        body.extend(
            f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'.encode()
        )
        body.extend(f"Content-Type: {mime}\r\n\r\n".encode())
        body.extend(content)
        body.extend(b"\r\n")
    body.extend(f"--{boundary}--\r\n".encode())
    return bytes(body), f"multipart/form-data; boundary={boundary}"


def _pcm16_to_wav_bytes(pcm: bytes) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(pcm)
    return buf.getvalue()


def _ffmpeg_audio_to_pcm16(audio: bytes, suffix: str) -> bytes:
    with tempfile.NamedTemporaryFile(suffix=suffix) as src, tempfile.NamedTemporaryFile(suffix=".pcm") as dst:
        src.write(audio)
        src.flush()
        cmd = [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            src.name,
            "-f",
            "s16le",
            "-acodec",
            "pcm_s16le",
            "-ac",
            "1",
            "-ar",
            str(SAMPLE_RATE),
            dst.name,
        ]
        subprocess.run(cmd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return dst.read()


def _parse_route(value: Any) -> RouteSummary:
    if not isinstance(value, Mapping):
        raise VoiceTurnError("routeEvidence must be an object", 400)
    return RouteSummary(
        input_name=_clean(value.get("inputName"), "selected input"),
        input_type=_clean(value.get("inputType"), "unknown input type"),
        output_name=_clean(value.get("outputName"), "selected output"),
        output_type=_clean(value.get("outputType"), "unknown output type"),
        wearable_active=bool(value.get("wearableActive")),
        message=_clean(value.get("message"), ""),
    )


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
                f"provider={response.get('provider')} "
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
