#!/usr/bin/env python3
"""Repo-local Otoxan Mobile realtime WebSocket skeleton.

Phase 1 scope is transport only: keep the proven /voice-turn contract intact,
accept PCM16 frames over a WebSocket, expose JSON control/audio events, and
commit the accumulated audio through the existing voice_turn_server handler.

This intentionally avoids hosted realtime providers and external Python
WebSocket dependencies so the mobile edge can be debugged from a clean clone.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import os
import struct
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Mapping

import voice_turn_server

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
SUPPORTED_FORMAT = voice_turn_server.SUPPORTED_FORMAT
MAX_BUFFERED_PCM_BYTES = int(os.environ.get("OTOXAN_REALTIME_MAX_PCM_BYTES", str(voice_turn_server.MAX_PCM_BYTES)))


class WebSocketProtocolError(Exception):
    pass


@dataclass
class RealtimeSession:
    session_id: str = field(default_factory=lambda: f"rt_{uuid.uuid4().hex}")
    audio_format: str = SUPPORTED_FORMAT
    route_evidence: dict[str, Any] = field(default_factory=lambda: {
        "inputName": "unknown",
        "inputType": "unknown",
        "outputName": "unknown",
        "outputType": "unknown",
        "wearableActive": False,
        "message": "realtime route evidence not yet provided",
    })
    buffered_pcm: bytearray = field(default_factory=bytearray)
    created_at: float = field(default_factory=time.monotonic)
    committed_turns: int = 0

    def created_event(self) -> dict[str, Any]:
        return {
            "type": "session.created",
            "sessionId": self.session_id,
            "audioFormat": self.audio_format,
            "transport": "websocket",
            "phase": "phase1-websocket-skeleton",
        }

    def update(self, payload: Mapping[str, Any]) -> dict[str, Any]:
        audio_format = str(payload.get("audioFormat", self.audio_format)).strip() or self.audio_format
        if audio_format != SUPPORTED_FORMAT:
            raise ValueError(f"audioFormat must be {SUPPORTED_FORMAT}")
        self.audio_format = audio_format
        route = payload.get("routeEvidence")
        if isinstance(route, Mapping):
            self.route_evidence = dict(route)
        return {
            "type": "session.updated",
            "sessionId": self.session_id,
            "audioFormat": self.audio_format,
            "routeEvidence": self.route_evidence,
        }

    def append_audio(self, pcm: bytes) -> dict[str, Any]:
        if not pcm:
            return {
                "type": "input_audio.appended",
                "sessionId": self.session_id,
                "bytesReceived": 0,
                "bufferedBytes": len(self.buffered_pcm),
            }
        if len(self.buffered_pcm) + len(pcm) > MAX_BUFFERED_PCM_BYTES:
            raise ValueError(f"buffered PCM exceeds {MAX_BUFFERED_PCM_BYTES} bytes")
        self.buffered_pcm.extend(pcm)
        return {
            "type": "input_audio.appended",
            "sessionId": self.session_id,
            "bytesReceived": len(pcm),
            "bufferedBytes": len(self.buffered_pcm),
        }

    def clear_audio(self) -> dict[str, Any]:
        cleared = len(self.buffered_pcm)
        self.buffered_pcm.clear()
        return {
            "type": "input_audio.cleared",
            "sessionId": self.session_id,
            "clearedBytes": cleared,
        }

    def commit_audio(self) -> dict[str, Any]:
        if not self.buffered_pcm:
            raise ValueError("no PCM buffered for commit")
        pcm = bytes(self.buffered_pcm)
        self.buffered_pcm.clear()
        self.committed_turns += 1
        payload = {
            "format": self.audio_format,
            "pcm16Mono16kBase64": base64.b64encode(pcm).decode("ascii"),
            "routeEvidence": self.route_evidence,
        }
        result = voice_turn_server.handle_voice_turn(payload)
        return {
            "type": "response.completed",
            "sessionId": self.session_id,
            "turnIndex": self.committed_turns,
            "audioFormat": self.audio_format,
            "bytesCommitted": len(pcm),
            "voiceTurn": result,
        }


def websocket_accept_key(client_key: str) -> str:
    return base64.b64encode(hashlib.sha1((client_key + GUID).encode("ascii")).digest()).decode("ascii")


async def read_http_headers(reader: asyncio.StreamReader) -> dict[str, str]:
    raw = await reader.readuntil(b"\r\n\r\n")
    lines = raw.decode("latin1").split("\r\n")
    if not lines or not lines[0].startswith("GET "):
        raise WebSocketProtocolError("expected WebSocket GET handshake")
    headers: dict[str, str] = {":request": lines[0]}
    for line in lines[1:]:
        if not line or ":" not in line:
            continue
        key, value = line.split(":", 1)
        headers[key.strip().lower()] = value.strip()
    return headers


async def write_handshake(writer: asyncio.StreamWriter, headers: Mapping[str, str]) -> None:
    key = headers.get("sec-websocket-key")
    upgrade = headers.get("upgrade", "").lower()
    if not key or upgrade != "websocket":
        raise WebSocketProtocolError("missing WebSocket upgrade headers")
    response = (
        "HTTP/1.1 101 Switching Protocols\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Accept: {websocket_accept_key(key)}\r\n"
        "\r\n"
    )
    writer.write(response.encode("ascii"))
    await writer.drain()


async def read_frame(reader: asyncio.StreamReader) -> tuple[int, bytes]:
    first = await reader.readexactly(2)
    opcode = first[0] & 0x0F
    masked = bool(first[1] & 0x80)
    length = first[1] & 0x7F
    if length == 126:
        length = struct.unpack("!H", await reader.readexactly(2))[0]
    elif length == 127:
        length = struct.unpack("!Q", await reader.readexactly(8))[0]
    mask = await reader.readexactly(4) if masked else b""
    payload = await reader.readexactly(length) if length else b""
    if masked:
        payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    return opcode, payload


async def write_frame(writer: asyncio.StreamWriter, opcode: int, payload: bytes) -> None:
    if len(payload) < 126:
        header = bytes([0x80 | opcode, len(payload)])
    elif len(payload) <= 0xFFFF:
        header = bytes([0x80 | opcode, 126]) + struct.pack("!H", len(payload))
    else:
        header = bytes([0x80 | opcode, 127]) + struct.pack("!Q", len(payload))
    writer.write(header + payload)
    await writer.drain()


async def write_json(writer: asyncio.StreamWriter, event: Mapping[str, Any]) -> None:
    await write_frame(writer, 0x1, json.dumps(event, separators=(",", ":")).encode("utf-8"))


async def handle_ws_client(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    session = RealtimeSession()
    try:
        headers = await read_http_headers(reader)
        await write_handshake(writer, headers)
        await write_json(writer, session.created_event())
        while True:
            opcode, payload = await read_frame(reader)
            if opcode == 0x8:  # close
                await write_frame(writer, 0x8, b"")
                break
            if opcode == 0x9:  # ping
                await write_frame(writer, 0xA, payload)
                continue
            if opcode == 0x2:  # binary PCM chunk
                await write_json(writer, session.append_audio(payload))
                continue
            if opcode != 0x1:
                await write_json(writer, {"type": "error", "sessionId": session.session_id, "error": f"unsupported opcode {opcode}"})
                continue
            event = json.loads(payload.decode("utf-8"))
            response = handle_realtime_event(session, event)
            if response is not None:
                await write_json(writer, response)
    except (asyncio.IncompleteReadError, ConnectionResetError, BrokenPipeError):
        pass
    except Exception as exc:  # noqa: BLE001 - dev skeleton returns an event before closing.
        try:
            await write_json(writer, {"type": "error", "sessionId": session.session_id, "error": str(exc)})
        except Exception:
            pass
    finally:
        writer.close()
        await writer.wait_closed()


def handle_realtime_event(session: RealtimeSession, event: Mapping[str, Any]) -> dict[str, Any] | None:
    event_type = str(event.get("type", "")).strip()
    if event_type == "session.update":
        return session.update(event)
    if event_type == "input_audio.append":
        b64 = str(event.get("pcm16Mono16kBase64", ""))
        return session.append_audio(base64.b64decode(b64) if b64 else b"")
    if event_type == "input_audio.commit":
        return session.commit_audio()
    if event_type == "input_audio.clear":
        return session.clear_audio()
    if event_type == "control.ping":
        return {"type": "control.pong", "sessionId": session.session_id}
    if event_type == "session.close":
        return {"type": "session.closed", "sessionId": session.session_id}
    raise ValueError(f"unsupported realtime event type: {event_type or '<missing>'}")


async def serve(host: str, port: int) -> None:
    server = await asyncio.start_server(handle_ws_client, host, port)
    sockets = ", ".join(str(sock.getsockname()) for sock in server.sockets or [])
    print(f"Otoxan Mobile realtime WebSocket server -> ws://{host}:{port}/realtime ({sockets})", flush=True)
    async with server:
        await server.serve_forever()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8788)
    args = parser.parse_args()
    try:
        asyncio.run(serve(args.host, args.port))
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
