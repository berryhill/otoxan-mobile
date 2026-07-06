import base64
import importlib.util
import os
import sys
import tempfile
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
                "OTOXAN_HERMES_CWD",
                "OTOXAN_XANDER_TIMEOUT_SECONDS",
                "OTOXAN_VOICE_METRICS_JSONL",
                "OTOXAN_MOBILE_FAST_PROVIDER",
                "OTOXAN_XANDER_CONFIG",
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
        self.assertEqual("proof", result["transcriptSource"])
        self.assertEqual("not-run", result["sttStatus"])
        self.assertIsNone(result["sttLatencyMs"])
        self.assertEqual("proof-mode-not-real-speech", result["pass1Status"])
        self.assertFalse(result["pass1Ready"])
        self.assertEqual(320, result["bytesReceived"])
        self.assertEqual(320, result["audioStats"]["bytes"])
        self.assertEqual(10, result["audioStats"]["durationMs"])
        self.assertEqual(513, result["audioStats"]["peak"])
        self.assertEqual(513.0, result["audioStats"]["rms"])
        self.assertGreater(len(base64.b64decode(result["ttsPcm16Mono16kBase64"])), 1000)
        self.assertIsInstance(result["timing"], dict)
        self.assertIsInstance(result["backendTotalMs"], int)
        self.assertIsInstance(result["decodePcmMs"], int)
        self.assertIsInstance(result["audioStatsMs"], int)
        self.assertEqual(0, result["transcriptTotalMs"])
        self.assertIsNone(result["xanderSessionMs"])
        self.assertIsInstance(result["responseBuildMs"], int)

    def test_default_provider_calls_xander_session_not_proof(self):
        os.environ.pop("OTOXAN_VOICE_PROVIDER", None)
        os.environ["OTOXAN_DEBUG_TRANSCRIPT"] = "Matt says hello Xander"
        with mock.patch.object(voice_turn_server, "_ask_xander_session", return_value="I am live through the phone."):
            result = voice_turn_server.handle_voice_turn(self._payload())

        self.assertTrue(result["ok"])
        self.assertEqual("xander-session", result["provider"])
        self.assertEqual("debug", result["transcriptSource"])
        self.assertEqual("not-run", result["sttStatus"])
        self.assertEqual("debug-transcript-not-real-speech", result["pass1Status"])
        self.assertFalse(result["pass1Ready"])
        self.assertEqual("Matt says hello Xander", result["transcript"])
        self.assertEqual("I am live through the phone.", result["assistantText"])
        self.assertEqual(b"", base64.b64decode(result["ttsPcm16Mono16kBase64"]))
        self.assertEqual(320, result["bytesReceived"])
        self.assertIsInstance(result["backendTotalMs"], int)
        self.assertIsInstance(result["transcriptTotalMs"], int)
        self.assertIsInstance(result["xanderSessionMs"], int)

    def test_xander_provider_can_use_debug_transcript(self):
        os.environ["OTOXAN_VOICE_PROVIDER"] = "xander-session"
        os.environ["OTOXAN_DEBUG_TRANSCRIPT"] = "Matt says hello Xander"
        with mock.patch.object(voice_turn_server, "_ask_xander_session", return_value="Hello Matt, I hear you now.") as ask:
            result = voice_turn_server.handle_voice_turn(self._payload())

        ask.assert_called_once()
        self.assertEqual("Matt says hello Xander", ask.call_args.args[0])
        self.assertEqual("xander-session", result["provider"])
        self.assertEqual("debug", result["transcriptSource"])
        self.assertEqual("not-run", result["sttStatus"])
        self.assertEqual("debug-transcript-not-real-speech", result["pass1Status"])
        self.assertFalse(result["pass1Ready"])
        self.assertEqual("Hello Matt, I hear you now.", result["assistantText"])

    def test_xander_transcript_uses_hermes_stt_lane_before_evidence_fallback(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        route = voice_turn_server.RouteSummary("Ray-Ban", "TYPE_BLE_HEADSET", "Ray-Ban", "TYPE_BLE_HEADSET", True, "")
        stt = voice_turn_server.SttResult("actual spoken words", "success", 17)
        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=stt):
            transcript = voice_turn_server._xander_transcript(b"\x01\x02" * 160, route)
        self.assertEqual("actual spoken words", transcript.transcript)
        self.assertEqual("hermes-stt", transcript.source)
        self.assertEqual("success", transcript.stt_status)
        self.assertEqual(17, transcript.stt_latency_ms)

    def test_xander_transcript_falls_back_to_route_evidence_when_stt_lane_empty(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        route = voice_turn_server.RouteSummary("Ray-Ban", "TYPE_BLE_HEADSET", "Ray-Ban", "TYPE_BLE_HEADSET", True, "")
        stt = voice_turn_server.SttResult("", "empty", 23)
        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=stt):
            transcript = voice_turn_server._xander_transcript(b"\x01\x02" * 160, route)
        self.assertIn("Hermes STT lane did not return", transcript.transcript)
        self.assertEqual("route-evidence-fallback", transcript.source)
        self.assertEqual("empty", transcript.stt_status)
        self.assertEqual(23, transcript.stt_latency_ms)

    def test_xander_turn_does_not_fake_response_when_stt_lane_empty(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        os.environ["OTOXAN_VOICE_PROVIDER"] = "xander-session"
        payload = self._payload()
        stt = voice_turn_server.SttResult("", "empty", 31)
        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=stt):
            with mock.patch.object(voice_turn_server, "_ask_xander_session") as ask:
                result = voice_turn_server.handle_voice_turn(payload)

        ask.assert_not_called()
        self.assertEqual("xander-session", result["provider"])
        self.assertEqual("route-evidence-fallback", result["transcriptSource"])
        self.assertEqual("empty", result["sttStatus"])
        self.assertEqual(31, result["sttLatencyMs"])
        self.assertEqual(31, result["timing"]["sttLatencyMs"])
        self.assertIsInstance(result["transcriptTotalMs"], int)
        self.assertIsNone(result["xanderSessionMs"])
        self.assertEqual("stt-empty", result["pass1Status"])
        self.assertFalse(result["pass1Ready"])
        self.assertIn("I got audio from Ray-Ban Meta", result["assistantText"])
        self.assertIn("couldn't decode words", result["assistantText"])
        self.assertIn("Hermes STT lane did not return", result["transcript"])

    def test_xander_provider_failure_is_session_framed(self):
        os.environ["OTOXAN_VOICE_PROVIDER"] = "xander-session"
        os.environ["OTOXAN_DEBUG_TRANSCRIPT"] = "Matt says hello Xander"
        with mock.patch.object(voice_turn_server, "_ask_xander_session", side_effect=RuntimeError("boom")):
            with self.assertRaises(voice_turn_server.VoiceTurnError) as raised:
                voice_turn_server.handle_voice_turn(self._payload())

        self.assertEqual(502, raised.exception.status)
        message = str(raised.exception)
        self.assertIn("Xander session provider failed", message)
        self.assertNotIn("provider key", message.lower())

    def test_xander_turn_marks_pass1_ready_when_hermes_stt_succeeds(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        os.environ["OTOXAN_VOICE_PROVIDER"] = "xander-session"
        payload = self._payload()
        stt = voice_turn_server.SttResult("say pineapple if you heard me", "success", 44)
        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=stt):
            with mock.patch.object(voice_turn_server, "_ask_xander_session", return_value="Pineapple. I heard you clearly."):
                result = voice_turn_server.handle_voice_turn(payload)

        self.assertEqual("hermes-stt", result["transcriptSource"])
        self.assertEqual("success", result["sttStatus"])
        self.assertEqual(44, result["sttLatencyMs"])
        self.assertEqual(44, result["timing"]["sttLatencyMs"])
        self.assertIsInstance(result["xanderSessionMs"], int)
        self.assertEqual("real-speech-proven", result["pass1Status"])
        self.assertTrue(result["pass1Ready"])
        self.assertIn("pineapple", result["assistantText"].lower())

    def test_mobile_fast_provider_uses_stt_and_fast_xander_lane(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        os.environ["OTOXAN_VOICE_PROVIDER"] = "mobile-fast"
        payload = self._payload()
        stt = voice_turn_server.SttResult("give me the latency status", "success", 33)
        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=stt):
            with mock.patch.object(voice_turn_server, "_ask_xander_mobile_fast", return_value="Fast lane is live; telemetry will show the cut.") as ask:
                result = voice_turn_server.handle_voice_turn(payload)

        ask.assert_called_once()
        self.assertEqual("mobile-fast", result["provider"])
        self.assertEqual("hermes-stt", result["transcriptSource"])
        self.assertEqual("success", result["sttStatus"])
        self.assertEqual(33, result["sttLatencyMs"])
        self.assertEqual(33, result["timing"]["sttLatencyMs"])
        self.assertIsInstance(result["xanderSessionMs"], int)
        self.assertIsInstance(result["timing"]["xanderFastMs"], int)
        self.assertEqual("real-speech-proven", result["pass1Status"])
        self.assertTrue(result["pass1Ready"])
        self.assertIn("Fast lane", result["assistantText"])

    def test_mobile_fast_provider_falls_back_to_session_instead_of_hard_failing_after_stt(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        os.environ["OTOXAN_VOICE_PROVIDER"] = "mobile-fast"
        payload = self._payload()
        stt = voice_turn_server.SttResult("real words decoded", "success", 33)
        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=stt):
            with mock.patch.object(voice_turn_server, "_ask_xander_mobile_fast", side_effect=RuntimeError("provider exploded")):
                with mock.patch.object(voice_turn_server, "_ask_xander_session", return_value="Fallback session answered clearly.") as fallback:
                    result = voice_turn_server.handle_voice_turn(payload)

        fallback.assert_called_once()
        self.assertTrue(result["ok"])
        self.assertEqual("mobile-fast", result["provider"])
        self.assertEqual("real-speech-proven", result["pass1Status"])
        self.assertTrue(result["pass1Ready"])
        self.assertEqual("Fallback session answered clearly.", result["assistantText"])
        self.assertEqual(0, result["timing"]["xanderFastStatus"])
        self.assertEqual(1, result["timing"]["xanderFallbackSessionStatus"])
        self.assertIsInstance(result["xanderSessionMs"], int)

    def test_mobile_fast_provider_returns_non_internal_degraded_response_if_all_model_lanes_fail(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        os.environ["OTOXAN_VOICE_PROVIDER"] = "mobile-fast"
        payload = self._payload()
        stt = voice_turn_server.SttResult("real words decoded", "success", 33)
        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=stt):
            with mock.patch.object(voice_turn_server, "_ask_xander_mobile_fast", side_effect=RuntimeError("provider exploded")):
                with mock.patch.object(voice_turn_server, "_ask_xander_session", side_effect=RuntimeError("fallback exploded")):
                    result = voice_turn_server.handle_voice_turn(payload)

        self.assertTrue(result["ok"])
        self.assertEqual("mobile-fast", result["provider"])
        self.assertEqual("real-speech-proven", result["pass1Status"])
        self.assertTrue(result["pass1Ready"])
        self.assertIn("voice loop is live", result["assistantText"])
        self.assertNotIn("Fast lane", result["assistantText"])
        self.assertNotIn("provider", result["assistantText"].lower())
        self.assertEqual(0, result["timing"]["xanderFastStatus"])
        self.assertEqual(0, result["timing"]["xanderFallbackSessionStatus"])
        self.assertIsInstance(result["xanderSessionMs"], int)

    def test_mobile_fast_direct_provider_shapes_response_without_leaking_reasoning(self):
        route = voice_turn_server.RouteSummary("RB Meta", "TYPE_BLUETOOTH_SCO", "RB Meta", "TYPE_BLUETOOTH_SCO", True, "")
        fake_config = {
            "providers": {
                "fast-test": {
                    "base_url": "https://example.invalid/v1",
                    "api_key": "test-key",
                    "model": "fast-model",
                }
            }
        }
        response_body = {
            "choices": [
                {
                    "message": {
                        "content": "<think>private reasoning</think>Sure, fast response works. Extra sentence removed."
                    }
                }
            ]
        }

        class FakeResponse:
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False
            def read(self):
                return voice_turn_server.json.dumps(response_body).encode("utf-8")

        os.environ["OTOXAN_MOBILE_FAST_PROVIDER"] = "fast-test"
        with mock.patch.object(voice_turn_server, "_load_xander_config", return_value=fake_config):
            with mock.patch.object(voice_turn_server.urllib.request, "urlopen", return_value=FakeResponse()) as urlopen:
                text = voice_turn_server._ask_xander_mobile_fast("test transcript", route)

        self.assertEqual("fast response works.", text)
        request = urlopen.call_args.args[0]
        body = voice_turn_server.json.loads(request.data.decode("utf-8"))
        self.assertEqual("fast-model", body["model"])
        self.assertIn("max 16 words", body["messages"][0]["content"])
        self.assertEqual(256, body["max_tokens"])
        self.assertTrue(body["reasoning_split"])
        self.assertNotIn("test-key", str(body))

    def test_mobile_fast_reasoning_only_output_raises_for_fallback(self):
        route = voice_turn_server.RouteSummary("RB Meta", "TYPE_BLUETOOTH_SCO", "RB Meta", "TYPE_BLUETOOTH_SCO", True, "")
        fake_config = {
            "providers": {
                "fast-test": {
                    "base_url": "https://example.invalid/v1",
                    "api_key": "test-key",
                    "model": "fast-model",
                }
            }
        }
        response_body = {"choices": [{"message": {"content": "<think>unfinished reasoning without spoken answer"}}]}

        class FakeResponse:
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False
            def read(self):
                return voice_turn_server.json.dumps(response_body).encode("utf-8")

        os.environ["OTOXAN_MOBILE_FAST_PROVIDER"] = "fast-test"
        with mock.patch.object(voice_turn_server, "_load_xander_config", return_value=fake_config):
            with mock.patch.object(voice_turn_server.urllib.request, "urlopen", return_value=FakeResponse()):
                with self.assertRaises(voice_turn_server.VoiceTurnError) as raised:
                    voice_turn_server._ask_xander_mobile_fast("test transcript", route)

        self.assertIn("no spoken content", str(raised.exception))

    def test_xander_session_prompt_preserves_mobile_xander_voice_contract(self):
        os.environ["OTOXAN_HERMES_BIN"] = "/bin/hermes-test"
        os.environ["OTOXAN_HERMES_CWD"] = "/tmp"
        route = voice_turn_server.RouteSummary("RB Meta 03YS", "TYPE_BLUETOOTH_SCO", "RB Meta 03YS", "TYPE_BLUETOOTH_SCO", True, "")
        completed = voice_turn_server.subprocess.CompletedProcess(
            args=[], returncode=0, stdout="I'm on the Ray-Ban route; the control loop is live.\n", stderr=""
        )
        with mock.patch.object(voice_turn_server.subprocess, "run", return_value=completed) as run:
            text = voice_turn_server._ask_xander_session("Xander, say pineapple if you heard me", route)

        self.assertIn("control loop", text)
        command = run.call_args.args[0]
        prompt = command[command.index("-z") + 1]
        self.assertIn("Otoxan controller operator", prompt)
        self.assertIn("not a generic assistant", prompt)
        self.assertIn("builder-first", prompt)
        self.assertIn("No filler", prompt)
        self.assertIn("Matt said", prompt)
        self.assertIn("Xander, say pineapple if you heard me", prompt)
        self.assertIn("RB Meta 03YS", prompt)

    def test_xander_session_shapes_generic_or_long_output_for_glasses_audio(self):
        generic = (
            "Sure, I can help with that. This second sentence should be removed.\n"
            "Extra detail should not be spoken."
        )
        self.assertEqual("I can help with that.", voice_turn_server._shape_mobile_spoken_response(generic))

        long = " ".join(f"word{i}" for i in range(30))
        shaped = voice_turn_server._shape_mobile_spoken_response(long)
        self.assertLessEqual(len(shaped.split()), voice_turn_server.XANDER_MOBILE_MAX_WORDS)
        self.assertTrue(shaped.endswith("."))

    def test_xander_session_returns_shaped_spoken_response(self):
        os.environ["OTOXAN_HERMES_BIN"] = "/bin/hermes-test"
        os.environ["OTOXAN_HERMES_CWD"] = "/tmp"
        route = voice_turn_server.RouteSummary("RB Meta 03YS", "TYPE_BLUETOOTH_SCO", "RB Meta 03YS", "TYPE_BLUETOOTH_SCO", True, "")
        completed = voice_turn_server.subprocess.CompletedProcess(
            args=[], returncode=0, stdout="Certainly, I heard the Ray-Ban route. Extra sentence should be stripped.\n", stderr=""
        )
        with mock.patch.object(voice_turn_server.subprocess, "run", return_value=completed):
            text = voice_turn_server._ask_xander_session("test", route)
        self.assertEqual("I heard the Ray-Ban route.", text)

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

    def test_voice_turn_metrics_persists_sanitized_jsonl(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "metrics.jsonl"
            os.environ["OTOXAN_VOICE_METRICS_JSONL"] = str(path)
            payload = {
                "type": "otoxan_mobile_voice_turn_metrics",
                "schemaVersion": 1,
                "turn": {"turnId": "turn-123", "success": True, "stage": "complete"},
                "totals": {"turnTotalMs": 1234},
                "backend": {"roundTripMs": 456},
                "playback": {"kind": "android_tts"},
                "verdict": {
                    "pass1Ready": True,
                    "transcriptLength": 12,
                    "assistantTextLength": 34,
                    "transcript": "do not persist raw speech",
                    "assistantText": "do not persist assistant text",
                },
                "transcript": "top level raw speech should be stripped",
            }

            result = voice_turn_server.handle_voice_turn_metrics(payload, remote_addr="127.0.0.1")
            latest = voice_turn_server.latest_voice_turn_metrics()

        self.assertTrue(result["ok"])
        self.assertEqual(1, latest["count"])
        stored = latest["latest"]
        self.assertEqual("127.0.0.1", stored["remoteAddr"])
        self.assertEqual("turn-123", stored["payload"]["turn"]["turnId"])
        self.assertNotIn("transcript", stored["payload"])
        self.assertNotIn("transcript", stored["payload"]["verdict"])
        self.assertNotIn("assistantText", stored["payload"]["verdict"])
        self.assertEqual(12, stored["payload"]["verdict"]["transcriptLength"])


if __name__ == "__main__":
    unittest.main()
