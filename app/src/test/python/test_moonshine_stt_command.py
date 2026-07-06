import importlib.util
import json
import sys
import tempfile
import types
from pathlib import Path
from unittest import mock
import unittest


MODULE_PATH = Path(__file__).resolve().parents[4] / "tools" / "moonshine_stt_command.py"
spec = importlib.util.spec_from_file_location("moonshine_stt_command", MODULE_PATH)
moonshine_stt_command = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules["moonshine_stt_command"] = moonshine_stt_command
spec.loader.exec_module(moonshine_stt_command)


class MoonshineSttCommandTest(unittest.TestCase):
    def _wav(self, tmpdir: str) -> str:
        path = Path(tmpdir) / "input.wav"
        path.write_bytes(b"RIFFfake-wav")
        return str(path)

    def test_onnx_backend_writes_success_json_to_stdout_and_output(self):
        fake = types.SimpleNamespace(transcribe=lambda path, model: ["hello", "xander"])
        with tempfile.TemporaryDirectory() as tmpdir:
            input_path = self._wav(tmpdir)
            output_path = str(Path(tmpdir) / "out.json")
            with mock.patch.dict(sys.modules, {"moonshine_onnx": fake}):
                with mock.patch("sys.stdout", new_callable=lambda: __import__("io").StringIO()) as stdout:
                    code = moonshine_stt_command.main([
                        "--backend", "moonshine-onnx",
                        "--input", input_path,
                        "--output", output_path,
                    ])
            stdout_data = json.loads(stdout.getvalue())
            file_data = json.loads(Path(output_path).read_text())

        self.assertEqual(0, code)
        self.assertEqual("hello xander", stdout_data["transcript"])
        self.assertEqual("moonshine-onnx", stdout_data["provider"])
        self.assertEqual(stdout_data, file_data)

    def test_auto_backend_falls_through_to_useful_moonshine_when_onnx_missing(self):
        fake_moonshine = types.SimpleNamespace(transcribe=lambda path, model: ["fallback package words"])
        with tempfile.TemporaryDirectory() as tmpdir:
            input_path = self._wav(tmpdir)
            with mock.patch.dict(sys.modules, {"moonshine_onnx": None, "moonshine": fake_moonshine}):
                with mock.patch("sys.stdout", new_callable=lambda: __import__("io").StringIO()) as stdout:
                    code = moonshine_stt_command.main(["--backend", "auto", "--input", input_path])
            data = json.loads(stdout.getvalue())

        self.assertEqual(0, code)
        self.assertEqual("fallback package words", data["transcript"])
        self.assertEqual("moonshine", data["provider"])

    def test_missing_backend_returns_nonzero_json_for_voice_turn_fallback(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            input_path = self._wav(tmpdir)
            with mock.patch.dict(sys.modules, {"moonshine_onnx": None}):
                with mock.patch("sys.stdout", new_callable=lambda: __import__("io").StringIO()) as stdout:
                    code = moonshine_stt_command.main([
                        "--backend", "moonshine-onnx",
                        "--input", input_path,
                    ])
            data = json.loads(stdout.getvalue())

        self.assertEqual(2, code)
        self.assertFalse(data["success"])
        self.assertEqual("", data["transcript"])
        self.assertIn("moonshine-onnx", data["error"])

    def test_missing_input_returns_nonzero_json(self):
        with mock.patch("sys.stdout", new_callable=lambda: __import__("io").StringIO()) as stdout:
            code = moonshine_stt_command.main(["--input", "/definitely/not/here.wav"])
        data = json.loads(stdout.getvalue())

        self.assertEqual(2, code)
        self.assertFalse(data["success"])
        self.assertIn("input not found", data["error"])


if __name__ == "__main__":
    unittest.main()
