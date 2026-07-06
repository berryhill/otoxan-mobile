import asyncio
import base64
import importlib.util
import json
import os
import secrets
import struct
import sys
from pathlib import Path
from unittest import mock
import unittest


TOOLS_DIR = Path(__file__).resolve().parents[4] / "tools"
sys.path.insert(0, str(TOOLS_DIR))
MODULE_PATH = TOOLS_DIR / "realtime_voice_server.py"
spec = importlib.util.spec_from_file_location("realtime_voice_server", MODULE_PATH)
realtime_voice_server = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules["realtime_voice_server"] = realtime_voice_server
spec.loader.exec_module(realtime_voice_server)


async def _read_server_frame(reader):
    first = await reader.readexactly(2)
    opcode = first[0] & 0x0F
    length = first[1] & 0x7F
    if length == 126:
        length = struct.unpack("!H", await reader.readexactly(2))[0]
    elif length == 127:
        length = struct.unpack("!Q", await reader.readexactly(8))[0]
    payload = await reader.readexactly(length) if length else b""
    return opcode, payload


async def _write_client_frame(writer, opcode, payload):
    mask = secrets.token_bytes(4)
    masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    if len(payload) < 126:
        header = bytes([0x80 | opcode, 0x80 | len(payload)])
    elif len(payload) <= 0xFFFF:
        header = bytes([0x80 | opcode, 0x80 | 126]) + struct.pack("!H", len(payload))
    else:
        header = bytes([0x80 | opcode, 0x80 | 127]) + struct.pack("!Q", len(payload))
    writer.write(header + mask + masked)
    await writer.drain()


async def _write_client_json(writer, event):
    await _write_client_frame(writer, 0x1, json.dumps(event).encode("utf-8"))


async def _read_json_event(reader):
    opcode, payload = await _read_server_frame(reader)
    assert opcode == 0x1
    return json.loads(payload.decode("utf-8"))


class RealtimeVoiceServerUnitTest(unittest.TestCase):
    def setUp(self):
        self._old_provider = os.environ.get("OTOXAN_VOICE_PROVIDER")
        os.environ["OTOXAN_VOICE_PROVIDER"] = "proof"

    def tearDown(self):
        if self._old_provider is None:
            os.environ.pop("OTOXAN_VOICE_PROVIDER", None)
        else:
            os.environ["OTOXAN_VOICE_PROVIDER"] = self._old_provider

    def test_session_update_append_commit_uses_existing_voice_turn_contract(self):
        session = realtime_voice_server.RealtimeSession(session_id="rt_test")
        updated = session.update({
            "type": "session.update",
            "audioFormat": "pcm_s16le_16khz_mono",
            "routeEvidence": {
                "inputName": "Ray-Ban Meta",
                "inputType": "TYPE_BLUETOOTH_SCO",
                "outputName": "Ray-Ban Meta",
                "outputType": "TYPE_BLUETOOTH_SCO",
                "wearableActive": True,
                "message": "test route",
            },
        })
        self.assertEqual("session.updated", updated["type"])

        appended = session.append_audio(b"\x01\x02" * 160)
        self.assertEqual("input_audio.appended", appended["type"])
        self.assertEqual(320, appended["bufferedBytes"])

        completed = session.commit_audio()
        self.assertEqual("response.completed", completed["type"])
        self.assertEqual(320, completed["bytesCommitted"])
        self.assertEqual("proof", completed["voiceTurn"]["provider"])
        self.assertEqual("pcm_s16le_16khz_mono", completed["voiceTurn"]["audioFormat"])
        self.assertEqual(0, len(session.buffered_pcm))

    def test_json_audio_append_accepts_base64_payload(self):
        session = realtime_voice_server.RealtimeSession(session_id="rt_test")
        event = {
            "type": "input_audio.append",
            "pcm16Mono16kBase64": base64.b64encode(b"\x03\x04" * 40).decode("ascii"),
        }
        response = realtime_voice_server.handle_realtime_event(session, event)
        self.assertEqual("input_audio.appended", response["type"])
        self.assertEqual(80, response["bufferedBytes"])


class RealtimeVoiceServerProtocolTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self._old_provider = os.environ.get("OTOXAN_VOICE_PROVIDER")
        os.environ["OTOXAN_VOICE_PROVIDER"] = "proof"
        self.server = await asyncio.start_server(realtime_voice_server.handle_ws_client, "127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        self.server.close()
        await self.server.wait_closed()
        if self._old_provider is None:
            os.environ.pop("OTOXAN_VOICE_PROVIDER", None)
        else:
            os.environ["OTOXAN_VOICE_PROVIDER"] = self._old_provider

    async def test_websocket_handshake_binary_pcm_and_commit(self):
        reader, writer = await asyncio.open_connection("127.0.0.1", self.port)
        client_key = base64.b64encode(secrets.token_bytes(16)).decode("ascii")
        writer.write((
            "GET /realtime HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{self.port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {client_key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "\r\n"
        ).encode("ascii"))
        await writer.drain()
        handshake = await reader.readuntil(b"\r\n\r\n")
        self.assertIn(b"101 Switching Protocols", handshake)

        created = await _read_json_event(reader)
        self.assertEqual("session.created", created["type"])
        self.assertEqual("phase1-websocket-skeleton", created["phase"])

        await _write_client_json(writer, {
            "type": "session.update",
            "routeEvidence": {
                "inputName": "Ray-Ban Meta",
                "inputType": "TYPE_BLUETOOTH_SCO",
                "outputName": "Ray-Ban Meta",
                "outputType": "TYPE_BLUETOOTH_SCO",
                "wearableActive": True,
                "message": "test route",
            },
        })
        updated = await _read_json_event(reader)
        self.assertEqual("session.updated", updated["type"])

        await _write_client_frame(writer, 0x2, b"\x01\x02" * 160)
        appended = await _read_json_event(reader)
        self.assertEqual("input_audio.appended", appended["type"])
        self.assertEqual(320, appended["bufferedBytes"])

        await _write_client_json(writer, {"type": "input_audio.commit"})
        completed = await _read_json_event(reader)
        self.assertEqual("response.completed", completed["type"])
        self.assertEqual(320, completed["bytesCommitted"])
        self.assertEqual("proof", completed["voiceTurn"]["provider"])
        self.assertEqual("TYPE_BLUETOOTH_SCO", completed["voiceTurn"]["routeEvidence"]["inputType"])

        await _write_client_frame(writer, 0x8, b"")
        writer.close()
        await writer.wait_closed()


if __name__ == "__main__":
    unittest.main()
