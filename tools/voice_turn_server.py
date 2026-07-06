#!/usr/bin/env python3
"""Repo-local Otoxan Mobile voice-turn server.

This is the fast-but-real backend seam for the Android/Ray-Ban voice loop:

1. validate Android PCM payload
2. in xander-session mode: start a short Hermes/Xander turn and return text
3. otherwise: return the deterministic local proof response/tone

The server does not persist raw audio. It stays repo-local and intentionally small
so the phone app can prove the end-to-end route before this graduates into the
Otoxan/Hermes runtime.
"""

from __future__ import annotations

import argparse
import base64
import json
import math
import os
import shutil
import subprocess
import tempfile
import time
import wave
import uuid
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Mapping

import yaml

SUPPORTED_FORMAT = "pcm_s16le_16khz_mono"
MAX_PCM_BYTES = 512_000
SAMPLE_RATE = 16_000
XANDER_PROVIDER = "xander-session"
MOBILE_FAST_PROVIDER = "mobile-fast"
XANDER_PROVIDER_ALIASES = {"xander", "xander-session", "hermes", "hermes-session"}
MOBILE_FAST_PROVIDER_ALIASES = {"mobile-fast", "fast", "xander-fast"}
SUPPORTED_PROVIDER_MODES = {"proof", *XANDER_PROVIDER_ALIASES, *MOBILE_FAST_PROVIDER_ALIASES}
HERMES_BIN_DEFAULT = "/home/silas/.local/bin/hermes"
XANDER_PROFILE_DEFAULT = "xander"
XANDER_PROMPT_TIMEOUT_SECONDS = 25
XANDER_FAST_TIMEOUT_SECONDS = 12
XANDER_MOBILE_MAX_WORDS = 25
XANDER_FAST_MAX_WORDS = 16
XANDER_MOBILE_VOICE_CONTRACT = """You are Xander on Otoxan Mobile.

Identity:
- You are the Otoxan controller operator speaking through Matt's Ray-Ban Meta route.
- You are not a generic assistant, chatbot, customer-support voice, or provider demo.

Voice:
- Direct, architectural, builder-first, evidence-driven.
- Use first person as Xander.
- Short enough for glasses audio: one spoken sentence, 25 words max.
- No filler, no apologies, no 'happy to help', no 'as an AI'.
- No provider/tool/API talk unless Matt explicitly asks.

Task:
- Answer Matt's actual transcript.
- If the transcript is unclear, say what is unclear and what to try next.
- Prefer concrete operator status over motivational language.
""".strip()
HERMES_AGENT_HOME_DEFAULT = "/home/silas/.hermes/hermes-agent"
HERMES_PYTHON_DEFAULT = "/home/silas/.hermes/hermes-agent/venv/bin/python"
XANDER_HERMES_HOME_DEFAULT = "/home/silas/.hermes/profiles/xander"
METRICS_JSONL_DEFAULT = str(Path(__file__).resolve().parent / "data" / "voice_turn_metrics.jsonl")


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
class SttResult:
    transcript: str
    status: str
    latency_ms: int | None


@dataclass(frozen=True)
class TranscriptResult:
    transcript: str
    source: str
    stt_status: str
    stt_latency_ms: int | None


@dataclass(frozen=True)
class AssistantTurn:
    transcript: str
    assistant_text: str
    tts_pcm: bytes
    provider: str
    transcript_source: str
    stt_status: str
    stt_latency_ms: int | None
    timing: dict[str, int | None]


def handle_voice_turn(payload: Mapping[str, Any]) -> dict[str, Any]:
    started = time.monotonic()
    timing: dict[str, int | None] = {}
    if not isinstance(payload, Mapping):
        raise VoiceTurnError("JSON object payload required", 400)

    audio_format = str(payload.get("format", "")).strip()
    if audio_format != SUPPORTED_FORMAT:
        raise VoiceTurnError(f"format must be {SUPPORTED_FORMAT}", 400)

    step_started = time.monotonic()
    pcm = _decode_pcm(payload.get("pcm16Mono16kBase64"))
    timing["decodePcmMs"] = _elapsed_ms(step_started)
    step_started = time.monotonic()
    stats = _audio_stats(pcm)
    timing["audioStatsMs"] = _elapsed_ms(step_started)
    step_started = time.monotonic()
    route = _parse_route(payload.get("routeEvidence"))
    timing["routeParseMs"] = _elapsed_ms(step_started)
    step_started = time.monotonic()
    turn = _run_assistant_turn(pcm, route)
    timing["assistantTurnMs"] = _elapsed_ms(step_started)
    timing.update(turn.timing)
    timing["backendTotalMs"] = _elapsed_ms(started)

    response = {
        "ok": True,
        "transcript": turn.transcript,
        "assistantText": turn.assistant_text,
        "ttsPcm16Mono16kBase64": base64.b64encode(turn.tts_pcm).decode("ascii"),
        "audioFormat": SUPPORTED_FORMAT,
        "bytesReceived": len(pcm),
        "audioStats": stats,
        "provider": turn.provider,
        "transcriptSource": turn.transcript_source,
        "sttStatus": turn.stt_status,
        "sttLatencyMs": turn.stt_latency_ms,
        "pass1Status": _pass1_status(turn, stats),
        "pass1Ready": _pass1_ready(turn),
        "routeEvidence": {
            "inputName": route.input_name,
            "inputType": route.input_type,
            "outputName": route.output_name,
            "outputType": route.output_type,
            "wearableActive": route.wearable_active,
            "message": route.message,
        },
        "timing": timing,
        "backendTotalMs": timing.get("backendTotalMs"),
        "decodePcmMs": timing.get("decodePcmMs"),
        "audioStatsMs": timing.get("audioStatsMs"),
        "transcriptTotalMs": timing.get("transcriptTotalMs"),
        "xanderSessionMs": timing.get("xanderSessionMs"),
        "responseBuildMs": 0,
    }
    response["responseBuildMs"] = _elapsed_ms(started) - int(response.get("backendTotalMs") or 0)
    response["timing"]["responseBuildMs"] = response["responseBuildMs"]
    return response


def _pass1_ready(turn: AssistantTurn) -> bool:
    return turn.provider in {XANDER_PROVIDER, MOBILE_FAST_PROVIDER} and turn.transcript_source == "hermes-stt" and turn.stt_status == "success"


def _pass1_status(turn: AssistantTurn, stats: Mapping[str, Any]) -> str:
    if _pass1_ready(turn):
        return "real-speech-proven"
    if turn.provider == "proof":
        return "proof-mode-not-real-speech"
    if stats.get("peak", 0) < 128:
        return "capture-too-quiet"
    if turn.transcript_source == "route-evidence-fallback":
        return f"stt-{turn.stt_status or 'not-proven'}"
    if turn.transcript_source == "debug":
        return "debug-transcript-not-real-speech"
    return "not-proven"


def _run_assistant_turn(pcm: bytes, route: RouteSummary) -> AssistantTurn:
    mode = os.environ.get("OTOXAN_VOICE_PROVIDER", XANDER_PROVIDER).strip().lower()
    if mode not in SUPPORTED_PROVIDER_MODES:
        accepted = ", ".join(sorted(SUPPORTED_PROVIDER_MODES))
        raise VoiceTurnError(f"OTOXAN_VOICE_PROVIDER must be one of: {accepted}", 500)

    if mode == "proof":
        return _proof_turn(pcm, route, provider="proof")

    try:
        if mode in MOBILE_FAST_PROVIDER_ALIASES:
            return _xander_mobile_fast_turn(pcm, route)
        return _xander_session_turn(pcm, route)
    except Exception as exc:  # noqa: BLE001 - strict mode should fail loudly.
        provider_name = MOBILE_FAST_PROVIDER if mode in MOBILE_FAST_PROVIDER_ALIASES else "Xander session"
        raise VoiceTurnError(f"{provider_name} provider failed: {exc}", 502) from exc


def _xander_session_turn(pcm: bytes, route: RouteSummary) -> AssistantTurn:
    timing: dict[str, int | None] = {}
    transcript_started = time.monotonic()
    transcript = _xander_transcript(pcm, route)
    timing["transcriptTotalMs"] = _elapsed_ms(transcript_started)
    timing["sttLatencyMs"] = transcript.stt_latency_ms
    if _is_stt_fallback(transcript.transcript):
        timing["xanderSessionMs"] = None
        return AssistantTurn(
            transcript=transcript.transcript,
            assistant_text=(
                f"I got audio from {route.input_name}, but couldn't decode words. "
                "Try speaking again a little closer."
            ),
            tts_pcm=b"",
            provider=XANDER_PROVIDER,
            transcript_source=transcript.source,
            stt_status=transcript.stt_status,
            stt_latency_ms=transcript.stt_latency_ms,
            timing=timing,
        )
    session_started = time.monotonic()
    assistant_text = _ask_xander_session(transcript.transcript, route)
    timing["xanderSessionMs"] = _elapsed_ms(session_started)
    return AssistantTurn(
        transcript=transcript.transcript,
        assistant_text=assistant_text,
        tts_pcm=b"",
        provider=XANDER_PROVIDER,
        transcript_source=transcript.source,
        stt_status=transcript.stt_status,
        stt_latency_ms=transcript.stt_latency_ms,
        timing=timing,
    )


def _xander_mobile_fast_turn(pcm: bytes, route: RouteSummary) -> AssistantTurn:
    timing: dict[str, int | None] = {}
    transcript_started = time.monotonic()
    transcript = _xander_transcript(pcm, route)
    timing["transcriptTotalMs"] = _elapsed_ms(transcript_started)
    timing["sttLatencyMs"] = transcript.stt_latency_ms
    if _is_stt_fallback(transcript.transcript):
        timing["xanderSessionMs"] = None
        timing["xanderFastMs"] = None
        return AssistantTurn(
            transcript=transcript.transcript,
            assistant_text=(
                f"I got audio from {route.input_name}, but couldn't decode words. "
                "Try speaking again a little closer."
            ),
            tts_pcm=b"",
            provider=MOBILE_FAST_PROVIDER,
            transcript_source=transcript.source,
            stt_status=transcript.stt_status,
            stt_latency_ms=transcript.stt_latency_ms,
            timing=timing,
        )
    fast_started = time.monotonic()
    try:
        assistant_text = _ask_xander_mobile_fast(transcript.transcript, route)
        timing["xanderFastStatus"] = 1
    except Exception as exc:  # noqa: BLE001 - mobile voice must return telemetry, not hard-fail.
        assistant_text = f"Fast lane failed after STT: {str(exc)[:90]}."
        timing["xanderFastStatus"] = 0
    fast_ms = _elapsed_ms(fast_started)
    # Keep xanderSessionMs populated so existing Android telemetry charts compare old vs fast lane.
    timing["xanderSessionMs"] = fast_ms
    timing["xanderFastMs"] = fast_ms
    return AssistantTurn(
        transcript=transcript.transcript,
        assistant_text=assistant_text,
        tts_pcm=b"",
        provider=MOBILE_FAST_PROVIDER,
        transcript_source=transcript.source,
        stt_status=transcript.stt_status,
        stt_latency_ms=transcript.stt_latency_ms,
        timing=timing,
    )


def _is_stt_fallback(transcript: str) -> bool:
    return "Hermes STT lane did not return a transcript for this turn." in transcript


def _xander_transcript(pcm: bytes, route: RouteSummary) -> TranscriptResult:
    debug_transcript = os.environ.get("OTOXAN_DEBUG_TRANSCRIPT", "").strip()
    if debug_transcript:
        return TranscriptResult(
            transcript=_clean(debug_transcript, "Voice turn received."),
            source="debug",
            stt_status="not-run",
            stt_latency_ms=None,
        )

    stt = _transcribe_with_hermes_stt(pcm)
    if stt.transcript:
        return TranscriptResult(
            transcript=stt.transcript,
            source="hermes-stt",
            stt_status=stt.status,
            stt_latency_ms=stt.latency_ms,
        )

    return TranscriptResult(
        transcript=(
            f"Voice audio received from {route.input_name} ({route.input_type}); "
            f"{len(pcm)} bytes of PCM reached the Xander session adapter. "
            "Hermes STT lane did not return a transcript for this turn."
        ),
        source="route-evidence-fallback",
        stt_status=stt.status,
        stt_latency_ms=stt.latency_ms,
    )


def _transcribe_with_hermes_stt(pcm: bytes) -> SttResult:
    hermes_agent_home = Path(os.environ.get("OTOXAN_HERMES_AGENT_HOME", HERMES_AGENT_HOME_DEFAULT))
    hermes_home = os.environ.get("HERMES_HOME") or os.environ.get("OTOXAN_XANDER_HERMES_HOME", XANDER_HERMES_HOME_DEFAULT)
    hermes_python = os.environ.get("OTOXAN_HERMES_PYTHON", HERMES_PYTHON_DEFAULT)
    with tempfile.NamedTemporaryFile(suffix=".wav") as wav_file:
        _write_pcm16_wav(wav_file.name, pcm)
        script = """
import json
import os
import sys
sys.path.insert(0, os.environ["OTOXAN_HERMES_AGENT_HOME"])
from hermes_cli.env_loader import load_hermes_dotenv
from tools.transcription_tools import transcribe_audio
load_hermes_dotenv(
    hermes_home=os.environ["HERMES_HOME"],
    project_env=os.path.join(os.environ["OTOXAN_HERMES_AGENT_HOME"], ".env"),
)
print(json.dumps(transcribe_audio(sys.argv[1])))
"""
        env = os.environ.copy()
        env.update(
            {
                "HERMES_HOME": hermes_home,
                "HERMES_PROFILE": os.environ.get("OTOXAN_XANDER_PROFILE", XANDER_PROFILE_DEFAULT),
                "OTOXAN_HERMES_AGENT_HOME": str(hermes_agent_home),
                "PYTHONPATH": str(hermes_agent_home),
            }
        )
        started = time.monotonic()
        try:
            result = subprocess.run(
                [hermes_python, "-c", script, wav_file.name],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=float(os.environ.get("OTOXAN_STT_TIMEOUT_SECONDS", "45")),
                check=False,
                env=env,
            )
        except FileNotFoundError:
            return SttResult("", "file-not-found", _elapsed_ms(started))
        except subprocess.TimeoutExpired:
            return SttResult("", "timeout", _elapsed_ms(started))
    latency_ms = _elapsed_ms(started)
    if result.returncode != 0:
        return SttResult("", "subprocess-error", latency_ms)
    lines = [line for line in result.stdout.splitlines() if line.strip()]
    if not lines:
        return SttResult("", "empty", latency_ms)
    try:
        data = json.loads(lines[-1])
    except json.JSONDecodeError:
        return SttResult("", "json-error", latency_ms)
    if not data.get("success"):
        return SttResult("", "stt-failed", latency_ms)
    transcript = _clean(data.get("transcript"), "")
    return SttResult(transcript, "success" if transcript else "empty", latency_ms)


def _elapsed_ms(started: float) -> int:
    return max(0, int((time.monotonic() - started) * 1000))


def _write_pcm16_wav(path: str, pcm: bytes) -> None:
    with wave.open(path, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(pcm)


def _ask_xander_session(transcript: str, route: RouteSummary) -> str:
    hermes_bin = os.environ.get("OTOXAN_HERMES_BIN", "").strip() or shutil.which("hermes") or HERMES_BIN_DEFAULT
    profile = os.environ.get("OTOXAN_XANDER_PROFILE", XANDER_PROFILE_DEFAULT).strip() or XANDER_PROFILE_DEFAULT
    timeout_raw = os.environ.get("OTOXAN_XANDER_TIMEOUT_SECONDS", str(XANDER_PROMPT_TIMEOUT_SECONDS)).strip()
    try:
        timeout = max(5.0, min(float(timeout_raw), 60.0))
    except ValueError:
        timeout = float(XANDER_PROMPT_TIMEOUT_SECONDS)

    prompt = _build_xander_mobile_prompt(transcript, route)
    command = [
        hermes_bin,
        "--profile",
        profile,
        "-z",
        prompt,
        "--ignore-rules",
        "--toolsets",
        "vision",
    ]
    try:
        result = subprocess.run(
            command,
            cwd=os.environ.get("OTOXAN_HERMES_CWD", "/home/silas/.hermes"),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError as exc:
        raise VoiceTurnError(f"Hermes executable not found: {hermes_bin}", 502) from exc
    except subprocess.TimeoutExpired as exc:
        raise VoiceTurnError(f"Hermes/Xander session timed out after {timeout:.0f}s", 504) from exc

    if result.returncode != 0:
        raise VoiceTurnError(f"Hermes/Xander session exited with status {result.returncode}", 502)
    text = _clean(result.stdout, "").strip()
    if not text:
        raise VoiceTurnError("Hermes/Xander session returned no text", 502)
    return _shape_mobile_spoken_response(text)


def _ask_xander_mobile_fast(transcript: str, route: RouteSummary) -> str:
    # MiniMax is fastest enough for this lane, but it can emit long <think> blocks;
    # give it enough budget for the closing tag and guard against reasoning-only output.
    provider_name = os.environ.get("OTOXAN_MOBILE_FAST_PROVIDER", "minimax").strip()
    config = _load_xander_config()
    provider = _configured_provider(config, provider_name)
    base_url = str(provider.get("base_url", "")).rstrip("/")
    api_key = str(provider.get("api_key", "")).strip()
    model = os.environ.get("OTOXAN_MOBILE_FAST_MODEL", "").strip() or str(provider.get("model", "")).strip()
    if not base_url or not api_key or not model:
        raise VoiceTurnError(f"mobile-fast provider {provider_name!r} is missing base_url/api_key/model", 502)

    timeout_raw = os.environ.get("OTOXAN_MOBILE_FAST_TIMEOUT_SECONDS", str(XANDER_FAST_TIMEOUT_SECONDS)).strip()
    try:
        timeout = max(3.0, min(float(timeout_raw), 30.0))
    except ValueError:
        timeout = float(XANDER_FAST_TIMEOUT_SECONDS)

    body = {
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are Xander, Otoxan controller operator, speaking through Ray-Ban Meta glasses. "
                    f"Answer in one direct spoken sentence, max {XANDER_FAST_MAX_WORDS} words. "
                    "Builder-first, concrete, no filler, no apologies, no reasoning, no XML tags."
                ),
            },
            {
                "role": "user",
                "content": (
                    "Route evidence: "
                    f"input={route.input_name} ({route.input_type}); output={route.output_name} ({route.output_type}).\n"
                    f"Matt said: {transcript}\n"
                    "Return only the spoken sentence."
                ),
            },
        ],
        "max_tokens": int(os.environ.get("OTOXAN_MOBILE_FAST_MAX_TOKENS", "1024")),
        "temperature": _mobile_fast_temperature(provider_name),
    }
    request = urllib.request.Request(
        base_url + "/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            data = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:240]
        raise VoiceTurnError(f"mobile-fast provider HTTP {exc.code}: {detail}", 502) from exc
    except urllib.error.URLError as exc:
        raise VoiceTurnError(f"mobile-fast provider connection failed: {exc.reason}", 502) from exc
    except TimeoutError as exc:
        raise VoiceTurnError(f"mobile-fast provider timed out after {timeout:.0f}s", 504) from exc

    try:
        text = data["choices"][0]["message"].get("content", "")
    except (KeyError, IndexError, TypeError) as exc:
        raise VoiceTurnError("mobile-fast provider returned an unexpected response shape", 502) from exc
    text = _strip_reasoning_markup(str(text))
    if not text.strip():
        return "Fast lane got your words, but the model returned no spoken answer."
    return _shape_mobile_spoken_response(text, max_words=XANDER_FAST_MAX_WORDS)


def _load_xander_config() -> Mapping[str, Any]:
    config_path = Path(os.environ.get("OTOXAN_XANDER_CONFIG", "/home/silas/.hermes/profiles/xander/config.yaml"))
    try:
        return yaml.safe_load(config_path.read_text()) or {}
    except FileNotFoundError as exc:
        raise VoiceTurnError(f"Xander config not found at {config_path}", 502) from exc


def _configured_provider(config: Mapping[str, Any], provider_name: str) -> Mapping[str, Any]:
    providers = config.get("providers")
    if not isinstance(providers, Mapping):
        raise VoiceTurnError("Xander config has no providers map", 502)
    provider = providers.get(provider_name)
    if not isinstance(provider, Mapping):
        raise VoiceTurnError(f"Xander config has no provider named {provider_name!r}", 502)
    return provider


def _mobile_fast_temperature(provider_name: str) -> float:
    override = os.environ.get("OTOXAN_MOBILE_FAST_TEMPERATURE", "").strip()
    if override:
        return float(override)
    return 1.0 if provider_name.strip().lower() == "kimi" else 0.2


def _strip_reasoning_markup(text: str) -> str:
    cleaned = text.strip()
    lower = cleaned.lower()
    if "</think>" in lower:
        cleaned = cleaned[lower.rfind("</think>") + len("</think>"):].strip()
    if cleaned.lower().startswith("<think>"):
        return ""
    return cleaned


def _build_xander_mobile_prompt(transcript: str, route: RouteSummary) -> str:
    return (
        f"{XANDER_MOBILE_VOICE_CONTRACT}\n\n"
        "Route evidence:\n"
        f"- input: {route.input_name} ({route.input_type})\n"
        f"- output: {route.output_name} ({route.output_type})\n"
        f"- wearableActive: {route.wearable_active}\n\n"
        "Matt said:\n"
        f"{transcript}\n\n"
        "Return only the spoken sentence."
    )


def _shape_mobile_spoken_response(text: str, max_words: int = XANDER_MOBILE_MAX_WORDS) -> str:
    cleaned = _clean(text, "").replace("\r", "\n").strip().strip('"“”')
    if not cleaned:
        return cleaned
    for prefix in ("Sure,", "Certainly,", "Of course,", "As an AI,", "As Xander,"):
        if cleaned.lower().startswith(prefix.lower()):
            cleaned = cleaned[len(prefix):].strip()
            break
    first_line = next((line.strip(" -\t") for line in cleaned.splitlines() if line.strip()), cleaned)
    sentence = _first_sentence(first_line)
    words = sentence.split()
    if len(words) > max_words:
        sentence = " ".join(words[:max_words]).rstrip(",;:-") + "."
    return sentence


def _first_sentence(text: str) -> str:
    for marker in (". ", "! ", "? "):
        index = text.find(marker)
        if index >= 0:
            return text[: index + 1].strip()
    return text.strip()


def _proof_turn(pcm: bytes, route: RouteSummary, provider: str) -> AssistantTurn:
    transcript = f"Received {len(pcm)} bytes from {route.input_name} ({route.input_type})."
    assistant_text = f"Xander heard you. The Ray-Ban voice route is live on {route.output_name}."
    tone_started = time.monotonic()
    tts_pcm = _proof_tone_pcm()
    return AssistantTurn(
        transcript=transcript,
        assistant_text=assistant_text,
        tts_pcm=tts_pcm,
        provider=provider,
        transcript_source="proof",
        stt_status="not-run",
        stt_latency_ms=None,
        timing={
            "transcriptTotalMs": 0,
            "sttLatencyMs": None,
            "xanderSessionMs": None,
            "proofToneBuildMs": _elapsed_ms(tone_started),
        },
    )


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


def _audio_stats(pcm: bytes) -> dict[str, Any]:
    samples = []
    for index in range(0, len(pcm) - 1, 2):
        low = pcm[index] & 0xFF
        high = pcm[index + 1]
        sample = int.from_bytes(bytes((low, high)), byteorder="little", signed=True)
        samples.append(sample)
    peak = max((abs(sample) for sample in samples), default=0)
    rms = math.sqrt(sum(sample * sample for sample in samples) / len(samples)) if samples else 0.0
    duration_ms = int(round((len(samples) / SAMPLE_RATE) * 1000)) if samples else 0
    return {
        "bytes": len(pcm),
        "samples": len(samples),
        "durationMs": duration_ms,
        "peak": peak,
        "rms": round(rms, 2),
    }


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

def handle_voice_turn_metrics(payload: Mapping[str, Any], remote_addr: str = "") -> dict[str, Any]:
    if not isinstance(payload, Mapping):
        raise VoiceTurnError("JSON object payload required", 400)
    packet_type = str(payload.get("type", "")).strip()
    if packet_type != "otoxan_mobile_voice_turn_metrics":
        raise VoiceTurnError("type must be otoxan_mobile_voice_turn_metrics", 400)
    schema_version = payload.get("schemaVersion")
    if schema_version != 1:
        raise VoiceTurnError("schemaVersion must be 1", 400)
    turn = payload.get("turn")
    if not isinstance(turn, Mapping):
        raise VoiceTurnError("turn must be an object", 400)
    turn_id = _clean(turn.get("turnId"), "")
    if not turn_id:
        raise VoiceTurnError("turn.turnId is required", 400)

    record = {
        "recordId": str(uuid.uuid4()),
        "receivedAtMs": int(time.time() * 1000),
        "remoteAddr": remote_addr,
        "payload": _sanitize_metrics_payload(payload),
    }
    path = Path(os.environ.get("OTOXAN_VOICE_METRICS_JSONL", METRICS_JSONL_DEFAULT))
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(record, sort_keys=True) + "\n")
    print(
        "voice-turn-metrics ok "
        f"turnId={turn_id} "
        f"success={turn.get('success')} "
        f"stage={turn.get('stage')} "
        f"totalMs={_nested_get(payload, 'totals', 'turnTotalMs')} "
        f"backendMs={_nested_get(payload, 'backend', 'roundTripMs')} "
        f"playback={_nested_get(payload, 'playback', 'kind')} "
        f"path={path}",
        flush=True,
    )
    return {"ok": True, "recordId": record["recordId"], "metricsPath": str(path)}


def latest_voice_turn_metrics() -> dict[str, Any]:
    path = Path(os.environ.get("OTOXAN_VOICE_METRICS_JSONL", METRICS_JSONL_DEFAULT))
    if not path.exists():
        return {"ok": True, "count": 0, "latest": None, "metricsPath": str(path)}
    latest = None
    count = 0
    with path.open("r", encoding="utf-8") as fh:
        for line in fh:
            if line.strip():
                count += 1
                latest = json.loads(line)
    return {"ok": True, "count": count, "latest": latest, "metricsPath": str(path)}


def _sanitize_metrics_payload(payload: Mapping[str, Any]) -> dict[str, Any]:
    # Do not persist raw spoken transcript/assistant text even if a future client accidentally sends it.
    data = json.loads(json.dumps(payload))
    for forbidden in ("transcript", "assistantText", "rawTranscript", "rawAssistantText"):
        data.pop(forbidden, None)
    verdict = data.get("verdict")
    if isinstance(verdict, dict):
        for forbidden in ("transcript", "assistantText", "rawTranscript", "rawAssistantText"):
            verdict.pop(forbidden, None)
    return data


def _nested_get(value: Mapping[str, Any], *keys: str) -> Any:
    current: Any = value
    for key in keys:
        if not isinstance(current, Mapping):
            return None
        current = current.get(key)
    return current


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - stdlib handler API.
        if self.path == "/healthz":
            self._send_json(200, {"ok": True, "service": "otoxan-mobile-voice-turn"})
            return
        if self.path == "/voice-turn-metrics/latest":
            self._send_json(200, latest_voice_turn_metrics())
            return
        self._send_json(404, {"ok": False, "error": "not found"})

    def do_POST(self) -> None:  # noqa: N802 - stdlib handler API.
        if self.path == "/voice-turn-metrics":
            self._handle_metrics_post()
            return
        if self.path != "/voice-turn":
            self._send_json(404, {"ok": False, "error": "not found"})
            return

        try:
            request_started = time.monotonic()
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length).decode("utf-8") if length else "{}"
            request_read_ms = _elapsed_ms(request_started)
            parse_started = time.monotonic()
            payload = json.loads(body)
            json_parse_ms = _elapsed_ms(parse_started)
            response = handle_voice_turn(payload)
            response.setdefault("timing", {})["httpRequestReadMs"] = request_read_ms
            response.setdefault("timing", {})["httpJsonParseMs"] = json_parse_ms
            response["httpRequestReadMs"] = request_read_ms
            response["httpJsonParseMs"] = json_parse_ms
            print(
                "voice-turn ok "
                f"provider={response.get('provider')} "
                f"bytes={response.get('bytesReceived')} "
                f"durationMs={response.get('audioStats', {}).get('durationMs')} "
                f"peak={response.get('audioStats', {}).get('peak')} "
                f"rms={response.get('audioStats', {}).get('rms')} "
                f"sttStatus={response.get('sttStatus')} "
                f"sttLatencyMs={response.get('sttLatencyMs')} "
                f"backendTotalMs={response.get('backendTotalMs')} "
                f"xanderSessionMs={response.get('xanderSessionMs')} "
                f"transcriptSource={response.get('transcriptSource')} "
                f"pass1Status={response.get('pass1Status')} "
                f"input={response.get('routeEvidence', {}).get('inputName')} "
                f"type={response.get('routeEvidence', {}).get('inputType')}",
                flush=True,
            )
            self._send_json(200, response)
        except json.JSONDecodeError:
            print("voice-turn error status=400 reason=invalid-json", flush=True)
            self._send_json(400, {"ok": False, "error": "Invalid JSON"})
        except VoiceTurnError as exc:
            print(f"voice-turn error status={exc.status} reason={str(exc)[:160]}", flush=True)
            self._send_json(exc.status, {"ok": False, "error": str(exc)})
        except Exception as exc:  # noqa: BLE001 - dinky dev server; return concise failure.
            print(f"voice-turn error status=500 reason={str(exc)[:160]}", flush=True)
            self._send_json(500, {"ok": False, "error": str(exc)})

    def _handle_metrics_post(self) -> None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length).decode("utf-8") if length else "{}"
            payload = json.loads(body)
            response = handle_voice_turn_metrics(payload, remote_addr=self.client_address[0])
            self._send_json(200, response)
        except json.JSONDecodeError:
            print("voice-turn-metrics error status=400 reason=invalid-json", flush=True)
            self._send_json(400, {"ok": False, "error": "Invalid JSON"})
        except VoiceTurnError as exc:
            print(f"voice-turn-metrics error status={exc.status} reason={str(exc)[:160]}", flush=True)
            self._send_json(exc.status, {"ok": False, "error": str(exc)})
        except Exception as exc:  # noqa: BLE001 - dinky dev server; return concise failure.
            print(f"voice-turn-metrics error status=500 reason={str(exc)[:160]}", flush=True)
            self._send_json(500, {"ok": False, "error": str(exc)})

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"{self.address_string()} - {fmt % args}")

    def _send_json(self, status: int, payload: Mapping[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            print(f"voice-turn client disconnected before status={status} response completed", flush=True)


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
