import base64
import importlib.util
import json
import os
import sys
import tempfile
import threading
import urllib.error
import urllib.request
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
                "OTOXAN_MOBILE_FAST_HARD_TIMEOUT_SECONDS",
                "OTOXAN_MOBILE_FAST_SESSION_FALLBACK",
                "OTOXAN_MOBILE_FALLBACK_HARD_TIMEOUT_SECONDS",
                "OTOXAN_XANDER_CONFIG",
                "OTOXAN_STT_MODE",
                "OTOXAN_STT_PROVIDER",
                "OTOXAN_MOONSHINE_STT_COMMAND",
                "OTOXAN_MOONSHINE_STT_TIMEOUT_SECONDS",
                "OTOXAN_STT_TOTAL_BUDGET_SECONDS",
                "OTOXAN_STT_FALLBACK_MIN_SECONDS",
                "OTOXAN_TTS_PROVIDER",
                "OTOXAN_KOKORO_TTS_COMMAND",
                "OTOXAN_TTS_TIMEOUT_SECONDS",
                "OTOXAN_EXPERIMENTAL_STREAM_TRANSPORT",
            )
        }
        os.environ["OTOXAN_VOICE_PROVIDER"] = "proof"

    def tearDown(self):
        setattr(voice_turn_server, "_STT_TRANSCRIBE_AUDIO", None)
        setattr(voice_turn_server, "_STT_FAST_LOCAL_TRANSCRIBE", None)
        setattr(voice_turn_server, "_STT_LOAD_ERROR", None)
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


    def test_recent_voice_turn_metrics_returns_latest_records_first(self):
        with tempfile.NamedTemporaryFile(delete=False) as fh:
            metrics_path = fh.name
        try:
            os.environ["OTOXAN_VOICE_METRICS_JSONL"] = metrics_path
            for index in range(3):
                packet = {
                    "type": "otoxan_mobile_voice_turn_metrics",
                    "schemaVersion": 1,
                    "turn": {"turnId": f"turn-{index}", "success": True, "stage": "complete"},
                }
                voice_turn_server.handle_voice_turn_metrics(packet, remote_addr="phone")

            recent = voice_turn_server.recent_voice_turn_metrics(limit=2)

            self.assertTrue(recent["ok"])
            self.assertEqual(3, recent["count"])
            self.assertEqual(2, len(recent["records"]))
            self.assertEqual("turn-2", recent["records"][0]["payload"]["turn"]["turnId"])
            self.assertEqual("turn-1", recent["records"][1]["payload"]["turn"]["turnId"])
            self.assertEqual("turn-2", voice_turn_server.latest_voice_turn_metrics()["latest"]["payload"]["turn"]["turnId"])
        finally:
            Path(metrics_path).unlink(missing_ok=True)

    def test_recent_hardware_sweep_summaries_are_text_only_and_run_sheet_shaped(self):
        with tempfile.NamedTemporaryFile(delete=False) as fh:
            metrics_path = fh.name
        try:
            os.environ["OTOXAN_VOICE_METRICS_JSONL"] = metrics_path
            packet = {
                "type": "otoxan_mobile_voice_turn_metrics",
                "schemaVersion": 1,
                "transcript": "do not persist these words",
                "assistantText": "do not persist this answer",
                "sweep": {"scenario": "normal"},
                "turn": {"turnId": "turn-accept", "success": True, "stage": "complete"},
                "route": {
                    "inputName": "Ray-Ban Meta",
                    "inputType": "TYPE_BLE_HEADSET",
                    "outputName": "Ray-Ban Meta",
                    "outputType": "TYPE_BLE_HEADSET",
                    "wearableActiveAtCapture": True,
                },
                "capture": {"capturedBytes": 32000, "expectedBytes": 32000, "actualMs": 1000, "stopReason": "duration_elapsed", "peakAmplitude": 4815},
                "backend": {"roundTripMs": 1200, "sttLatencyMs": 200},
                "perceivedLatency": {"ttfaMs": 1000, "postCaptureAckDelayMs": 80},
                "playback": {"kind": "android-tts", "totalMs": 400},
                "verdict": {
                    "provider": "mobile-fast",
                    "transcriptSource": "hermes-stt",
                    "sttProvider": "hermes-stt",
                    "sttStatus": "success",
                    "pass1Ready": True,
                    "pass1Status": "real-speech-proven",
                    "transcriptLength": 18,
                    "assistantTextLength": 22,
                },
                "totals": {"turnTotalMs": 2600},
            }
            voice_turn_server.handle_voice_turn_metrics(packet, remote_addr="phone")

            result = voice_turn_server.recent_hardware_sweep_summaries(limit=5)

            self.assertTrue(result["ok"])
            self.assertEqual(1, result["count"])
            self.assertIn("no pcm16Mono16kBase64", result["privacy"])
            summary = result["summaries"][0]
            self.assertEqual("turn-accept", summary["runId"])
            self.assertEqual("normal", summary["scenario"])
            self.assertEqual("mobile-fast", summary["backendProviderObserved"])
            self.assertEqual("Ray-Ban Meta", summary["inputName"])
            self.assertEqual(32000, summary["capturedBytes"])
            self.assertEqual(4815, summary["backendPeak"])
            self.assertEqual("pcm_s16le_16khz_mono", summary["audioFormat"])
            self.assertEqual("pass", summary["canonicalTimingTargetResult"])
            self.assertEqual("accept", summary["runDisposition"])
            self.assertTrue(summary["realtimeVadDiagnostic"]["diagnosticOnly"])
            self.assertEqual("energy-vad-phase3", summary["realtimeVadDiagnostic"]["provider"])
            self.assertEqual(700, summary["realtimeVadDiagnostic"]["peakThreshold"])
            self.assertTrue(summary["realtimeVadDiagnostic"]["wouldDetectSpeech"])
            self.assertEqual("diagnostic-detects-speech-threshold", summary["realtimeVadDiagnostic"]["comparison"])
            self.assertTrue(result["realtimeVadComparison"]["diagnosticOnly"])
            self.assertEqual(1, result["realtimeVadComparison"]["runsCompared"])
            self.assertEqual("keep-diagnostic-hardware-comparison-clean", result["realtimeVadComparison"]["recommendation"])
            self.assertNotIn("transcript", summary)
            self.assertNotIn("assistantText", summary)
            serialized = json.dumps(result)
            self.assertNotIn("do not persist", serialized)
            self.assertNotIn("\"pcm16Mono16kBase64\"", serialized)
        finally:
            Path(metrics_path).unlink(missing_ok=True)

    def test_realtime_vad_comparison_flags_reject_scenario_triggers_as_diagnostic_only(self):
        with tempfile.NamedTemporaryFile(delete=False) as fh:
            metrics_path = fh.name
        try:
            os.environ["OTOXAN_VOICE_METRICS_JSONL"] = metrics_path
            packet = {
                "type": "otoxan_mobile_voice_turn_metrics",
                "schemaVersion": 1,
                "sweep": {"scenario": "silence"},
                "turn": {"turnId": "turn-silence", "success": True, "stage": "complete"},
                "route": {
                    "inputName": "Ray-Ban Meta",
                    "inputType": "TYPE_BLUETOOTH_SCO",
                    "outputName": "Ray-Ban Meta",
                    "outputType": "TYPE_BLUETOOTH_SCO",
                    "wearableActiveAtCapture": True,
                },
                "capture": {"capturedBytes": 32000, "expectedBytes": 32000, "actualMs": 1000, "stopReason": "duration_elapsed", "peakAmplitude": 900},
                "backend": {"roundTripMs": 1200},
                "verdict": {
                    "provider": "mobile-fast",
                    "transcriptSource": "hermes-stt",
                    "sttProvider": "hermes-stt",
                    "sttStatus": "empty",
                    "pass1Ready": False,
                    "pass1Status": "stt-empty",
                },
            }
            voice_turn_server.handle_voice_turn_metrics(packet, remote_addr="phone")

            result = voice_turn_server.recent_hardware_sweep_summaries(limit=5)

            summary = result["summaries"][0]
            self.assertEqual("expected-reject", summary["runDisposition"])
            self.assertEqual("diagnostic-would-trigger-on-reject-scenario", summary["realtimeVadDiagnostic"]["comparison"])
            self.assertTrue(summary["realtimeVadDiagnostic"]["diagnosticOnly"])
            self.assertEqual(1, result["realtimeVadComparison"]["rejectScenarioTriggerCount"])
            self.assertEqual("keep-diagnostic", result["realtimeVadComparison"]["recommendation"])
            self.assertIn("non-authoritative", result["realtimeVadComparison"]["policy"])
        finally:
            Path(metrics_path).unlink(missing_ok=True)

    def test_mobile_voice_contract_includes_xander_stanza(self):
        self.assertIn("Otoxan controller builder", voice_turn_server.XANDER_MOBILE_VOICE_CONTRACT)
        self.assertIn("Silas owns the live Frankenstein", voice_turn_server.XANDER_MOBILE_VOICE_CONTRACT)

    def test_handle_voice_turn_returns_mobile_contract(self):
        result = voice_turn_server.handle_voice_turn(self._payload())

        self.assertTrue(result["ok"])
        self.assertIn("Ray-Ban Meta", result["transcript"])
        self.assertIn("Xander heard you", result["assistantText"])
        self.assertEqual("pcm_s16le_16khz_mono", result["audioFormat"])
        self.assertEqual("proof", result["provider"])
        self.assertEqual("proof", result["transcriptSource"])
        self.assertEqual("not-run", result["sttProvider"])
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

    def test_stream_transport_descriptor_is_disabled_without_experimental_flag(self):
        descriptor = voice_turn_server.stream_transport_descriptor()

        self.assertFalse(descriptor["enabled"])
        self.assertEqual("OTOXAN_EXPERIMENTAL_STREAM_TRANSPORT", descriptor["experimentalFlag"])
        self.assertEqual("/voice-stream", descriptor["endpoint"])
        self.assertEqual("/voice-turn", descriptor["canonicalFallback"]["endpoint"])
        self.assertEqual("same_request_response_contract", descriptor["canonicalFallback"]["semantics"])

    def test_handle_voice_stream_wraps_voice_turn_contract_without_persisting_audio(self):
        os.environ["OTOXAN_EXPERIMENTAL_STREAM_TRANSPORT"] = "1"

        events = voice_turn_server.handle_voice_stream(self._payload())

        self.assertEqual(["stream.started", "response.completed", "stream.completed"], [event["type"] for event in events])
        self.assertEqual([1, 2, 3], [event["sequence"] for event in events])
        self.assertTrue(events[0]["transport"]["enabled"])
        self.assertTrue(events[0]["privacy"]["explicitSessionOnly"])
        self.assertFalse(events[0]["privacy"]["rawAudioPersisted"])
        self.assertEqual("proof", events[1]["voiceTurn"]["provider"])
        self.assertEqual(320, events[1]["voiceTurn"]["bytesReceived"])
        self.assertEqual("/voice-turn", events[2]["fallback"]["endpoint"])
        self.assertNotIn("pcm16Mono16kBase64", json.dumps(events[0]))
        self.assertNotIn("pcm16Mono16kBase64", json.dumps(events[2]))

    def test_voice_stream_http_endpoint_is_404_until_experimental_flag_enabled(self):
        server = voice_turn_server.ThreadingHTTPServer(("127.0.0.1", 0), voice_turn_server.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        url = f"http://127.0.0.1:{server.server_address[1]}/voice-stream"
        request = urllib.request.Request(
            url,
            data=json.dumps(self._payload()).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(request, timeout=5)
            self.assertEqual(404, raised.exception.code)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_voice_stream_http_endpoint_returns_ndjson_when_experimental_flag_enabled(self):
        os.environ["OTOXAN_EXPERIMENTAL_STREAM_TRANSPORT"] = "true"
        server = voice_turn_server.ThreadingHTTPServer(("127.0.0.1", 0), voice_turn_server.Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        url = f"http://127.0.0.1:{server.server_address[1]}/voice-stream"
        request = urllib.request.Request(
            url,
            data=json.dumps(self._payload()).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                self.assertEqual("application/x-ndjson", response.headers.get_content_type())
                events = [json.loads(line) for line in response.read().decode("utf-8").splitlines()]
            self.assertEqual(["stream.started", "response.completed", "stream.completed"], [event["type"] for event in events])
            self.assertEqual("proof", events[1]["voiceTurn"]["provider"])
            self.assertEqual("/voice-turn", events[2]["fallback"]["endpoint"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_default_provider_calls_xander_session_not_proof(self):
        os.environ.pop("OTOXAN_VOICE_PROVIDER", None)
        os.environ["OTOXAN_DEBUG_TRANSCRIPT"] = "Matt says hello Xander"
        with mock.patch.object(voice_turn_server, "_ask_xander_session", return_value="I am live through the phone."):
            result = voice_turn_server.handle_voice_turn(self._payload())

        self.assertTrue(result["ok"])
        self.assertEqual("xander-session", result["provider"])
        self.assertEqual("debug", result["transcriptSource"])
        self.assertEqual("not-run", result["sttProvider"])
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
        self.assertEqual("not-run", result["sttProvider"])
        self.assertEqual("not-run", result["sttStatus"])
        self.assertEqual("debug-transcript-not-real-speech", result["pass1Status"])
        self.assertFalse(result["pass1Ready"])
        self.assertEqual("Hello Matt, I hear you now.", result["assistantText"])

    def test_xander_turn_preserves_android_tts_fallback_by_default(self):
        os.environ["OTOXAN_VOICE_PROVIDER"] = "xander-session"
        os.environ["OTOXAN_DEBUG_TRANSCRIPT"] = "Matt says hello Xander"
        with mock.patch.object(voice_turn_server, "_ask_xander_session", return_value="I am speaking through Android fallback."):
            result = voice_turn_server.handle_voice_turn(self._payload())

        self.assertEqual(b"", base64.b64decode(result["ttsPcm16Mono16kBase64"]))
        self.assertEqual("android", result["ttsProvider"])
        self.assertEqual("android-fallback", result["ttsStatus"])
        self.assertIsInstance(result["ttsLatencyMs"], int)

    def test_xander_turn_can_use_kokoro_command_tts_pcm(self):
        os.environ["OTOXAN_VOICE_PROVIDER"] = "xander-session"
        os.environ["OTOXAN_DEBUG_TRANSCRIPT"] = "Matt says hello Xander"
        os.environ["OTOXAN_TTS_PROVIDER"] = "kokoro-command"
        os.environ["OTOXAN_KOKORO_TTS_COMMAND"] = "fake-kokoro --text {text} --output {output}"
        fake_pcm = b"\x10\x00\x20\x00" * 80

        completed = mock.Mock(returncode=0, stdout=fake_pcm)
        with mock.patch.object(voice_turn_server, "_ask_xander_session", return_value="Kokoro path is wired."):
            with mock.patch.object(voice_turn_server.subprocess, "run", return_value=completed) as run:
                result = voice_turn_server.handle_voice_turn(self._payload())

        self.assertEqual(fake_pcm, base64.b64decode(result["ttsPcm16Mono16kBase64"]))
        self.assertEqual("kokoro-command", result["ttsProvider"])
        self.assertEqual("success", result["ttsStatus"])
        self.assertIsInstance(result["ttsLatencyMs"], int)
        command = run.call_args.args[0]
        self.assertEqual("fake-kokoro", command[0])
        self.assertIn("--output", command)

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

    def test_stt_prefers_inprocess_transcriber_without_subprocess_spawn(self):
        os.environ.pop("OTOXAN_STT_MODE", None)
        with mock.patch.object(voice_turn_server, "_load_fast_local_stt", return_value=lambda path: {"success": True, "transcript": "hey xander", "provider": "local-fast"}):
            with mock.patch.object(voice_turn_server.subprocess, "run") as run:
                stt = voice_turn_server._transcribe_with_hermes_stt(b"\x01\x02" * 160)
        run.assert_not_called()
        self.assertEqual("hey xander", stt.transcript)
        self.assertEqual("success", stt.status)
        self.assertIsInstance(stt.latency_ms, int)

    def test_stt_falls_back_to_subprocess_when_inprocess_unavailable(self):
        os.environ.pop("OTOXAN_STT_MODE", None)
        with mock.patch.object(voice_turn_server, "_transcribe_with_inprocess_stt", return_value=voice_turn_server.SttResult("", "inprocess-unavailable", 1)):
            with mock.patch.object(voice_turn_server, "_transcribe_with_subprocess_stt", return_value=voice_turn_server.SttResult("fallback words", "success", 22)) as subprocess_stt:
                stt = voice_turn_server._transcribe_with_hermes_stt(b"\x01\x02" * 160)
        subprocess_stt.assert_called_once()
        self.assertEqual("fallback words", stt.transcript)
        self.assertEqual("success", stt.status)
        self.assertEqual(22, stt.latency_ms)

    def test_stt_subprocess_mode_bypasses_inprocess_loader(self):
        os.environ["OTOXAN_STT_MODE"] = "subprocess"
        with mock.patch.object(voice_turn_server, "_load_inprocess_stt") as loader:
            with mock.patch.object(voice_turn_server, "_transcribe_with_subprocess_stt", return_value=voice_turn_server.SttResult("subprocess words", "success", 44)):
                stt = voice_turn_server._transcribe_with_hermes_stt(b"\x01\x02" * 160)
        loader.assert_not_called()
        self.assertEqual("subprocess words", stt.transcript)

    def test_moonshine_command_stt_runs_before_hermes_fallback(self):
        os.environ["OTOXAN_STT_PROVIDER"] = "moonshine-command"
        os.environ["OTOXAN_MOONSHINE_STT_COMMAND"] = "moonshine-test --input {input} --output {output}"
        completed = mock.Mock(returncode=0, stdout=b'{"success": true, "transcript": "moonshine heard xander"}')
        with mock.patch.object(voice_turn_server.subprocess, "run", return_value=completed) as run:
            with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt_fallback") as fallback:
                stt = voice_turn_server._transcribe_with_hermes_stt(b"\x01\x02" * 160)
        fallback.assert_not_called()
        self.assertEqual("moonshine heard xander", stt.transcript)
        self.assertEqual("success", stt.status)
        self.assertEqual("moonshine-stt", stt.provider)
        command = run.call_args.args[0]
        self.assertEqual("moonshine-test", command[0])
        self.assertIn("--input", command)
        self.assertIn("--output", command)

    def test_moonshine_command_stt_falls_back_to_hermes_when_empty(self):
        os.environ["OTOXAN_STT_PROVIDER"] = "moonshine-command"
        os.environ["OTOXAN_MOONSHINE_STT_COMMAND"] = "moonshine-test --input {input}"
        completed = mock.Mock(returncode=0, stdout=b'{"success": true, "transcript": ""}')
        with mock.patch.object(voice_turn_server.subprocess, "run", return_value=completed):
            with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt_fallback", return_value=voice_turn_server.SttResult("fallback words", "success", 55)) as fallback:
                stt = voice_turn_server._transcribe_with_hermes_stt(b"\x01\x02" * 160)
        fallback.assert_called_once()
        self.assertEqual("fallback words", stt.transcript)
        self.assertEqual("hermes-stt", stt.provider)

    def test_moonshine_fallback_carries_split_latency_and_budget(self):
        os.environ["OTOXAN_STT_PROVIDER"] = "moonshine-command"
        os.environ["OTOXAN_STT_TOTAL_BUDGET_SECONDS"] = "4.5"
        os.environ["OTOXAN_MOONSHINE_STT_COMMAND"] = "moonshine-test --input {input}"
        completed = mock.Mock(returncode=0, stdout=b'{"success": true, "transcript": ""}')
        with mock.patch.object(voice_turn_server.subprocess, "run", return_value=completed):
            with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt_fallback", return_value=voice_turn_server.SttResult("fallback words", "success", 55)) as fallback:
                stt = voice_turn_server._transcribe_with_hermes_stt(b"" * 160)
        self.assertEqual("fallback words", stt.transcript)
        self.assertEqual("empty", stt.primary_status)
        self.assertEqual("moonshine-stt", stt.primary_provider)
        self.assertEqual("success", stt.fallback_status)
        self.assertEqual(55, stt.fallback_latency_ms)
        self.assertIsInstance(stt.budget_remaining_ms, int)
        self.assertIn("timeout_seconds", fallback.call_args.kwargs)
        self.assertLessEqual(fallback.call_args.kwargs["timeout_seconds"], 4.5)

    def test_sprint4_stt_budget_defaults_are_locked_to_closeout_target(self):
        self.assertEqual(1500, voice_turn_server.TIMING_CONTRACT_TARGETS["sttLatencyMs"])
        self.assertEqual(1.5, voice_turn_server.SPRINT4_STT_TOTAL_BUDGET_SECONDS)
        self.assertEqual(0.75, voice_turn_server.SPRINT4_MOONSHINE_PRIMARY_BUDGET_SECONDS)
        self.assertEqual(0.25, voice_turn_server.SPRINT4_STT_FALLBACK_MIN_SECONDS)

        os.environ["OTOXAN_STT_PROVIDER"] = "moonshine-command"
        os.environ["OTOXAN_MOONSHINE_STT_COMMAND"] = "moonshine-test --input {input}"
        completed = mock.Mock(returncode=0, stdout=b'{"success": true, "transcript": ""}')
        with mock.patch.object(voice_turn_server.subprocess, "run", return_value=completed) as run:
            with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt_fallback", return_value=voice_turn_server.SttResult("fallback words", "success", 55)) as fallback:
                stt = voice_turn_server._transcribe_with_hermes_stt(b"\x01\x02" * 160)

        self.assertEqual("fallback words", stt.transcript)
        self.assertLessEqual(run.call_args.kwargs["timeout"], voice_turn_server.SPRINT4_MOONSHINE_PRIMARY_BUDGET_SECONDS)
        self.assertLessEqual(fallback.call_args.kwargs["timeout_seconds"], voice_turn_server.SPRINT4_STT_TOTAL_BUDGET_SECONDS)
        self.assertLessEqual(stt.latency_ms or 0, voice_turn_server.TIMING_CONTRACT_TARGETS["sttLatencyMs"])

    def test_xander_transcript_reports_moonshine_source_when_local_stt_succeeds(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        route = voice_turn_server.RouteSummary("Ray-Ban", "TYPE_BLE_HEADSET", "Ray-Ban", "TYPE_BLE_HEADSET", True, "")
        stt = voice_turn_server.SttResult("actual moonshine words", "success", 19, "moonshine-stt")
        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=stt):
            transcript = voice_turn_server._xander_transcript(b"\x01\x02" * 160, route)
        self.assertEqual("actual moonshine words", transcript.transcript)
        self.assertEqual("moonshine-stt", transcript.source)
        self.assertEqual("moonshine-stt", transcript.stt_provider)
        self.assertEqual("success", transcript.stt_status)
        self.assertEqual(19, transcript.stt_latency_ms)

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
        self.assertEqual("Audio arrived, but words did not decode.", result["assistantText"])
        self.assertLessEqual(len(result["assistantText"]), voice_turn_server.XANDER_SPOKEN_MAX_CHARS)
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
        self.assertIsInstance(result["xanderFastMs"], int)
        self.assertEqual(1, result["xanderFastStatus"])
        self.assertEqual(0, result["xanderFastTimedOut"])
        self.assertIsInstance(result["timing"]["xanderFastMs"], int)
        self.assertEqual("real-speech-proven", result["pass1Status"])
        self.assertTrue(result["pass1Ready"])
        self.assertIn("Fast lane", result["assistantText"])

    def test_mobile_fast_provider_degrades_instead_of_slow_session_fallback_by_default(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        os.environ["OTOXAN_VOICE_PROVIDER"] = "mobile-fast"
        payload = self._payload()
        stt = voice_turn_server.SttResult("real words decoded", "success", 33)
        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=stt):
            with mock.patch.object(voice_turn_server, "_ask_xander_mobile_fast", side_effect=RuntimeError("provider exploded")):
                with mock.patch.object(voice_turn_server, "_ask_xander_session") as fallback:
                    result = voice_turn_server.handle_voice_turn(payload)

        fallback.assert_not_called()
        self.assertTrue(result["ok"])
        self.assertEqual("mobile-fast", result["provider"])
        self.assertEqual("real-speech-proven", result["pass1Status"])
        self.assertTrue(result["pass1Ready"])
        self.assertEqual("Say that again.", result["assistantText"])
        self.assertNotIn("provider", result["assistantText"].lower())
        self.assertEqual(0, result["timing"]["xanderFastStatus"])
        self.assertEqual(0, result["timing"]["xanderFastTimedOut"])
        self.assertEqual(0, result["timing"]["xanderFallbackSessionStatus"])
        self.assertEqual(1, result["timing"]["xanderFallbackSkipped"])
        self.assertIsInstance(result["xanderSessionMs"], int)

    def test_mobile_fast_provider_can_opt_into_session_fallback(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        os.environ["OTOXAN_VOICE_PROVIDER"] = "mobile-fast"
        os.environ["OTOXAN_MOBILE_FAST_SESSION_FALLBACK"] = "1"
        payload = self._payload()
        stt = voice_turn_server.SttResult("real words decoded", "success", 33)
        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=stt):
            with mock.patch.object(voice_turn_server, "_ask_xander_mobile_fast", side_effect=RuntimeError("provider exploded")):
                with mock.patch.object(voice_turn_server, "_ask_xander_session", return_value="Fallback session answered clearly.") as fallback:
                    result = voice_turn_server.handle_voice_turn(payload)

        fallback.assert_called_once()
        self.assertEqual("Fallback session answered clearly.", result["assistantText"])
        self.assertEqual(0, result["timing"]["xanderFastStatus"])
        self.assertEqual(1, result["timing"]["xanderFallbackSessionStatus"])
        self.assertNotIn("xanderFallbackSkipped", result["timing"])

    def test_mobile_fast_provider_hard_timeout_returns_telemetry_safe_degraded_response(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        os.environ["OTOXAN_VOICE_PROVIDER"] = "mobile-fast"
        os.environ["OTOXAN_MOBILE_FAST_HARD_TIMEOUT_SECONDS"] = "0.5"
        payload = self._payload()
        stt = voice_turn_server.SttResult("real words decoded", "success", 33)

        def slow_fast(_transcript, _route):
            voice_turn_server.time.sleep(2)
            return "too late"

        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=stt):
            with mock.patch.object(voice_turn_server, "_ask_xander_mobile_fast", side_effect=slow_fast):
                with mock.patch.object(voice_turn_server, "_ask_xander_session") as fallback:
                    result = voice_turn_server.handle_voice_turn(payload)

        fallback.assert_not_called()
        self.assertTrue(result["ok"])
        self.assertEqual("Say that again.", result["assistantText"])
        self.assertEqual(0, result["xanderFastStatus"])
        self.assertEqual(1, result["xanderFastTimedOut"])
        self.assertEqual(0, result["timing"]["xanderFastStatus"])
        self.assertEqual(1, result["timing"]["xanderFastTimedOut"])
        self.assertEqual(1, result["timing"]["xanderFallbackSkipped"])
        self.assertLess(result["xanderSessionMs"], 1200)

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
        self.assertEqual("Say that again.", result["assistantText"])
        self.assertNotIn("Fast lane", result["assistantText"])
        self.assertNotIn("provider", result["assistantText"].lower())
        self.assertEqual(0, result["timing"]["xanderFastStatus"])
        self.assertEqual(0, result["timing"]["xanderFallbackSessionStatus"])
        self.assertIsInstance(result["xanderSessionMs"], int)


    def test_mobile_fast_opt_in_session_fallback_is_bounded_by_deadline(self):
        os.environ.pop("OTOXAN_DEBUG_TRANSCRIPT", None)
        os.environ["OTOXAN_VOICE_PROVIDER"] = "mobile-fast"
        os.environ["OTOXAN_MOBILE_FAST_SESSION_FALLBACK"] = "1"
        os.environ["OTOXAN_MOBILE_FALLBACK_HARD_TIMEOUT_SECONDS"] = "0.5"
        payload = self._payload()
        stt = voice_turn_server.SttResult("real words decoded", "success", 33)

        def slow_fallback(_transcript, _route):
            voice_turn_server.time.sleep(2)
            return "too late"

        with mock.patch.object(voice_turn_server, "_transcribe_with_hermes_stt", return_value=stt):
            with mock.patch.object(voice_turn_server, "_ask_xander_mobile_fast", side_effect=RuntimeError("provider exploded")):
                with mock.patch.object(voice_turn_server, "_ask_xander_session", side_effect=slow_fallback):
                    result = voice_turn_server.handle_voice_turn(payload)

        self.assertTrue(result["ok"])
        self.assertEqual("Say that again.", result["assistantText"])
        self.assertEqual(0, result["timing"]["xanderFastStatus"])
        self.assertEqual(0, result["timing"]["xanderFallbackSessionStatus"])
        self.assertEqual(1, result["timing"]["xanderFallbackTimedOut"])
        self.assertLess(result["xanderFastMs"], 1200)

    def test_latest_metrics_skips_corrupt_jsonl_lines(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "metrics.jsonl"
            os.environ["OTOXAN_VOICE_METRICS_JSONL"] = str(path)
            path.write_text('{"ok": true, "n": 1}\nnot-json\n{"ok": true, "n": 2}\n')

            latest = voice_turn_server.latest_voice_turn_metrics()

        self.assertTrue(latest["ok"])
        self.assertEqual(3, latest["count"])
        self.assertEqual(1, latest["corruptLineCount"])
        self.assertEqual(2, latest["latest"]["n"])

    def test_recent_metrics_returns_bounded_newest_first_records(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "metrics.jsonl"
            os.environ["OTOXAN_VOICE_METRICS_JSONL"] = str(path)
            path.write_text(''.join(f'{{"n": {idx}}}\n' for idx in range(5)))

            recent = voice_turn_server.recent_voice_turn_metrics(limit=3)

        self.assertTrue(recent["ok"])
        self.assertEqual(5, recent["count"])
        self.assertEqual([4, 3, 2], [record["n"] for record in recent["records"]])

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
        self.assertEqual(96, body["max_tokens"])
        self.assertTrue(body["reasoning_split"])
        self.assertNotIn("test-key", str(body))

    def test_mobile_fast_default_provider_prefers_lowest_latency_configured_lane(self):
        route = voice_turn_server.RouteSummary("RB Meta", "TYPE_BLUETOOTH_SCO", "RB Meta", "TYPE_BLUETOOTH_SCO", True, "")
        fake_config = {
            "providers": {
                "api-z-ai": {
                    "base_url": "https://example.invalid/v1",
                    "api_key": "test-key",
                    "model": "fast-model",
                }
            }
        }
        response_body = {"choices": [{"message": {"content": "Latency status is green."}}]}

        class FakeResponse:
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False
            def read(self):
                return voice_turn_server.json.dumps(response_body).encode("utf-8")

        os.environ.pop("OTOXAN_MOBILE_FAST_PROVIDER", None)
        with mock.patch.object(voice_turn_server, "_load_xander_config", return_value=fake_config):
            with mock.patch.object(voice_turn_server.urllib.request, "urlopen", return_value=FakeResponse()) as urlopen:
                text = voice_turn_server._ask_xander_mobile_fast("test transcript", route)

        self.assertEqual("Latency status is green.", text)
        request = urlopen.call_args.args[0]
        body = voice_turn_server.json.loads(request.data.decode("utf-8"))
        self.assertEqual("fast-model", body["model"])

    def test_mobile_fast_shaper_allows_clear_spoken_reply_not_four_word_fragment(self):
        text = voice_turn_server._shape_mobile_spoken_response(
            "The backend is responding, but the slowest lane is speech recognition.",
            max_words=voice_turn_server.XANDER_FAST_MAX_WORDS,
        )

        self.assertEqual("The backend is responding, but the slowest lane is speech recognition.", text)

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

        self.assertEqual("I'm on the Ray-Ban route", text)
        command = run.call_args.args[0]
        prompt = command[command.index("-z") + 1]
        self.assertIn("Otoxan controller builder and fleet operator", prompt)
        self.assertIn("Mobile/Ray-Ban work is a controller voice-loop surface, not a separate identity", prompt)
        self.assertIn("direct, builder-first, operator-grade, concrete", prompt)
        self.assertIn("One short spoken answer, not a report", prompt)
        self.assertIn("Operator said", prompt)
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
        self.assertLessEqual(len(shaped), voice_turn_server.XANDER_SPOKEN_MAX_CHARS + 1)
        self.assertTrue(shaped.endswith("."))

        semicolon = "The loop is live; this slower diagnostic clause should not be spoken."
        self.assertEqual("The loop is live", voice_turn_server._shape_mobile_spoken_response(semicolon))

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
                "perceivedLatency": {
                    "ttfaMs": 900,
                    "breakdown": {"postCaptureDispatchMs": 17},
                },
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
        self.assertEqual(17, stored["payload"]["perceivedLatency"]["postCaptureAckDelayMs"])
        self.assertEqual(1234, stored["timingSummary"]["turnTotalMs"])
        self.assertEqual(456, stored["timingSummary"]["backendRoundTripMs"])
        self.assertEqual(900, stored["timingSummary"]["ttfaMs"])
        self.assertEqual(17, stored["timingSummary"]["postCaptureAckDelayMs"])
        self.assertEqual("otoxan-mobile-canonical-timing", stored["payload"]["timingContract"]["name"])
        self.assertEqual(1, stored["payload"]["timingContract"]["version"])
        self.assertEqual("turn_elapsed_ms_from_android_monotonic_start", stored["payload"]["timingContract"]["clock"])
        self.assertEqual(1500, stored["payload"]["timingContract"]["targets"]["ttfaMs"])
        self.assertEqual(250, stored["payload"]["timingContract"]["targets"]["postCaptureAckDelayMs"])
        self.assertEqual(8000, stored["payload"]["timingContract"]["targets"]["turnTotalMs"])
        self.assertEqual(4000, stored["payload"]["timingContract"]["targets"]["backendRoundTripMs"])
        self.assertEqual(12, stored["payload"]["verdict"]["transcriptLength"])

    def test_voice_turn_metrics_backfills_incomplete_timing_contract_without_losing_anchors(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "metrics.jsonl"
            os.environ["OTOXAN_VOICE_METRICS_JSONL"] = str(path)
            payload = {
                "type": "otoxan_mobile_voice_turn_metrics",
                "schemaVersion": 1,
                "timingContract": {
                    "anchors": {
                        "turnStartMs": 0,
                        "ttfaMs": "local ack or assistant playback start",
                    },
                    "targets": {"ttfaMs": 1500},
                },
                "turn": {"turnId": "turn-contract", "success": True, "stage": "complete"},
                "totals": {"turnTotalMs": 8010},
                "backend": {"roundTripMs": 3900},
                "perceivedLatency": {
                    "ttfaMs": 1440,
                    "localAckKind": "earcon_while_route_active",
                    "localAckStartMs": 1440,
                    "localAckTotalMs": 90,
                    "assistantPlaybackStartMs": 4700,
                    "backendResponseReadyMs": 4560,
                    "breakdown": {
                        "routeSelectMs": 40,
                        "captureReadMs": 1320,
                        "postCaptureDispatchMs": 80,
                        "backendWaitAfterReleaseMs": 330,
                    },
                },
            }

            voice_turn_server.handle_voice_turn_metrics(payload, remote_addr="phone")
            stored = voice_turn_server.latest_voice_turn_metrics()["latest"]

        contract = stored["payload"]["timingContract"]
        self.assertEqual(voice_turn_server.TIMING_CONTRACT_NAME, contract["name"])
        self.assertEqual(voice_turn_server.TIMING_CONTRACT_VERSION, contract["version"])
        self.assertEqual(voice_turn_server.TIMING_CONTRACT_CLOCK, contract["clock"])
        self.assertEqual("local ack or assistant playback start", contract["anchors"]["ttfaMs"])
        self.assertEqual(voice_turn_server.TIMING_CONTRACT_TARGETS, contract["targets"])
        self.assertEqual(80, stored["payload"]["perceivedLatency"]["postCaptureAckDelayMs"])
        self.assertEqual(1440, stored["timingSummary"]["ttfaMs"])
        self.assertEqual(80, stored["timingSummary"]["postCaptureAckDelayMs"])
        self.assertEqual(4560, stored["timingSummary"]["backendResponseReadyMs"])

    def test_recent_metrics_exposes_timing_summary_for_new_and_legacy_records(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "metrics.jsonl"
            os.environ["OTOXAN_VOICE_METRICS_JSONL"] = str(path)
            payload = {
                "type": "otoxan_mobile_voice_turn_metrics",
                "schemaVersion": 1,
                "turn": {"turnId": "turn-new", "success": True, "stage": "complete"},
                "totals": {"turnTotalMs": 8123},
                "backend": {"roundTripMs": 3456},
                "perceivedLatency": {
                    "ttfaMs": 789,
                    "localAckKind": "earcon",
                    "postCaptureAckDelayMs": 33,
                    "localAckStartMs": 640,
                    "localAckTotalMs": 120,
                    "assistantPlaybackStartMs": 4010,
                    "backendResponseReadyMs": 3560,
                    "breakdown": {
                        "routeSelectMs": 11,
                        "captureReadMs": 5002,
                        "postCaptureDispatchMs": 33,
                        "backendWaitAfterReleaseMs": 222,
                    },
                },
            }
            voice_turn_server.handle_voice_turn_metrics(payload, remote_addr="phone")
            with path.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps({
                    "recordId": "legacy-record",
                    "payload": {
                        "turn": {"turnId": "turn-legacy", "success": True, "stage": "complete"},
                        "totals": {"turnTotalMs": 9000},
                        "backend": {"roundTripMs": 4000},
                        "perceivedLatency": {
                            "ttfaMs": 1000,
                            "breakdown": {"postCaptureDispatchMs": 44},
                        },
                    },
                }) + "\n")

            recent = voice_turn_server.recent_voice_turn_metrics(limit=2)

        legacy, newest = recent["records"]
        self.assertEqual("turn-legacy", legacy["payload"]["turn"]["turnId"])
        self.assertEqual(9000, legacy["timingSummary"]["turnTotalMs"])
        self.assertEqual(4000, legacy["timingSummary"]["backendRoundTripMs"])
        self.assertEqual(1000, legacy["timingSummary"]["ttfaMs"])
        self.assertEqual(44, legacy["timingSummary"]["postCaptureAckDelayMs"])
        self.assertEqual("turn-new", newest["payload"]["turn"]["turnId"])
        self.assertEqual(8123, newest["timingSummary"]["turnTotalMs"])
        self.assertEqual(3456, newest["timingSummary"]["backendRoundTripMs"])
        self.assertEqual(789, newest["timingSummary"]["ttfaMs"])
        self.assertEqual(33, newest["timingSummary"]["postCaptureAckDelayMs"])
        self.assertEqual("earcon", newest["timingSummary"]["localAckKind"])
        self.assertEqual(640, newest["timingSummary"]["localAckStartMs"])
        self.assertEqual(120, newest["timingSummary"]["localAckTotalMs"])
        self.assertEqual(4010, newest["timingSummary"]["assistantPlaybackStartMs"])
        self.assertEqual(3560, newest["timingSummary"]["backendResponseReadyMs"])
        self.assertEqual(11, newest["timingSummary"]["routeSelectMs"])
        self.assertEqual(5002, newest["timingSummary"]["captureReadMs"])
        self.assertEqual(33, newest["timingSummary"]["postCaptureDispatchMs"])
        self.assertEqual(222, newest["timingSummary"]["backendWaitAfterReleaseMs"])


if __name__ == "__main__":
    unittest.main()
