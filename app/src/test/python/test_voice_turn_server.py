import base64
import importlib.util
import os
import sys
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).resolve().parents[4] / "tools" / "voice_turn_server.py"
spec = importlib.util.spec_from_file_location("voice_turn_server", MODULE_PATH)
voice_turn_server = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules["voice_turn_server"] = voice_turn_server
spec.loader.exec_module(voice_turn_server)


class VoiceTurnServerTest(unittest.TestCase):
    def setUp(self):
        self._old_provider = os.environ.get("OTOXAN_VOICE_PROVIDER")
        os.environ["OTOXAN_VOICE_PROVIDER"] = "proof"

    def tearDown(self):
        if self._old_provider is None:
            os.environ.pop("OTOXAN_VOICE_PROVIDER", None)
        else:
            os.environ["OTOXAN_VOICE_PROVIDER"] = self._old_provider

    def test_handle_voice_turn_returns_mobile_contract(self):
        result = voice_turn_server.handle_voice_turn(
            {
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
        )

        self.assertTrue(result["ok"])
        self.assertIn("Ray-Ban Meta", result["transcript"])
        self.assertIn("Xander heard you", result["assistantText"])
        self.assertEqual("pcm_s16le_16khz_mono", result["audioFormat"])
        self.assertEqual("proof", result["provider"])
        self.assertEqual(320, result["bytesReceived"])
        self.assertGreater(len(base64.b64decode(result["ttsPcm16Mono16kBase64"])), 1000)

    def test_handle_voice_turn_requires_key_when_openai_forced(self):
        old_key = os.environ.pop("OPENAI_API_KEY", None)
        os.environ["OTOXAN_VOICE_PROVIDER"] = "openai"
        try:
            with self.assertRaises(voice_turn_server.VoiceTurnError) as raised:
                voice_turn_server.handle_voice_turn(
                    {
                        "format": "pcm_s16le_16khz_mono",
                        "pcm16Mono16kBase64": base64.b64encode(b"\x01\x02" * 160).decode("ascii"),
                        "routeEvidence": {},
                    }
                )
            self.assertEqual(500, raised.exception.status)
            self.assertIn("OPENAI_API_KEY", str(raised.exception))
        finally:
            if old_key is not None:
                os.environ["OPENAI_API_KEY"] = old_key

    def test_handle_voice_turn_rejects_bad_audio(self):
        with self.assertRaises(voice_turn_server.VoiceTurnError) as raised:
            voice_turn_server.handle_voice_turn(
                {
                    "format": "pcm_s16le_16khz_mono",
                    "pcm16Mono16kBase64": "not-base64",
                    "routeEvidence": {},
                }
            )
        self.assertEqual(400, raised.exception.status)
        self.assertIn("valid base64", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
