import base64
import importlib.util
import os
import sys
from pathlib import Path
from unittest import mock
import unittest


MODULE_PATH = Path(__file__).resolve().parents[4] / "tools" / "voice_turn_server.py"
spec = importlib.util.spec_from_file_location("voice_turn_server", MODULE_PATH)
voice_turn_server = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules["voice_turn_server"] = voice_turn_server
spec.loader.exec_module(voice_turn_server)


class VoiceTurnServerTest(unittest.TestCase):
    def setUp(self):
        self._old_env = {
            key: os.environ.get(key)
            for key in (
                "OTOXAN_VOICE_PROVIDER",
                "OTOXAN_DEBUG_TRANSCRIPT",
                "OTOXAN_HERMES_BIN",
                "OTOXAN_XANDER_TIMEOUT_SECONDS",
            )
        }
        os.environ["OTOXAN_VOICE_PROVIDER"] = "proof"

    def tearDown(self):
        for key, value in self._old_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

    def _payload(self):
        return {
            "format": "pcm_s16le_16khz_mono",
            "pcm16Mono16kBase64": base64.b64encode(b"\x01\x02" * 160).decode("ascii"),
            "routeEvidence": {
                "inputName": "Ray-Ban Meta",
                "inputType": "TYPE_BLE_HEADSET",
                "outputName": "Ray-Ban Meta",
                "outputType": "TYPE_BLE_HEADSET",
                "wearableActive": True,
                "message": "setCommunicationDevice=true",
            },
        }

    def test_handle_voice_turn_returns_mobile_contract(self):
        result = voice_turn_server.handle_voice_turn(self._payload())

        self.assertTrue(result["ok"])
        self.assertIn("Ray-Ban Meta", result["transcript"])
        self.assertIn("Xander heard you", result["assistantText"])
        self.assertEqual("pcm_s16le_16khz_mono", result["audioFormat"])
        self.assertEqual("proof", result["provider"])
        self.assertEqual(320, result["bytesReceived"])
        self.assertGreater(len(base64.b64decode(result["ttsPcm16Mono16kBase64"])), 1000)

    def test_default_provider_calls_xander_session_not_proof(self):
        os.environ.pop("OTOXAN_VOICE_PROVIDER", None)
        with mock.patch.object(voice_turn_server, "_ask_xander_session", return_value="I am live through the phone."):
            result = voice_turn_server.handle_voice_turn(self._payload())

        self.assertTrue(result["ok"])
        self.assertEqual("xander-session", result["provider"])
        self.assertIn("PCM reached the Xander session adapter", result["transcript"])
        self.assertEqual("I am live through the phone.", result["assistantText"])
        self.assertEqual(b"", base64.b64decode(result["ttsPcm16Mono16kBase64"]))
        self.assertEqual(320, result["bytesReceived"])

    def test_xander_provider_can_use_debug_transcript(self):
        os.environ["OTOXAN_VOICE_PROVIDER"] = "xander-session"
        os.environ["OTOXAN_DEBUG_TRANSCRIPT"] = "Matt says hello Xander"
        with mock.patch.object(voice_turn_server, "_ask_xander_session", return_value="Hello Matt, I hear you now.") as ask:
            result = voice_turn_server.handle_voice_turn(self._payload())

        ask.assert_called_once()
        self.assertEqual("Matt says hello Xander", ask.call_args.args[0])
        self.assertEqual("xander-session", result["provider"])
        self.assertEqual("Hello Matt, I hear you now.", result["assistantText"])

    def test_xander_transcript_uses_hermes_stt_lane_before_evidence_fallback(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        route = voice_turn_server.RouteSummary("Ray-Ban", "TYPE_BLE_HEADSET", "Ray-Ban", "TYPE_BLE_HEADSET", True, "")
        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value="actual spoken words"):
            transcript = voice_turn_server._xander_transcript(b"\x01\x02" * 160, route)
        self.assertEqual("actual spoken words", transcript)

    def test_xander_transcript_falls_back_to_route_evidence_when_stt_lane_empty(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        route = voice_turn_server.RouteSummary("Ray-Ban", "TYPE_BLE_HEADSET", "Ray-Ban", "TYPE_BLE_HEADSET", True, "")
        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=""):
            transcript = voice_turn_server._xander_transcript(b"\x01\x02" * 160, route)
        self.assertIn("Hermes STT lane did not return", transcript)

    def test_xander_provider_failure_is_session_framed(self):
        os.environ["OTOXAN_VOICE_PROVIDER"] = "xander-session"
        with mock.patch.object(voice_turn_server, "_ask_xander_session", side_effect=RuntimeError("boom")):
            with self.assertRaises(voice_turn_server.VoiceTurnError) as raised:
                voice_turn_server.handle_voice_turn(self._payload())

        self.assertEqual(502, raised.exception.status)
        message = str(raised.exception)
        self.assertIn("Xander session provider failed", message)
        self.assertNotIn("provider key", message.lower())

    def test_cloud_provider_mode_is_rejected(self):
        os.environ["OTOXAN_VOICE_PROVIDER"] = "generic-cloud"
        with self.assertRaises(voice_turn_server.VoiceTurnError) as raised:
            voice_turn_server.handle_voice_turn(self._payload())
        self.assertEqual(500, raised.exception.status)
        self.assertIn("xander-session", str(raised.exception))
        self.assertNotIn("provider key", str(raised.exception).lower())

    def test_handle_voice_turn_rejects_bad_audio(self):
        payload = self._payload()
        payload["pcm16Mono16kBase64"] = "not-base64"
        with self.assertRaises(voice_turn_server.VoiceTurnError) as raised:
            voice_turn_server.handle_voice_turn(payload)
        self.assertEqual(400, raised.exception.status)
        self.assertIn("valid base64", str(raised.exception))

    def test_handle_voice_turn_requires_route_evidence_object(self):
        base_payload = {
            "format": "pcm_s16le_16khz_mono",
            "pcm16Mono16kBase64": base64.b64encode(b"\x01\x02" * 160).decode("ascii"),
        }
        for route_value in (None, [], ""):
            payload = dict(base_payload)
            if route_value is not None:
                payload["routeEvidence"] = route_value
            with self.subTest(route_value=route_value):
                with self.assertRaises(voice_turn_server.VoiceTurnError) as raised:
                    voice_turn_server.handle_voice_turn(payload)
                self.assertEqual(400, raised.exception.status)
                self.assertIn("routeEvidence", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
