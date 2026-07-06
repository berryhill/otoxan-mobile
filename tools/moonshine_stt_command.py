#!/usr/bin/env python3
"""Optional Moonshine STT command adapter for Otoxan Mobile.

This script is intentionally dependency-optional. It imports Moonshine packages only
when invoked, emits the JSON shape consumed by tools/voice_turn_server.py, and exits
non-zero on unavailable/empty/error cases so the voice-turn helper can fall back to
Hermes STT.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Callable


BackendAttempt = tuple[str, Callable[[], str]]


def _write_result(data: dict[str, Any], output_path: str | None) -> None:
    text = json.dumps(data, ensure_ascii=False)
    if output_path:
        Path(output_path).write_text(text, encoding="utf-8")
    print(text)


def _join_transcript(parts: Any) -> str:
    if isinstance(parts, str):
        return parts.strip()
    if isinstance(parts, (list, tuple)):
        return " ".join(str(part).strip() for part in parts if str(part).strip()).strip()
    return str(parts).strip() if parts is not None else ""


def _transcribe_with_moonshine_onnx(input_path: str, model: str) -> str:
    import moonshine_onnx as moonshine  # type: ignore[import-not-found]

    return _join_transcript(moonshine.transcribe(input_path, model))


def _transcribe_with_useful_moonshine(input_path: str, model: str) -> str:
    import moonshine  # type: ignore[import-not-found]

    return _join_transcript(moonshine.transcribe(input_path, model))


def _transcribe_with_moonshine_voice(input_path: str, language: str, model: str) -> str:
    from moonshine_voice import Transcriber, get_model_for_language, load_wav_file  # type: ignore[import-not-found]

    audio, sample_rate = load_wav_file(input_path)
    model_path, model_arch = get_model_for_language(language)
    transcriber = Transcriber(model_path=model_path, model_arch=model_arch)
    try:
        transcript = transcriber.transcribe_without_streaming(audio, sample_rate)
        lines = getattr(transcript, "lines", [])
        return " ".join(
            str(getattr(line, "text", "")).strip()
            for line in lines
            if str(getattr(line, "text", "")).strip()
        ).strip()
    finally:
        close = getattr(transcriber, "close", None)
        if callable(close):
            close()


def _backend_attempts(args: argparse.Namespace) -> list[BackendAttempt]:
    attempts: list[BackendAttempt] = []
    if args.backend in {"auto", "moonshine-onnx"}:
        attempts.append(("moonshine-onnx", lambda: _transcribe_with_moonshine_onnx(args.input, args.model)))
    if args.backend in {"auto", "moonshine"}:
        attempts.append(("moonshine", lambda: _transcribe_with_useful_moonshine(args.input, args.model)))
    if args.backend in {"auto", "moonshine-voice"}:
        attempts.append(("moonshine-voice", lambda: _transcribe_with_moonshine_voice(args.input, args.language, args.model)))
    return attempts


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Transcribe a WAV file with an optional local Moonshine backend.")
    parser.add_argument("--input", required=True, help="16 kHz mono PCM16 WAV input path")
    parser.add_argument("--output", help="optional JSON output path")
    parser.add_argument(
        "--backend",
        default="auto",
        choices=("auto", "moonshine-onnx", "moonshine", "moonshine-voice"),
        help="Moonshine backend to try; auto prefers ONNX, then useful-moonshine, then moonshine-voice",
    )
    parser.add_argument("--model", default="moonshine/tiny", help="model id for useful-moonshine or useful-moonshine-onnx")
    parser.add_argument("--language", default="en", help="language code for moonshine-voice")
    args = parser.parse_args(argv)

    if not Path(args.input).exists():
        _write_result({"success": False, "transcript": "", "error": f"input not found: {args.input}"}, args.output)
        return 2

    errors: list[str] = []
    for provider, attempt in _backend_attempts(args):
        try:
            transcript = attempt().strip()
        except Exception as exc:  # noqa: BLE001 - command boundary reports provider failure and falls back.
            errors.append(f"{provider}: {exc.__class__.__name__}: {exc}")
            continue
        if transcript:
            _write_result(
                {
                    "success": True,
                    "transcript": transcript,
                    "provider": provider,
                    "model": args.model,
                    "language": args.language,
                },
                args.output,
            )
            return 0
        errors.append(f"{provider}: empty transcript")

    _write_result(
        {
            "success": False,
            "transcript": "",
            "provider": args.backend,
            "model": args.model,
            "language": args.language,
            "error": "; ".join(errors) or "no Moonshine backend attempted",
        },
        args.output,
    )
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
