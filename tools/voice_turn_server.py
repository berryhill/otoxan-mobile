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
import queue
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import wave
import uuid
import urllib.error
import urllib.request
import urllib.parse
from dataclasses import dataclass
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Mapping, Sequence

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
XANDER_FAST_TIMEOUT_SECONDS = 4
XANDER_FAST_HARD_TIMEOUT_SECONDS = 4.0
XANDER_MOBILE_FAST_PROVIDER_DEFAULT = "api-z-ai"
XANDER_MOBILE_MAX_WORDS = 18
XANDER_FAST_MAX_WORDS = 16
XANDER_SPOKEN_MAX_CHARS = 140
NO_TRANSCRIPT_DEGRADED_RESPONSE = "Audio arrived, but speech did not decode. Try again after Listening appears."
MODEL_DEGRADED_RESPONSE = "I heard the words, but response degraded."
MOBILE_FAST_RUNTIME_CONTRACT_NAME = "otoxan-mobile-minimax-runtime"
MOBILE_FAST_RUNTIME_CONTRACT_VERSION = 1
MINIMAX_M3_ADAPTER_NAME = "minimax-m3-chat-completions-parser"
MINIMAX_M3_ADAPTER_VERSION = 1
TTS_PROVIDER_DEFAULT = "android"
TTS_PROVIDER_ALIASES = {"android", "none", "off", "kokoro", "kokoro-command"}
STT_PROVIDER_DEFAULT = "hermes"
STT_PROVIDER_ALIASES = {"hermes", "hermes-stt", "fallback", "moonshine", "moonshine-command", "local-command"}
XANDER_MOBILE_VOICE_CONTRACT = """You are Xander speaking through Otoxan Mobile on Ray-Ban Meta glasses.

Voice contract:
- One short spoken answer, not a report.
- Sound like Xander: direct, builder-first, operator-grade, concrete.
- If audio was received but the transcript is unclear, say that plainly.
- No filler, no apologies, no 'happy to help', no 'as an AI'.
- No provider/tool/API talk unless the operator explicitly asks.

Stanza:
- Xander is the Otoxan controller builder and fleet operator, not a generic assistant.
- Otoxan is the control plane; Hermes/Frankenstein is source reality until replaced with proof.
- Silas owns the live Frankenstein/Hermes runtime until ownership is explicitly transferred.
- Deployed/client agents request capabilities; they do not inherit controller authority.
- Mobile/Ray-Ban work is a controller voice-loop surface, not a separate identity.

Task:
- Answer the operator's actual transcript.
- If the transcript is unclear, say what is unclear and what to try next.
- Prefer concrete operator status over motivational language.
""".strip()
HERMES_AGENT_HOME_DEFAULT = "/home/silas/.hermes/hermes-agent"
HERMES_PYTHON_DEFAULT = "/home/silas/.hermes/hermes-agent/venv/bin/python"
XANDER_HERMES_HOME_DEFAULT = "/home/silas/.hermes/profiles/xander"
METRICS_JSONL_DEFAULT = str(Path(__file__).resolve().parent / "data" / "voice_turn_metrics.jsonl")
TIMING_CONTRACT_NAME = "otoxan-mobile-canonical-timing"
TIMING_CONTRACT_VERSION = 1
TIMING_CONTRACT_CLOCK = "turn_elapsed_ms_from_android_monotonic_start"
TIMING_CONTRACT_TARGETS = {
    "ttfaMs": 1500,
    "postCaptureAckDelayMs": 250,
    "turnTotalMs": 8000,
    "backendRoundTripMs": 4000,
    "sttLatencyMs": 1500,
}
SPRINT4_STT_TOTAL_BUDGET_SECONDS = TIMING_CONTRACT_TARGETS["sttLatencyMs"] / 1000.0
SPRINT4_MOONSHINE_PRIMARY_BUDGET_SECONDS = 0.75
SPRINT4_STT_FALLBACK_MIN_SECONDS = 0.25
SPRINT4_HERMES_FALLBACK_BUDGET_SECONDS = 0.75
EXPERIMENTAL_STREAM_TRANSPORT_ENV = "OTOXAN_EXPERIMENTAL_STREAM_TRANSPORT"
EXPERIMENTAL_STREAM_ENDPOINT = "/voice-stream"
STREAM_TRANSPORT_PROTOCOL_NAME = "otoxan-mobile-backend-stream"
STREAM_TRANSPORT_PROTOCOL_VERSION = 1
ASSISTANT_PREP_CONTRACT_NAME = "otoxan-mobile-cancellable-assistant-prep"
ASSISTANT_PREP_CONTRACT_VERSION = 1
ASSISTANT_PREP_DEFAULT_DEADLINE_SECONDS = 12.0
STT_STREAM_EVENT_SCHEMA_NAME = "otoxan-mobile-stt-stream-events"
STT_STREAM_EVENT_SCHEMA_VERSION = 1
ASSISTANT_PREP_POLICY_NAME = "otoxan-mobile-safe-assistant-prep"
ASSISTANT_PREP_POLICY_VERSION = 1
BARGE_IN_POLICY_NAME = "otoxan-mobile-barge-in-cancellation"
BARGE_IN_POLICY_VERSION = 1
ASSISTANT_PREP_MIN_PARTIAL_CHARS = int(os.environ.get("OTOXAN_ASSISTANT_PREP_MIN_PARTIAL_CHARS", "12"))
ASSISTANT_PREP_MIN_PARTIAL_WORDS = int(os.environ.get("OTOXAN_ASSISTANT_PREP_MIN_PARTIAL_WORDS", "3"))
ASSISTANT_PREP_STABLE_TRANSCRIPT_SOURCES = {"hermes-stt", "moonshine-stt"}
ASSISTANT_PREP_STABLE_STT_STATUSES = {"success"}
REALTIME_VAD_DIAGNOSTIC_PROVIDER = "energy-vad-phase3"
REALTIME_VAD_DIAGNOSTIC_PEAK_THRESHOLD = int(os.environ.get("OTOXAN_REALTIME_VAD_PEAK_THRESHOLD", "700"))
REALTIME_VAD_DIAGNOSTIC_END_SILENCE_CHUNKS = int(os.environ.get("OTOXAN_REALTIME_VAD_END_SILENCE_CHUNKS", "3"))
MOONSHINE_STREAMING_ADAPTER_NAME = "moonshine-streaming-adapter"
MOONSHINE_STREAMING_ADAPTER_VERSION = 1
MOONSHINE_STREAMING_ADAPTER_ENV = "OTOXAN_MOONSHINE_STREAMING_ADAPTER"
MOONSHINE_STREAMING_COMMAND_ENV = "OTOXAN_MOONSHINE_STREAMING_COMMAND"
_STT_LOCK = threading.Lock()
_STT_TRANSCRIBE_AUDIO: Callable[[str], Mapping[str, Any]] | None = None
_STT_FAST_LOCAL_TRANSCRIBE: Callable[[str], Mapping[str, Any]] | None = None
_STT_LOAD_ERROR: str | None = None


def experimental_stream_transport_enabled() -> bool:
    return os.environ.get(EXPERIMENTAL_STREAM_TRANSPORT_ENV, "").strip().lower() in {"1", "true", "yes", "on"}


def stream_transport_descriptor() -> dict[str, Any]:
    return {
        "protocol": {
            "name": STREAM_TRANSPORT_PROTOCOL_NAME,
            "version": STREAM_TRANSPORT_PROTOCOL_VERSION,
        },
        "name": STREAM_TRANSPORT_PROTOCOL_NAME,
        "version": STREAM_TRANSPORT_PROTOCOL_VERSION,
        "endpoint": EXPERIMENTAL_STREAM_ENDPOINT,
        "method": "POST",
        "contentType": "application/x-ndjson",
        "experimentalFlag": EXPERIMENTAL_STREAM_TRANSPORT_ENV,
        "enabled": experimental_stream_transport_enabled(),
        "canonicalFallback": {
            "endpoint": "/voice-turn",
            "method": "POST",
            "semantics": "same_request_response_contract",
        },
        "sttEventSchema": stt_stream_event_schema(),
        "sttBudget": stt_budget_model(),
        "assistantPrepContract": assistant_prep_contract(),
        "bargeIn": barge_in_policy_descriptor(),
        "moonshineStreamingAdapter": moonshine_streaming_adapter_descriptor(),
    }


def assistant_prep_contract() -> dict[str, Any]:
    """Return the explicit contract for assistant prep around one voice turn.

    MVP mobile turns must not start speculative assistant work while capture/STT is
    still mutable unless that work can be cancelled from the same explicit user
    session. The current repo-local backend only starts Xander/mobile-fast after
    a final transcript exists, so the contract advertises a safe disabled prep
    state plus the events a future realtime prep lane must honor before it can be
    enabled.
    """
    deadline_seconds = _bounded_seconds(
        "OTOXAN_ASSISTANT_PREP_DEADLINE_SECONDS",
        ASSISTANT_PREP_DEFAULT_DEADLINE_SECONDS,
        0.5,
        30.0,
    )
    return {
        "name": ASSISTANT_PREP_CONTRACT_NAME,
        "version": ASSISTANT_PREP_CONTRACT_VERSION,
        "enabled": False,
        "speculativePrepAllowed": False,
        "requiresFinalTranscript": False,
        "promotionRequiresFinalTranscriptValidation": True,
        "startAfterEvent": "stt.partial",
        "promoteAfterEvent": "stt.final",
        "startPolicy": "stable_non_final_stt_partial_only",
        "promotionPolicy": "final_transcript_must_match_validated_prep_candidate",
        "allowedTranscriptSources": sorted(ASSISTANT_PREP_STABLE_TRANSCRIPT_SOURCES),
        "allowedSttStatuses": sorted(ASSISTANT_PREP_STABLE_STT_STATUSES),
        "minTranscriptChars": ASSISTANT_PREP_MIN_PARTIAL_CHARS,
        "minTranscriptWords": ASSISTANT_PREP_MIN_PARTIAL_WORDS,
        "neverTriggerFrom": [
            "vad_only",
            "route_evidence_fallback",
            "debug_transcript",
            "proof_mode",
            "empty_or_unstable_partial",
            "final_only_transcript",
        ],
        "cancelEvents": [
            "input_audio.clear",
            "session.close",
            "turn.timeout",
            "new_turn_started",
        ],
        "deadlineMs": int(round(deadline_seconds * 1000)),
        "fallbackOnCancel": "degraded_spoken_response_without_provider_detail",
        "privacy": {
            "explicitSessionOnly": True,
            "rawAudioPersisted": False,
            "rawTranscriptPersistedByContract": False,
        },
        "currentImplementation": {
            "mobileFast": "post_stable_partial_prep_marker_then_post_stt_final_turn",
            "xanderSessionFallback": "opt_in_post_stt_final_deadline_thread",
            "preStablePartialAssistantWork": "forbidden_until_stt_stability_policy_passes",
            "speculativeResponsePromotion": "blocked_until_stt_final_validates_prep_candidate",
        },
        "evidenceClass": "contract_readback_not_hardware_proof",
    }


def moonshine_streaming_adapter_descriptor() -> dict[str, Any]:
    """Describe the optional local Moonshine streaming seam without importing it.

    The mobile backend can now advertise where a future/chosen Moonshine streaming
    adapter plugs in, but a clean clone must keep working without Moonshine Python
    packages installed. Runtime audio remains on the existing /voice-turn contract
    until an operator explicitly configures the command adapter.
    """
    mode = os.environ.get(MOONSHINE_STREAMING_ADAPTER_ENV, "disabled").strip().lower() or "disabled"
    command_template = os.environ.get(MOONSHINE_STREAMING_COMMAND_ENV, "").strip()
    command_configured = bool(command_template)
    command_mode_requested = mode in {"1", "true", "yes", "on", "command"}
    enabled = command_mode_requested and command_configured
    status = "configured" if enabled else "disabled"
    if command_mode_requested and not command_configured:
        status = "command-not-configured"
    return {
        "name": MOONSHINE_STREAMING_ADAPTER_NAME,
        "version": MOONSHINE_STREAMING_ADAPTER_VERSION,
        "provider": "moonshine-stt",
        "enabled": enabled,
        "status": status,
        "mode": "command" if command_mode_requested else "disabled",
        "hardDependency": False,
        "importPolicy": "no Moonshine package import at server startup",
        "commandEnv": MOONSHINE_STREAMING_COMMAND_ENV,
        "commandConfigured": command_configured,
        "adapterFlag": MOONSHINE_STREAMING_ADAPTER_ENV,
        "inputAudioFormat": SUPPORTED_FORMAT,
        "events": ["stt.partial", "stt.final", "stt.completed"],
        "canonicalFallback": {
            "endpoint": "/voice-turn",
            "method": "POST",
            "semantics": "same_request_response_contract",
        },
        "privacy": {
            "rawAudioPersisted": False,
            "explicitSessionOnly": True,
        },
        "evidenceClass": "adapter_seam_readback_not_hardware_proof",
    }


def stt_budget_model() -> dict[str, Any]:
    total_budget_seconds = _bounded_seconds(
        "OTOXAN_STT_TOTAL_BUDGET_SECONDS",
        SPRINT4_STT_TOTAL_BUDGET_SECONDS,
        0.5,
        10.0,
    )
    primary_budget_seconds = _bounded_seconds(
        "OTOXAN_MOONSHINE_STT_TIMEOUT_SECONDS",
        SPRINT4_MOONSHINE_PRIMARY_BUDGET_SECONDS,
        0.2,
        8.0,
    )
    fallback_reserve_seconds = _bounded_seconds(
        "OTOXAN_STT_FALLBACK_MIN_SECONDS",
        SPRINT4_STT_FALLBACK_MIN_SECONDS,
        0.0,
        5.0,
    )
    fallback_max_seconds = _bounded_seconds(
        "OTOXAN_HERMES_STT_FALLBACK_TIMEOUT_SECONDS",
        SPRINT4_HERMES_FALLBACK_BUDGET_SECONDS,
        0.0,
        5.0,
    )
    total_budget_ms = int(round(total_budget_seconds * 1000))
    primary_budget_ms = int(round(primary_budget_seconds * 1000))
    fallback_reserve_ms = int(round(fallback_reserve_seconds * 1000))
    fallback_max_ms = int(round(fallback_max_seconds * 1000))
    fallback_budget_ms = max(0, total_budget_ms - primary_budget_ms)
    return {
        "name": "sprint4-stt-budget",
        "version": 1,
        "targetField": "sttLatencyMs",
        "targetMs": TIMING_CONTRACT_TARGETS["sttLatencyMs"],
        "totalBudgetMs": total_budget_ms,
        "primaryLocalBudgetMs": primary_budget_ms,
        "fallbackReserveMs": fallback_reserve_ms,
        "fallbackMaxMs": fallback_max_ms,
        "fallbackBudgetMs": fallback_budget_ms,
        "primaryProvider": "moonshine-stt",
        "fallbackProvider": "hermes-stt",
        "environmentOverrides": {
            "totalBudgetSeconds": "OTOXAN_STT_TOTAL_BUDGET_SECONDS",
            "primaryLocalBudgetSeconds": "OTOXAN_MOONSHINE_STT_TIMEOUT_SECONDS",
            "fallbackReserveSeconds": "OTOXAN_STT_FALLBACK_MIN_SECONDS",
            "fallbackMaxSeconds": "OTOXAN_HERMES_STT_FALLBACK_TIMEOUT_SECONDS",
        },
        "evidenceClass": "latency_budget_readback_not_hardware_proof",
        "hardwareGate": "requires_fresh_phone_rayban_turn",
    }


def stt_stream_event_schema() -> dict[str, Any]:
    return {
        "name": STT_STREAM_EVENT_SCHEMA_NAME,
        "version": STT_STREAM_EVENT_SCHEMA_VERSION,
        "audioFormat": SUPPORTED_FORMAT,
        "privacy": {
            "rawAudioPersisted": False,
            "transcriptTextPersistedBySchema": False,
            "explicitSessionOnly": True,
        },
        "events": [
            {
                "type": "stt.budget",
                "sequence": "monotonic stream sequence",
                "payload": [
                    "sttBudget.targetMs",
                    "sttBudget.totalBudgetMs",
                    "sttBudget.primaryLocalBudgetMs",
                    "sttBudget.fallbackReserveMs",
                    "sttBudget.fallbackMaxMs",
                    "sttBudget.fallbackBudgetMs",
                    "sttBudget.evidenceClass",
                ],
                "emission": "stream.started discovery/readback",
            },
            {
                "type": "stt.partial",
                "sequence": "monotonic stream sequence",
                "payload": [
                    "stt.provider",
                    "stt.status",
                    "stt.transcriptLength",
                    "stt.isFinal",
                    "stt.transcriptSource",
                    "stt.textOmitted",
                ],
                "emission": "optional diagnostic readback before final transcript state; no raw transcript text is persisted by schema",
            },
            {
                "type": "stt.final",
                "sequence": "monotonic stream sequence",
                "payload": [
                    "stt.provider",
                    "stt.status",
                    "stt.transcriptLength",
                    "stt.isFinal",
                    "stt.transcriptSource",
                    "stt.textOmitted",
                ],
                "emission": "final transcript state readback before response.completed; no standalone raw transcript persistence",
            },
            {
                "type": "stt.completed",
                "sequence": "monotonic stream sequence",
                "payload": [
                    "stt.provider",
                    "stt.status",
                    "stt.latencyMs",
                    "stt.primaryStatus",
                    "stt.primaryLatencyMs",
                    "stt.primaryProvider",
                    "stt.fallbackStatus",
                    "stt.fallbackLatencyMs",
                    "stt.fallbackProvider",
                    "stt.budgetRemainingMs",
                    "stt.transcriptSource",
                ],
                "emission": "after /voice-turn STT work completes, before stream.completed",
            },
            {
                "type": "assistant.prep.started",
                "sequence": "monotonic stream sequence when emitted",
                "payload": [
                    "assistantPrepContract.policy.name",
                    "assistantPrepContract.trigger",
                    "assistantPrepContract.status",
                    "assistantPrepContract.transcriptLength",
                    "assistantPrepContract.transcriptSource",
                    "assistantPrepContract.textOmitted",
                    "assistantPrepContract.assistantInvoked",
                ],
                "emission": "optional safe prewarm marker; emitted only after a stable non-final STT partial passes policy gates",
            },
            {
                "type": "barge_in.detected",
                "sequence": "monotonic stream sequence when user speech interrupts an active assistant response",
                "payload": [
                    "bargeIn.policy.name",
                    "bargeIn.sourceEvent",
                    "bargeIn.cancelledTurnIndex",
                    "bargeIn.cancellationEvent",
                    "bargeIn.assistantInvokedByBargeIn",
                ],
                "emission": "diagnostic control event only; does not invoke a new assistant turn or persist raw audio",
            },
            {
                "type": "response.cancelled",
                "sequence": "monotonic stream sequence after barge_in.detected or explicit cancel",
                "payload": [
                    "reason",
                    "cancelledTurnIndex",
                    "assistantInvoked",
                    "nextTurnRequires",
                ],
                "emission": "client should stop current playback/prep and wait for the next explicit commit",
            },
        ],
    }


def assistant_prep_policy_descriptor() -> dict[str, Any]:
    contract = assistant_prep_contract()
    return {
        "name": ASSISTANT_PREP_POLICY_NAME,
        "version": ASSISTANT_PREP_POLICY_VERSION,
        "trigger": contract["startPolicy"],
        "allowedTranscriptSources": contract["allowedTranscriptSources"],
        "allowedSttStatuses": contract["allowedSttStatuses"],
        "minTranscriptChars": contract["minTranscriptChars"],
        "minTranscriptWords": contract["minTranscriptWords"],
        "neverTriggerFrom": contract["neverTriggerFrom"],
        "assistantInvokedByPrep": False,
        "privacy": {
            "rawTranscriptPersistedByPrep": False,
            "rawAudioPersisted": False,
            "explicitSessionOnly": True,
        },
        "evidenceClass": "safe_prep_policy_readback_not_hardware_proof",
    }


def barge_in_policy_descriptor() -> dict[str, Any]:
    return {
        "name": BARGE_IN_POLICY_NAME,
        "version": BARGE_IN_POLICY_VERSION,
        "trigger": "user_speech_while_assistant_response_active",
        "detection": {
            "provider": REALTIME_VAD_DIAGNOSTIC_PROVIDER,
            "peakThreshold": REALTIME_VAD_DIAGNOSTIC_PEAK_THRESHOLD,
            "requiresExplicitSession": True,
            "diagnosticOnly": True,
        },
        "events": ["barge_in.detected", "response.cancelled"],
        "cancelEvents": ["barge_in.detected", "input_audio.clear", "session.close", "turn.timeout", "new_turn_started"],
        "assistantAuthority": "no_new_assistant_turn_until_explicit_commit",
        "fallbackOnCancel": "stop_current_playback_and_wait_for_next_push_to_talk_commit",
        "privacy": {
            "explicitSessionOnly": True,
            "rawAudioPersisted": False,
            "alwaysOnRecording": False,
        },
        "evidenceClass": "diagnostic_stream_control_not_hardware_proof",
    }


def _barge_in_event(stream_id: str, sequence: int, *, source_event: str, cancelled_turn_index: int | None = None) -> dict[str, Any]:
    return {
        "type": "barge_in.detected",
        "streamId": stream_id,
        "sequence": sequence,
        "bargeIn": {
            "policy": barge_in_policy_descriptor(),
            "sourceEvent": source_event,
            "cancelledTurnIndex": cancelled_turn_index,
            "cancellationEvent": "response.cancelled",
            "assistantInvokedByBargeIn": False,
            "evidenceClass": "diagnostic_stream_control_not_hardware_proof",
        },
        "privacy": {
            "rawAudioPersisted": False,
            "explicitSessionOnly": True,
        },
    }


def _response_cancelled_event(stream_id: str, sequence: int, *, reason: str, cancelled_turn_index: int | None = None) -> dict[str, Any]:
    return {
        "type": "response.cancelled",
        "streamId": stream_id,
        "sequence": sequence,
        "reason": reason,
        "cancelledTurnIndex": cancelled_turn_index,
        "assistantInvoked": False,
        "nextTurnRequires": "input_audio.commit",
        "bargeIn": barge_in_policy_descriptor(),
        "privacy": {
            "rawAudioPersisted": False,
            "explicitSessionOnly": True,
        },
    }


def _payload_requests_barge_in_cancel(payload: Mapping[str, Any]) -> bool:
    raw = payload.get("bargeIn")
    if isinstance(raw, Mapping):
        return bool(raw.get("detected") or raw.get("cancelResponse") or raw.get("cancel"))
    return bool(payload.get("bargeInDetected") or payload.get("cancelResponse"))


def _payload_cancel_reason(payload: Mapping[str, Any]) -> str:
    raw = payload.get("bargeIn")
    if isinstance(raw, Mapping):
        reason = str(raw.get("reason") or "").strip()
        if reason:
            return reason
    reason = str(payload.get("cancelReason") or "").strip()
    return reason or "user_barge_in"


def _payload_cancelled_turn_index(payload: Mapping[str, Any]) -> int | None:
    raw = payload.get("bargeIn")
    value = raw.get("cancelledTurnIndex") if isinstance(raw, Mapping) else payload.get("cancelledTurnIndex")
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _stt_completed_event_from_voice_turn(result: Mapping[str, Any], stream_id: str, sequence: int) -> dict[str, Any]:
    return {
        "type": "stt.completed",
        "streamId": stream_id,
        "sequence": sequence,
        "schema": {
            "name": STT_STREAM_EVENT_SCHEMA_NAME,
            "version": STT_STREAM_EVENT_SCHEMA_VERSION,
        },
        "stt": {
            "provider": result.get("sttProvider"),
            "status": result.get("sttStatus"),
            "latencyMs": result.get("sttLatencyMs"),
            "primaryStatus": result.get("primarySttStatus"),
            "primaryLatencyMs": result.get("primarySttMs"),
            "primaryProvider": result.get("primarySttProvider"),
            "fallbackStatus": result.get("fallbackSttStatus"),
            "fallbackLatencyMs": result.get("fallbackSttMs"),
            "fallbackProvider": result.get("fallbackSttProvider"),
            "budgetRemainingMs": result.get("sttBudgetRemainingMs"),
            "transcriptSource": result.get("transcriptSource"),
            "evidenceClass": "latency_budget_readback_not_hardware_proof",
        },
        "sttBudget": stt_budget_model(),
    }


def _stt_transcript_state_event_from_voice_turn(result: Mapping[str, Any], stream_id: str, sequence: int, event_type: str, is_final: bool) -> dict[str, Any]:
    transcript = str(result.get("transcript") or "")
    return {
        "type": event_type,
        "streamId": stream_id,
        "sequence": sequence,
        "schema": {
            "name": STT_STREAM_EVENT_SCHEMA_NAME,
            "version": STT_STREAM_EVENT_SCHEMA_VERSION,
        },
        "stt": {
            "provider": result.get("sttProvider"),
            "status": result.get("sttStatus"),
            "transcriptLength": len(transcript),
            "isFinal": is_final,
            "transcriptSource": result.get("transcriptSource"),
            "textOmitted": True,
            "evidenceClass": "transcript_state_readback_not_hardware_proof",
        },
        "privacy": {
            "rawTranscriptPersistedByEvent": False,
            "rawAudioPersisted": False,
        },
    }


def _word_count(text: str) -> int:
    return len([word for word in text.strip().split() if word])


def _assistant_prep_event_from_stable_partial(result: Mapping[str, Any], stream_id: str, sequence: int) -> dict[str, Any] | None:
    """Emit prep only after a stable non-final STT partial policy gate.

    The event is a safe prewarm/readback marker. It does not invoke the assistant
    and never persists raw transcript text. Proof, debug, route-evidence fallback,
    VAD-only, empty, and short/unstable partial states are deliberately rejected.
    """
    transcript = str(result.get("transcript") or "").strip()
    transcript_source = str(result.get("transcriptSource") or "")
    stt_status = str(result.get("sttStatus") or "")
    transcript_length = len(transcript)
    transcript_word_count = _word_count(transcript)
    stable = (
        transcript_source in ASSISTANT_PREP_STABLE_TRANSCRIPT_SOURCES
        and stt_status in ASSISTANT_PREP_STABLE_STT_STATUSES
        and transcript_length >= ASSISTANT_PREP_MIN_PARTIAL_CHARS
        and transcript_word_count >= ASSISTANT_PREP_MIN_PARTIAL_WORDS
    )
    if not stable:
        return None
    return {
        "type": "assistant.prep.started",
        "streamId": stream_id,
        "sequence": sequence,
        "assistantPrepContract": {
            "policy": assistant_prep_policy_descriptor(),
            "trigger": "stable_non_final_stt_partial",
            "status": "started",
            "transcriptLength": transcript_length,
            "transcriptWordCount": transcript_word_count,
            "transcriptSource": transcript_source,
            "sttStatus": stt_status,
            "isFinal": False,
            "textOmitted": True,
            "assistantInvoked": False,
            "promotionBlockedUntil": "stt.final",
            "promotionRequiresFinalTranscriptValidation": True,
            "evidenceClass": "safe_prep_policy_readback_not_hardware_proof",
        },
        "privacy": {
            "rawTranscriptPersistedByEvent": False,
            "rawAudioPersisted": False,
        },
    }


def _assistant_prep_final_validation(prep_event: Mapping[str, Any] | None, result: Mapping[str, Any]) -> dict[str, Any]:
    """Validate the final transcript state before any speculative response promotion.

    The current backend still invokes the assistant only after final STT, but this
    readback locks the future speculative path: a prep candidate cannot be
    promoted unless the final transcript is from the same stable STT class and
    still matches the non-final candidate's transcript state. Raw transcript text
    remains omitted from stream metadata.
    """
    if prep_event is None:
        return {
            "required": False,
            "validated": True,
            "promotionAllowed": False,
            "promotedSpeculativeResponse": False,
            "reason": "no_speculative_prep_candidate",
            "promotionPolicy": "final_transcript_must_match_validated_prep_candidate",
            "evidenceClass": "speculative_promotion_guard_readback_not_hardware_proof",
        }

    prep_contract = _mapping(prep_event.get("assistantPrepContract"))
    final_transcript = str(result.get("transcript") or "").strip()
    final_length = len(final_transcript)
    final_word_count = _word_count(final_transcript)
    final_source = str(result.get("transcriptSource") or "")
    final_status = str(result.get("sttStatus") or "")
    length_matches = prep_contract.get("transcriptLength") == final_length
    words_match = prep_contract.get("transcriptWordCount") == final_word_count
    source_matches = prep_contract.get("transcriptSource") == final_source
    status_matches = prep_contract.get("sttStatus") == final_status
    final_stable = (
        final_source in ASSISTANT_PREP_STABLE_TRANSCRIPT_SOURCES
        and final_status in ASSISTANT_PREP_STABLE_STT_STATUSES
        and final_length >= ASSISTANT_PREP_MIN_PARTIAL_CHARS
        and final_word_count >= ASSISTANT_PREP_MIN_PARTIAL_WORDS
    )
    validated = bool(final_stable and length_matches and words_match and source_matches and status_matches)
    return {
        "required": True,
        "validated": validated,
        "promotionAllowed": validated,
        "promotedSpeculativeResponse": False,
        "reason": "final_transcript_validated" if validated else "final_transcript_mismatch_or_unstable",
        "promotionPolicy": "final_transcript_must_match_validated_prep_candidate",
        "validatedAfterEvent": "stt.final",
        "candidate": {
            "transcriptLength": prep_contract.get("transcriptLength"),
            "transcriptWordCount": prep_contract.get("transcriptWordCount"),
            "transcriptSource": prep_contract.get("transcriptSource"),
            "sttStatus": prep_contract.get("sttStatus"),
            "textOmitted": True,
        },
        "final": {
            "transcriptLength": final_length,
            "transcriptWordCount": final_word_count,
            "transcriptSource": final_source,
            "sttStatus": final_status,
            "textOmitted": True,
        },
        "checks": {
            "finalStable": final_stable,
            "lengthMatches": length_matches,
            "wordCountMatches": words_match,
            "sourceMatches": source_matches,
            "statusMatches": status_matches,
        },
        "privacy": {
            "rawTranscriptPersistedByValidation": False,
            "rawAudioPersisted": False,
        },
        "evidenceClass": "speculative_promotion_guard_readback_not_hardware_proof",
    }


class VoiceTurnError(Exception):
    def __init__(self, message: str, status: int = 400) -> None:
        super().__init__(message)
        self.status = status


def _safe_log(message: str) -> None:
    try:
        print(message, file=sys.stderr, flush=True)
    except Exception:
        pass


def _bounded_seconds(env_name: str, default: float, minimum: float, maximum: float) -> float:
    raw = os.environ.get(env_name, str(default)).strip()
    try:
        return max(minimum, min(float(raw), maximum))
    except ValueError:
        return float(default)


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
    provider: str = "hermes-stt"
    primary_status: str | None = None
    primary_latency_ms: int | None = None
    primary_provider: str | None = None
    fallback_status: str | None = None
    fallback_latency_ms: int | None = None
    fallback_provider: str | None = None
    budget_remaining_ms: int | None = None


@dataclass(frozen=True)
class TranscriptResult:
    transcript: str
    source: str
    stt_status: str
    stt_latency_ms: int | None
    stt_provider: str
    primary_stt_status: str | None = None
    primary_stt_ms: int | None = None
    primary_stt_provider: str | None = None
    fallback_stt_status: str | None = None
    fallback_stt_ms: int | None = None
    fallback_stt_provider: str | None = None
    stt_budget_remaining_ms: int | None = None


@dataclass(frozen=True)
class AssistantTurn:
    transcript: str
    assistant_text: str
    tts_pcm: bytes
    provider: str
    transcript_source: str
    stt_status: str
    stt_latency_ms: int | None
    stt_provider: str
    timing: dict[str, Any]


def handle_voice_turn(payload: Mapping[str, Any]) -> dict[str, Any]:
    started = time.monotonic()
    timing: dict[str, Any] = {}
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
        "sttProvider": turn.stt_provider,
        "turnOutcome": _turn_outcome(turn, stats),
        "assistantPrepContract": assistant_prep_contract(),
        "mobileFastRuntimeContract": mobile_fast_runtime_contract(),
        "primarySttStatus": timing.get("primarySttStatus"),
        "primarySttMs": timing.get("primarySttMs"),
        "primarySttProvider": timing.get("primarySttProvider"),
        "fallbackSttStatus": timing.get("fallbackSttStatus"),
        "fallbackSttMs": timing.get("fallbackSttMs"),
        "fallbackSttProvider": timing.get("fallbackSttProvider"),
        "sttBudgetRemainingMs": timing.get("sttBudgetRemainingMs"),
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
        "xanderFastMs": timing.get("xanderFastMs"),
        "xanderFastStatus": timing.get("xanderFastStatus"),
        "xanderFastTimedOut": timing.get("xanderFastTimedOut"),
        "mobileFastProvider": timing.get("mobileFastProvider"),
        "mobileFastModel": timing.get("mobileFastModel"),
        "mobileFastTimeoutSeconds": timing.get("mobileFastTimeoutSeconds"),
        "mobileFastHardTimeoutSeconds": timing.get("mobileFastHardTimeoutSeconds"),
        "mobileFastSessionFallbackEnabled": timing.get("mobileFastSessionFallbackEnabled"),
        "mobileFastSessionFallbackHardTimeoutSeconds": timing.get("mobileFastSessionFallbackHardTimeoutSeconds"),
        "mobileFastFailureReason": timing.get("mobileFastFailureReason"),
        "xanderFallbackSessionStatus": timing.get("xanderFallbackSessionStatus"),
        "xanderFallbackSkipped": timing.get("xanderFallbackSkipped"),
        "xanderFallbackTimedOut": timing.get("xanderFallbackTimedOut"),
        "xanderFallbackFailureReason": timing.get("xanderFallbackFailureReason"),
        "ttsProvider": timing.get("ttsProvider"),
        "ttsStatus": timing.get("ttsStatus"),
        "ttsLatencyMs": timing.get("ttsLatencyMs"),
        "responseBuildMs": 0,
    }
    response["responseBuildMs"] = _elapsed_ms(started) - int(response.get("backendTotalMs") or 0)
    response["timing"]["responseBuildMs"] = response["responseBuildMs"]
    return response


def _turn_outcome(turn: AssistantTurn, stats: Mapping[str, Any]) -> dict[str, Any]:
    """Return assistant-turn outcome separately from hardware/pass1 evidence."""
    timing = turn.timing
    if not str(turn.transcript).strip() or turn.transcript_source == "route-evidence-fallback":
        status = "degraded-no-transcript"
        assistant_source = "degraded-no-transcript"
    elif turn.provider == MOBILE_FAST_PROVIDER and timing.get("xanderFastStatus") == 1:
        status = "assistant-success"
        assistant_source = "mobile-fast"
    elif turn.provider == MOBILE_FAST_PROVIDER and timing.get("xanderFallbackSessionStatus") == 1:
        status = "assistant-success"
        assistant_source = "xander-session-fallback"
    elif turn.provider == MOBILE_FAST_PROVIDER:
        status = "degraded-model-fallback"
        assistant_source = "degraded-response"
    elif turn.provider == XANDER_PROVIDER and turn.stt_status == "success":
        status = "assistant-success"
        assistant_source = "xander-session"
    elif turn.provider == "proof":
        status = "proof-only"
        assistant_source = "proof"
    else:
        status = "degraded"
        assistant_source = "degraded-response"
    outcome = {
        "status": status,
        "assistantResponseSource": assistant_source,
        "degraded": status.startswith("degraded"),
        "provider": turn.provider,
        "transcriptSource": turn.transcript_source,
        "sttStatus": turn.stt_status,
        "assistantTextLength": len(turn.assistant_text),
        "transcriptLength": len(turn.transcript),
        "capturePeak": stats.get("peak"),
        "evidenceClass": "backend_turn_outcome_not_hardware_proof",
    }
    failure_reason = _first_present(timing.get("mobileFastFailureReason"), timing.get("xanderFallbackFailureReason"))
    if failure_reason and outcome["degraded"]:
        outcome["failureReason"] = failure_reason
    return outcome


def handle_voice_stream(payload: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Return the experimental backend streaming envelope for one explicit turn.

    This is intentionally a thin transport shim over the proven /voice-turn
    contract. It gives the Android client a stream-shaped backend endpoint to
    exercise without moving authority, persistence, capture policy, or assistant
    semantics away from handle_voice_turn.
    """
    stream_id = f"vs_{uuid.uuid4().hex}"
    started = time.monotonic()
    events: list[dict[str, Any]] = [
        {
            "type": "stream.started",
            "streamId": stream_id,
            "sequence": 1,
            "protocol": {
                "name": STREAM_TRANSPORT_PROTOCOL_NAME,
                "version": STREAM_TRANSPORT_PROTOCOL_VERSION,
            },
            "transport": stream_transport_descriptor(),
            "sttEventSchema": stt_stream_event_schema(),
            "sttBudget": stt_budget_model(),
            "assistantPrepContract": assistant_prep_contract(),
            "bargeIn": barge_in_policy_descriptor(),
            "privacy": {
                "explicitSessionOnly": True,
                "rawAudioPersisted": False,
                "alwaysOnRecording": False,
            },
        }
    ]
    if _payload_requests_barge_in_cancel(payload):
        cancelled_turn_index = _payload_cancelled_turn_index(payload)
        events.append(_barge_in_event(stream_id, len(events) + 1, source_event="voice-stream.request", cancelled_turn_index=cancelled_turn_index))
        events.append(
            _response_cancelled_event(
                stream_id,
                len(events) + 1,
                reason=_payload_cancel_reason(payload),
                cancelled_turn_index=cancelled_turn_index,
            )
        )
        events.append(
            {
                "type": "stream.completed",
                "streamId": stream_id,
                "sequence": len(events) + 1,
                "elapsedMs": _elapsed_ms(started),
                "fallback": stream_transport_descriptor()["canonicalFallback"],
                "sttBudget": stt_budget_model(),
                "assistantPrepContract": assistant_prep_contract(),
                "bargeIn": barge_in_policy_descriptor(),
            }
        )
        return events
    result = handle_voice_turn(payload)
    events.append(_stt_transcript_state_event_from_voice_turn(result, stream_id, len(events) + 1, "stt.partial", False))
    prep_event = _assistant_prep_event_from_stable_partial(result, stream_id, len(events) + 1)
    if prep_event is not None:
        events.append(prep_event)
    events.append(_stt_transcript_state_event_from_voice_turn(result, stream_id, len(events) + 1, "stt.final", True))
    prep_validation = _assistant_prep_final_validation(prep_event, result)
    events.append(_stt_completed_event_from_voice_turn(result, stream_id, len(events) + 1))
    events.append(
        {
            "type": "response.completed",
            "streamId": stream_id,
            "sequence": len(events) + 1,
            "assistantPrepPromotion": prep_validation,
            "voiceTurn": result,
        }
    )
    events.append(
        {
            "type": "stream.completed",
            "streamId": stream_id,
            "sequence": len(events) + 1,
            "elapsedMs": _elapsed_ms(started),
            "fallback": stream_transport_descriptor()["canonicalFallback"],
            "sttBudget": stt_budget_model(),
            "assistantPrepContract": assistant_prep_contract(),
            "assistantPrepPromotion": prep_validation,
            "bargeIn": barge_in_policy_descriptor(),
        }
    )
    return events


def _pass1_ready(turn: AssistantTurn) -> bool:
    return (
        turn.provider in {XANDER_PROVIDER, MOBILE_FAST_PROVIDER}
        and turn.transcript_source in {"hermes-stt", "moonshine-stt"}
        and turn.stt_status == "success"
    )


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
    timing: dict[str, Any] = {}
    transcript_started = time.monotonic()
    transcript = _xander_transcript(pcm, route)
    timing["transcriptTotalMs"] = _elapsed_ms(transcript_started)
    timing["sttLatencyMs"] = transcript.stt_latency_ms
    timing["sttProvider"] = transcript.stt_provider
    timing["primarySttStatus"] = transcript.primary_stt_status
    timing["primarySttMs"] = transcript.primary_stt_ms
    timing["primarySttProvider"] = transcript.primary_stt_provider
    timing["fallbackSttStatus"] = transcript.fallback_stt_status
    timing["fallbackSttMs"] = transcript.fallback_stt_ms
    timing["fallbackSttProvider"] = transcript.fallback_stt_provider
    timing["sttBudgetRemainingMs"] = transcript.stt_budget_remaining_ms
    if _is_stt_fallback(transcript.transcript):
        timing["xanderSessionMs"] = None
        return AssistantTurn(
            transcript=transcript.transcript,
            assistant_text=NO_TRANSCRIPT_DEGRADED_RESPONSE,
            tts_pcm=b"",
            provider=XANDER_PROVIDER,
            transcript_source=transcript.source,
            stt_status=transcript.stt_status,
            stt_latency_ms=transcript.stt_latency_ms,
            stt_provider=transcript.stt_provider,
            timing=timing,
        )
    session_started = time.monotonic()
    assistant_text = _ask_xander_session(transcript.transcript, route)
    timing["xanderSessionMs"] = _elapsed_ms(session_started)
    tts_pcm = _synthesize_optional_tts(assistant_text, timing)
    return AssistantTurn(
        transcript=transcript.transcript,
        assistant_text=assistant_text,
        tts_pcm=tts_pcm,
        provider=XANDER_PROVIDER,
        transcript_source=transcript.source,
        stt_status=transcript.stt_status,
        stt_latency_ms=transcript.stt_latency_ms,
        stt_provider=transcript.stt_provider,
        timing=timing,
    )


def _xander_mobile_fast_turn(pcm: bytes, route: RouteSummary) -> AssistantTurn:
    timing: dict[str, Any] = _mobile_fast_runtime_descriptor()
    transcript_started = time.monotonic()
    transcript = _xander_transcript(pcm, route)
    timing["transcriptTotalMs"] = _elapsed_ms(transcript_started)
    timing["sttLatencyMs"] = transcript.stt_latency_ms
    timing["sttProvider"] = transcript.stt_provider
    timing["primarySttStatus"] = transcript.primary_stt_status
    timing["primarySttMs"] = transcript.primary_stt_ms
    timing["primarySttProvider"] = transcript.primary_stt_provider
    timing["fallbackSttStatus"] = transcript.fallback_stt_status
    timing["fallbackSttMs"] = transcript.fallback_stt_ms
    timing["fallbackSttProvider"] = transcript.fallback_stt_provider
    timing["sttBudgetRemainingMs"] = transcript.stt_budget_remaining_ms
    if _is_stt_fallback(transcript.transcript):
        timing["xanderSessionMs"] = None
        timing["xanderFastMs"] = None
        return AssistantTurn(
            transcript=transcript.transcript,
            assistant_text=NO_TRANSCRIPT_DEGRADED_RESPONSE,
            tts_pcm=b"",
            provider=MOBILE_FAST_PROVIDER,
            transcript_source=transcript.source,
            stt_status=transcript.stt_status,
            stt_latency_ms=transcript.stt_latency_ms,
            stt_provider=transcript.stt_provider,
            timing=timing,
        )
    fast_started = time.monotonic()
    assistant_text, fast_status, fast_timed_out, fast_failure_reason = _ask_xander_mobile_fast_with_deadline(transcript.transcript, route)
    timing["xanderFastStatus"] = 1 if fast_status == "success" else 0
    timing["xanderFastTimedOut"] = 1 if fast_timed_out else 0
    timing["xanderFallbackSessionStatus"] = 0
    if fast_failure_reason:
        timing["mobileFastFailureReason"] = fast_failure_reason
    if fast_status != "success":
        if _mobile_fast_session_fallback_enabled():
            assistant_text, fallback_status, fallback_timed_out, fallback_failure_reason = _ask_xander_session_with_deadline(transcript.transcript, route)
            timing["xanderFallbackSessionStatus"] = 1 if fallback_status == "success" else 0
            timing["xanderFallbackTimedOut"] = 1 if fallback_timed_out else 0
            if fallback_failure_reason:
                timing["xanderFallbackFailureReason"] = fallback_failure_reason
            if fallback_status != "success":
                assistant_text = _mobile_fast_degraded_spoken_response(transcript.transcript)
        else:
            timing["xanderFallbackSkipped"] = 1
            assistant_text = _mobile_fast_degraded_spoken_response(transcript.transcript)
    fast_ms = _elapsed_ms(fast_started)
    # Keep xanderSessionMs populated so existing Android telemetry charts compare old vs fast lane.
    timing["xanderSessionMs"] = fast_ms
    timing["xanderFastMs"] = fast_ms
    tts_pcm = _synthesize_optional_tts(assistant_text, timing)
    return AssistantTurn(
        transcript=transcript.transcript,
        assistant_text=assistant_text,
        tts_pcm=tts_pcm,
        provider=MOBILE_FAST_PROVIDER,
        transcript_source=transcript.source,
        stt_status=transcript.stt_status,
        stt_latency_ms=transcript.stt_latency_ms,
        stt_provider=transcript.stt_provider,
        timing=timing,
    )


def _is_stt_fallback(transcript: str) -> bool:
    return not transcript.strip() or "Hermes STT lane did not return a transcript for this turn." in transcript


def _xander_transcript(pcm: bytes, route: RouteSummary) -> TranscriptResult:
    debug_transcript = os.environ.get("OTOXAN_DEBUG_TRANSCRIPT", "").strip()
    if debug_transcript:
        return TranscriptResult(
            transcript=_clean(debug_transcript, "Voice turn received."),
            source="debug",
            stt_status="not-run",
            stt_latency_ms=None,
            stt_provider="not-run",
            primary_stt_status="not-run",
            primary_stt_provider="not-run",
        )

    stt = _transcribe_with_hermes_stt(pcm)
    if stt.transcript:
        return TranscriptResult(
            transcript=stt.transcript,
            source=stt.provider,
            stt_status=stt.status,
            stt_latency_ms=stt.latency_ms,
            stt_provider=stt.provider,
            primary_stt_status=stt.primary_status,
            primary_stt_ms=stt.primary_latency_ms,
            primary_stt_provider=stt.primary_provider,
            fallback_stt_status=stt.fallback_status,
            fallback_stt_ms=stt.fallback_latency_ms,
            fallback_stt_provider=stt.fallback_provider,
            stt_budget_remaining_ms=stt.budget_remaining_ms,
        )

    return TranscriptResult(
        transcript="",
        source="route-evidence-fallback",
        stt_status=stt.status,
        stt_latency_ms=stt.latency_ms,
        stt_provider=stt.provider,
        primary_stt_status=stt.primary_status,
        primary_stt_ms=stt.primary_latency_ms,
        primary_stt_provider=stt.primary_provider,
        fallback_stt_status=stt.fallback_status,
        fallback_stt_ms=stt.fallback_latency_ms,
        fallback_stt_provider=stt.fallback_provider,
        stt_budget_remaining_ms=stt.budget_remaining_ms,
    )


def _fallback_stt_timeout_seconds(available_seconds: float) -> float:
    """Return the bounded Hermes fallback STT lane timeout for one explicit turn."""
    cap_seconds = _bounded_seconds(
        "OTOXAN_HERMES_STT_FALLBACK_TIMEOUT_SECONDS",
        SPRINT4_HERMES_FALLBACK_BUDGET_SECONDS,
        0.0,
        5.0,
    )
    return max(0.0, min(available_seconds, cap_seconds))


def _transcribe_with_hermes_stt(pcm: bytes) -> SttResult:
    provider = os.environ.get("OTOXAN_STT_PROVIDER", STT_PROVIDER_DEFAULT).strip().lower() or STT_PROVIDER_DEFAULT
    total_started = time.monotonic()
    total_budget = _bounded_seconds("OTOXAN_STT_TOTAL_BUDGET_SECONDS", SPRINT4_STT_TOTAL_BUDGET_SECONDS, 0.5, 20.0)
    if provider not in STT_PROVIDER_ALIASES:
        return SttResult("", "unsupported-provider", 0, provider, budget_remaining_ms=int(total_budget * 1000))

    if provider in {"moonshine", "moonshine-command", "local-command"}:
        moonshine = _transcribe_with_moonshine_command(pcm)
        elapsed = time.monotonic() - total_started
        remaining = max(0.0, total_budget - elapsed)
        if moonshine.transcript:
            return SttResult(
                moonshine.transcript,
                moonshine.status,
                _elapsed_ms(total_started),
                moonshine.provider,
                primary_status=moonshine.status,
                primary_latency_ms=moonshine.latency_ms,
                primary_provider=moonshine.provider,
                budget_remaining_ms=int(remaining * 1000),
            )
        fallback_min = _bounded_seconds("OTOXAN_STT_FALLBACK_MIN_SECONDS", SPRINT4_STT_FALLBACK_MIN_SECONDS, 0.0, 5.0)
        if remaining < fallback_min:
            return SttResult(
                "",
                f"primary-{moonshine.status}-budget-exhausted",
                _elapsed_ms(total_started),
                moonshine.provider,
                primary_status=moonshine.status,
                primary_latency_ms=moonshine.latency_ms,
                primary_provider=moonshine.provider,
                budget_remaining_ms=int(remaining * 1000),
            )
        fallback_timeout = _fallback_stt_timeout_seconds(remaining)
        if fallback_timeout <= 0:
            return SttResult(
                "",
                f"primary-{moonshine.status}-fallback-budget-exhausted",
                _elapsed_ms(total_started),
                moonshine.provider,
                primary_status=moonshine.status,
                primary_latency_ms=moonshine.latency_ms,
                primary_provider=moonshine.provider,
                fallback_status="skipped-budget-exhausted",
                fallback_latency_ms=0,
                fallback_provider="hermes-stt",
                budget_remaining_ms=int(remaining * 1000),
            )
        fallback = _transcribe_with_hermes_stt_fallback(pcm, timeout_seconds=fallback_timeout)
        return SttResult(
            fallback.transcript,
            fallback.status,
            _elapsed_ms(total_started),
            fallback.provider,
            primary_status=moonshine.status,
            primary_latency_ms=moonshine.latency_ms,
            primary_provider=moonshine.provider,
            fallback_status=fallback.status,
            fallback_latency_ms=fallback.latency_ms,
            fallback_provider=fallback.provider,
            budget_remaining_ms=max(0, int((total_budget - (time.monotonic() - total_started)) * 1000)),
        )

    fallback = _transcribe_with_hermes_stt_fallback(pcm, timeout_seconds=total_budget)
    return SttResult(
        fallback.transcript,
        fallback.status,
        fallback.latency_ms,
        fallback.provider,
        primary_status=fallback.status,
        primary_latency_ms=fallback.latency_ms,
        primary_provider=fallback.provider,
        budget_remaining_ms=max(0, int((total_budget - (time.monotonic() - total_started)) * 1000)),
    )


def _transcribe_with_hermes_stt_fallback(pcm: bytes, timeout_seconds: float | None = None) -> SttResult:
    mode = os.environ.get("OTOXAN_STT_MODE", "inprocess").strip().lower()
    if mode != "subprocess":
        inprocess = _transcribe_with_inprocess_stt(pcm)
        if inprocess.status != "inprocess-unavailable":
            return inprocess
    return _transcribe_with_subprocess_stt(pcm, timeout_seconds=timeout_seconds)


def _transcribe_with_moonshine_command(pcm: bytes) -> SttResult:
    started = time.monotonic()
    command_template = os.environ.get("OTOXAN_MOONSHINE_STT_COMMAND", "").strip()
    if not command_template:
        return SttResult("", "not-configured", _elapsed_ms(started), "moonshine-stt")
    timeout = _bounded_seconds("OTOXAN_MOONSHINE_STT_TIMEOUT_SECONDS", SPRINT4_MOONSHINE_PRIMARY_BUDGET_SECONDS, 0.2, 8.0)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as wav_file:
        input_path = wav_file.name
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as out_file:
        output_path = out_file.name
    try:
        _write_pcm16_wav(input_path, pcm)
        command = command_template.format(input=shlex.quote(input_path), output=shlex.quote(output_path))
        try:
            result = subprocess.run(
                shlex.split(command),
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                timeout=timeout,
                check=False,
            )
        except FileNotFoundError:
            return SttResult("", "file-not-found", _elapsed_ms(started), "moonshine-stt")
        except subprocess.TimeoutExpired:
            return SttResult("", "timeout", _elapsed_ms(started), "moonshine-stt")
        latency_ms = _elapsed_ms(started)
        output = Path(output_path)
        text = ""
        if output.exists() and output.stat().st_size > 0:
            text = _parse_stt_command_output(output.read_text(errors="replace"))
        if not text:
            text = _parse_stt_command_output((result.stdout or b"").decode("utf-8", errors="replace"))
        if result.returncode != 0 and not text:
            return SttResult("", "command-error", latency_ms, "moonshine-stt")
        return SttResult(text, "success" if text else "empty", latency_ms, "moonshine-stt")
    except Exception:
        return SttResult("", "error", _elapsed_ms(started), "moonshine-stt")
    finally:
        for path in (input_path, output_path):
            try:
                Path(path).unlink(missing_ok=True)
            except Exception:
                pass


def _parse_stt_command_output(raw: str) -> str:
    text = raw.strip()
    if not text:
        return ""
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return _clean(text.splitlines()[-1] if text.splitlines() else text, "")
    if isinstance(data, Mapping):
        if data.get("success") is False:
            return ""
        for key in ("transcript", "text", "utterance"):
            transcript = _clean(data.get(key), "")
            if transcript:
                return transcript
    return ""


def _transcribe_with_inprocess_stt(pcm: bytes) -> SttResult:
    started = time.monotonic()
    try:
        transcribe_audio = _load_fast_local_stt()
    except Exception:
        try:
            transcribe_audio = _load_inprocess_stt()
        except Exception:
            return SttResult("", "inprocess-unavailable", _elapsed_ms(started))
    with _STT_LOCK:
        try:
            with tempfile.NamedTemporaryFile(suffix=".wav") as wav_file:
                _write_pcm16_wav(wav_file.name, pcm)
                data = transcribe_audio(wav_file.name)
        except Exception:
            return SttResult("", "inprocess-error", _elapsed_ms(started))
    latency_ms = _elapsed_ms(started)
    if not data.get("success"):
        return SttResult("", "stt-failed", latency_ms)
    transcript = _clean(data.get("transcript"), "")
    return SttResult(transcript, "success" if transcript else "empty", latency_ms)


def _load_fast_local_stt() -> Callable[[str], Mapping[str, Any]]:
    global _STT_FAST_LOCAL_TRANSCRIBE
    if _STT_FAST_LOCAL_TRANSCRIBE is not None:
        return _STT_FAST_LOCAL_TRANSCRIBE
    _load_inprocess_stt()
    import tools.transcription_tools as transcription_tools  # type: ignore[import-not-found]
    stt_config = transcription_tools._load_stt_config()
    if transcription_tools._get_provider(stt_config) != "local":
        raise RuntimeError("fast local STT only applies to provider=local")
    local_cfg = stt_config.get("local", {})
    model_name = transcription_tools._normalize_local_model(
        local_cfg.get("model", transcription_tools.DEFAULT_LOCAL_MODEL)
    )
    model = transcription_tools._load_local_whisper_model(model_name)
    language = local_cfg.get("language") or os.getenv("HERMES_LOCAL_STT_LANGUAGE") or "en"

    def transcribe_fast_local(file_path: str) -> Mapping[str, Any]:
        segments, _info = model.transcribe(
            file_path,
            beam_size=1,
            language=language,
            condition_on_previous_text=False,
        )
        transcript = " ".join(segment.text.strip() for segment in segments).strip()
        return {"success": True, "transcript": transcript, "provider": "local-fast"}

    _STT_FAST_LOCAL_TRANSCRIBE = transcribe_fast_local
    return transcribe_fast_local


def _load_inprocess_stt() -> Callable[[str], Mapping[str, Any]]:
    global _STT_LOAD_ERROR, _STT_TRANSCRIBE_AUDIO
    if _STT_TRANSCRIBE_AUDIO is not None:
        return _STT_TRANSCRIBE_AUDIO
    if _STT_LOAD_ERROR:
        raise RuntimeError(_STT_LOAD_ERROR)
    try:
        hermes_agent_home = Path(os.environ.get("OTOXAN_HERMES_AGENT_HOME", HERMES_AGENT_HOME_DEFAULT))
        hermes_home = os.environ.get("HERMES_HOME") or os.environ.get("OTOXAN_XANDER_HERMES_HOME", XANDER_HERMES_HOME_DEFAULT)
        if str(hermes_agent_home) not in sys.path:
            sys.path.insert(0, str(hermes_agent_home))
        from hermes_cli.env_loader import load_hermes_dotenv  # type: ignore[import-not-found]
        from tools.transcription_tools import transcribe_audio  # type: ignore[import-not-found]
        load_hermes_dotenv(
            hermes_home=hermes_home,
            project_env=str(hermes_agent_home / ".env"),
        )
        _STT_TRANSCRIBE_AUDIO = transcribe_audio
        return transcribe_audio
    except Exception as exc:
        _STT_LOAD_ERROR = exc.__class__.__name__
        raise


def _transcribe_with_subprocess_stt(pcm: bytes, timeout_seconds: float | None = None) -> SttResult:
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
try:
    import tools.transcription_tools as stt_tools
    cfg = stt_tools._load_stt_config()
    if stt_tools._get_provider(cfg) == "local":
        local_cfg = cfg.get("local", {})
        model_name = stt_tools._normalize_local_model(local_cfg.get("model", stt_tools.DEFAULT_LOCAL_MODEL))
        model = stt_tools._load_local_whisper_model(model_name)
        language = local_cfg.get("language") or os.getenv("HERMES_LOCAL_STT_LANGUAGE") or "en"
        segments, _info = model.transcribe(
            sys.argv[1],
            beam_size=1,
            language=language,
            condition_on_previous_text=False,
        )
        transcript = " ".join(segment.text.strip() for segment in segments).strip()
        print(json.dumps({"success": True, "transcript": transcript, "provider": "local-fast"}))
    else:
        print(json.dumps(transcribe_audio(sys.argv[1])))
except Exception:
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
                timeout=max(0.2, timeout_seconds if timeout_seconds is not None else float(os.environ.get("OTOXAN_STT_TIMEOUT_SECONDS", "45"))),
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


def _synthesize_optional_tts(text: str, timing: dict[str, Any]) -> bytes:
    started = time.monotonic()
    provider = os.environ.get("OTOXAN_TTS_PROVIDER", TTS_PROVIDER_DEFAULT).strip().lower() or TTS_PROVIDER_DEFAULT
    if provider not in TTS_PROVIDER_ALIASES:
        timing["ttsProvider"] = provider
        timing["ttsStatus"] = "unsupported"
        timing["ttsLatencyMs"] = _elapsed_ms(started)
        return b""
    if provider in {"android", "none", "off"}:
        # Android TextToSpeech remains the default playback fallback until a local voice is configured.
        timing["ttsProvider"] = provider
        timing["ttsStatus"] = "android-fallback"
        timing["ttsLatencyMs"] = _elapsed_ms(started)
        return b""
    try:
        pcm = _synthesize_with_kokoro_command(text)
        timing["ttsProvider"] = provider
        timing["ttsStatus"] = "success" if pcm else "not-configured"
        timing["ttsLatencyMs"] = _elapsed_ms(started)
        return pcm
    except Exception:
        # TTS must not break the conversation turn; empty PCM preserves Android TTS fallback.
        timing["ttsProvider"] = provider
        timing["ttsStatus"] = "error"
        timing["ttsLatencyMs"] = _elapsed_ms(started)
        return b""


def _synthesize_with_kokoro_command(text: str) -> bytes:
    command_template = os.environ.get("OTOXAN_KOKORO_TTS_COMMAND", "").strip()
    if not command_template:
        return b""
    timeout = float(os.environ.get("OTOXAN_TTS_TIMEOUT_SECONDS", "20"))
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as out_file:
        output_path = out_file.name
    try:
        command = command_template.format(text=shlex.quote(text), output=shlex.quote(output_path))
        result = subprocess.run(
            shlex.split(command),
            input=text,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=timeout,
            check=False,
        )
        if result.returncode != 0:
            return b""
        output = Path(output_path)
        if output.exists() and output.stat().st_size > 0:
            return _read_tts_output(output)
        return bytes(result.stdout or b"")
    finally:
        try:
            Path(output_path).unlink(missing_ok=True)
        except Exception:
            pass


def _read_tts_output(path: Path) -> bytes:
    data = path.read_bytes()
    if data.startswith(b"RIFF"):
        with wave.open(str(path), "rb") as wav:
            if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getframerate() != SAMPLE_RATE:
                return b""
            return wav.readframes(wav.getnframes())
    return data


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


def _ask_xander_mobile_fast_with_deadline(transcript: str, route: RouteSummary) -> tuple[str, str, bool, str | None]:
    hard_timeout = _mobile_fast_hard_timeout_seconds()

    result_queue: queue.Queue[tuple[str, str, str | None]] = queue.Queue(maxsize=1)

    def worker() -> None:
        try:
            result_queue.put(("success", _ask_xander_mobile_fast(transcript, route), None))
        except Exception as exc:
            result_queue.put(("error", "", _safe_failure_reason(exc)))

    thread = threading.Thread(target=worker, name="otoxan-mobile-fast-provider", daemon=True)
    thread.start()
    try:
        status, text, failure_reason = result_queue.get(timeout=hard_timeout)
    except queue.Empty:
        return "", "timeout", True, f"deadline-timeout-after-{hard_timeout:g}s"
    return text, status, False, failure_reason


def _ask_xander_session_with_deadline(transcript: str, route: RouteSummary) -> tuple[str, str, bool, str | None]:
    hard_timeout = _mobile_fast_session_fallback_timeout_seconds()
    result_queue: queue.Queue[tuple[str, str, str | None]] = queue.Queue(maxsize=1)

    def worker() -> None:
        try:
            result_queue.put(("success", _ask_xander_session(transcript, route), None))
        except Exception as exc:
            result_queue.put(("error", "", _safe_failure_reason(exc)))

    thread = threading.Thread(target=worker, name="otoxan-session-fallback-provider", daemon=True)
    thread.start()
    try:
        status, text, failure_reason = result_queue.get(timeout=hard_timeout)
    except queue.Empty:
        return "", "timeout", True, f"deadline-timeout-after-{hard_timeout:g}s"
    return text, status, False, failure_reason


def _safe_failure_reason(exc: BaseException) -> str:
    """Return bounded, secret-free failure evidence for backend response/metrics."""
    reason = str(exc).replace("\x00", " ").strip() or exc.__class__.__name__
    redacted_tokens = ("api_key", "authorization", "bearer ", "token", "secret", "password")
    if any(token in reason.lower() for token in redacted_tokens):
        return f"{exc.__class__.__name__}: redacted-sensitive-detail"
    return reason[:240]


def _mobile_fast_session_fallback_enabled() -> bool:
    raw = os.environ.get("OTOXAN_MOBILE_FAST_SESSION_FALLBACK", "1").strip().lower()
    return raw not in {"0", "false", "no", "off", "disabled"}


def _mobile_fast_session_fallback_timeout_seconds() -> float:
    return _bounded_seconds(
        "OTOXAN_MOBILE_FALLBACK_HARD_TIMEOUT_SECONDS",
        2.5,
        0.5,
        15.0,
    )


def _mobile_fast_provider_name() -> str:
    return os.environ.get("OTOXAN_MOBILE_FAST_PROVIDER", XANDER_MOBILE_FAST_PROVIDER_DEFAULT).strip() or XANDER_MOBILE_FAST_PROVIDER_DEFAULT


def _mobile_fast_model_name(config: Mapping[str, Any] | None = None, provider_name: str | None = None) -> str:
    env_model = os.environ.get("OTOXAN_MOBILE_FAST_MODEL", "").strip()
    if env_model:
        return env_model
    provider_name = provider_name or _mobile_fast_provider_name()
    try:
        provider = _configured_provider(config or _load_xander_config(), provider_name)
    except Exception:
        return ""
    return str(provider.get("model", "")).strip()


def _mobile_fast_request_timeout_seconds() -> float:
    return _bounded_seconds("OTOXAN_MOBILE_FAST_TIMEOUT_SECONDS", XANDER_FAST_TIMEOUT_SECONDS, 0.5, 30.0)


def _mobile_fast_hard_timeout_seconds() -> float:
    return _bounded_seconds("OTOXAN_MOBILE_FAST_HARD_TIMEOUT_SECONDS", XANDER_FAST_HARD_TIMEOUT_SECONDS, 0.5, 20.0)


def _mobile_fast_runtime_descriptor() -> dict[str, Any]:
    provider_name = _mobile_fast_provider_name()
    return {
        "mobileFastProvider": provider_name,
        "mobileFastModel": _mobile_fast_model_name(provider_name=provider_name),
        "mobileFastTimeoutSeconds": _mobile_fast_request_timeout_seconds(),
        "mobileFastHardTimeoutSeconds": _mobile_fast_hard_timeout_seconds(),
        "mobileFastSessionFallbackEnabled": _mobile_fast_session_fallback_enabled(),
        "mobileFastSessionFallbackHardTimeoutSeconds": _mobile_fast_session_fallback_timeout_seconds(),
    }


def mobile_fast_runtime_contract() -> dict[str, Any]:
    """Describe the bounded MiniMax/OpenAI-compatible runtime lane.

    The descriptor is intentionally secret-free and suitable for response/readback
    telemetry. It locks the wearable contract: STT must produce real speech before
    the mobile-fast model is called, the provider call has a hard deadline, MiniMax
    reasoning is split out of spoken content, and fallback output must stay short.
    """
    return {
        "name": MOBILE_FAST_RUNTIME_CONTRACT_NAME,
        "version": MOBILE_FAST_RUNTIME_CONTRACT_VERSION,
        "providerMode": MOBILE_FAST_PROVIDER,
        "providerEnv": "OTOXAN_MOBILE_FAST_PROVIDER",
        "providerDefault": XANDER_MOBILE_FAST_PROVIDER_DEFAULT,
        "modelEnv": "OTOXAN_MOBILE_FAST_MODEL",
        "requestTimeoutEnv": "OTOXAN_MOBILE_FAST_TIMEOUT_SECONDS",
        "hardTimeoutEnv": "OTOXAN_MOBILE_FAST_HARD_TIMEOUT_SECONDS",
        "defaultRequestTimeoutSeconds": XANDER_FAST_TIMEOUT_SECONDS,
        "defaultHardTimeoutSeconds": XANDER_FAST_HARD_TIMEOUT_SECONDS,
        "maxSpokenWords": XANDER_FAST_MAX_WORDS,
        "maxSpokenChars": XANDER_SPOKEN_MAX_CHARS,
        "requestShape": {
            "endpointSuffix": "/chat/completions",
            "apiCompatibility": "openai_chat_completions",
            "maxTokensEnv": "OTOXAN_MOBILE_FAST_MAX_TOKENS",
            "defaultMaxTokens": 96,
            "temperatureEnv": "OTOXAN_MOBILE_FAST_TEMPERATURE",
            "defaultTemperature": 0.2,
            "reasoningSplit": True,
        },
        "adapterParser": minimax_m3_adapter_descriptor(),
        "callGate": "only_after_stt_success_or_debug_transcript_not_route_evidence_fallback",
        "fallbackPolicy": {
            "sessionFallbackEnv": "OTOXAN_MOBILE_FAST_SESSION_FALLBACK",
            "defaultSessionFallbackEnabled": True,
            "sessionFallbackHardTimeoutEnv": "OTOXAN_MOBILE_FALLBACK_HARD_TIMEOUT_SECONDS",
            "defaultSessionFallbackHardTimeoutSeconds": 2.5,
            "sessionFallbackDisableValues": ["0", "false", "no", "off", "disabled"],
            "recoveryOrder": ["mobile-fast", "bounded-xander-session", "degraded-spoken-response"],
            "providerFailureSpokenResponse": MODEL_DEGRADED_RESPONSE,
            "emptySttSpokenResponse": NO_TRANSCRIPT_DEGRADED_RESPONSE,
        },
        "readbackFields": [
            "provider",
            "mobileFastProvider",
            "mobileFastModel",
            "mobileFastTimeoutSeconds",
            "mobileFastHardTimeoutSeconds",
            "mobileFastSessionFallbackEnabled",
            "mobileFastSessionFallbackHardTimeoutSeconds",
            "xanderFastMs",
            "xanderFastStatus",
            "xanderFastTimedOut",
            "mobileFastFailureReason",
            "xanderFallbackSessionStatus",
            "xanderFallbackSkipped",
            "xanderFallbackFailureReason",
        ],
        "privacy": {
            "secretMaterialInTelemetry": False,
            "rawAudioPersisted": False,
            "explicitSessionOnly": True,
        },
        "evidenceClass": "runtime_contract_readback_not_hardware_proof",
    }


def minimax_m3_adapter_descriptor() -> dict[str, Any]:
    """Describe the named MiniMax M3 response parser without secrets.

    MiniMax M3 can return OpenAI-compatible chat responses where reasoning is
    split away from ``message.content``. For glasses audio, only spoken content
    may be read aloud; reasoning fields and reasoning markup are evidence for
    diagnostics/fallback, never speech material.
    """
    return {
        "name": MINIMAX_M3_ADAPTER_NAME,
        "version": MINIMAX_M3_ADAPTER_VERSION,
        "targetModel": "MiniMax-M3",
        "apiCompatibility": "openai_chat_completions",
        "spokenContentPath": "choices[0].message.content",
        "reasoningEvidenceFields": [
            "choices[0].message.reasoning_content",
            "choices[0].message.reasoningContent",
            "choices[0].message.reasoning",
            "choices[0].message.reasoning_details",
            "choices[0].message.content:<think>...</think>",
        ],
        "emptyContentEvidence": True,
        "reasoningPolicy": "reasoning_never_spoken_empty_content_triggers_fallback",
        "evidenceClass": "adapter_parser_readback_not_hardware_proof",
    }


def _ask_xander_mobile_fast(transcript: str, route: RouteSummary) -> str:
    provider_name = _mobile_fast_provider_name()
    config = _load_xander_config()
    provider = _configured_provider(config, provider_name)
    base_url = str(provider.get("base_url", "")).rstrip("/")
    api_key = str(provider.get("api_key", "")).strip()
    model = _mobile_fast_model_name(config=config, provider_name=provider_name)
    if not base_url or not api_key or not model:
        raise VoiceTurnError(f"mobile-fast provider {provider_name!r} is missing base_url/api_key/model", 502)

    timeout = _mobile_fast_request_timeout_seconds()

    body = {
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are Xander, Otoxan controller builder and fleet operator, speaking through Ray-Ban Meta glasses. "
                    "Stanza: Otoxan is the control plane; Hermes/Frankenstein is source reality until replaced with proof; "
                    "Silas owns the live Frankenstein/Hermes runtime until ownership is explicitly transferred; "
                    "deployed/client agents request capabilities and do not inherit controller authority. "
                    f"Answer the operator's current transcript in one coherent spoken reply, max {XANDER_FAST_MAX_WORDS} words. "
                    "Use enough words to be clear; do not answer with fragments. "
                    "Do not infer hidden context, narrate status, or continue a prior topic unless the transcript asks for it. "
                    "If the transcript is unclear, ask the operator to repeat it. "
                    "Builder-first, concrete, no filler, no apologies, no reasoning, no XML tags."
                ),
            },
            {
                "role": "user",
                "content": (
                    "Route evidence: "
                    f"input={route.input_name} ({route.input_type}); output={route.output_name} ({route.output_type}).\n"
                    f"Operator transcript: <<< {transcript} >>>\n"
                    "Return only the spoken reply."
                ),
            },
        ],
        "max_tokens": int(os.environ.get("OTOXAN_MOBILE_FAST_MAX_TOKENS", "96")),
        "temperature": _mobile_fast_temperature(provider_name),
        # MiniMax OpenAI-compatible API: split thinking out of message.content.
        "reasoning_split": True,
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

    text = _parse_minimax_m3_chat_completion(data)
    return _shape_mobile_spoken_response(text, max_words=XANDER_FAST_MAX_WORDS)


def _parse_minimax_m3_chat_completion(data: Mapping[str, Any]) -> str:
    """Extract spoken MiniMax M3 content and preserve reasoning/empty evidence.

    The return value is intentionally only the speakable text. Evidence is kept
    in the descriptor and in sanitized error text so the mobile-fast lane can
    fall back without leaking reasoning into Ray-Ban audio or telemetry.
    """
    try:
        message = data["choices"][0]["message"]  # type: ignore[index]
    except (KeyError, IndexError, TypeError) as exc:
        raise VoiceTurnError(f"{MINIMAX_M3_ADAPTER_NAME} returned an unexpected response shape", 502) from exc
    if not isinstance(message, Mapping):
        raise VoiceTurnError(f"{MINIMAX_M3_ADAPTER_NAME} returned an unexpected response shape", 502)

    raw_content = _chat_message_content_text(message.get("content", ""))
    reasoning_evidence = _minimax_m3_reasoning_evidence(message, raw_content)
    text = _strip_reasoning_markup(raw_content)
    if not text.strip():
        fields = ",".join(reasoning_evidence["fields"]) or "none"
        raise VoiceTurnError(
            f"{MINIMAX_M3_ADAPTER_NAME} returned no spoken content "
            f"(contentEmpty={str(reasoning_evidence['contentEmpty']).lower()}, "
            f"reasoningPresent={str(reasoning_evidence['reasoningPresent']).lower()}, "
            f"reasoningFields={fields})",
            502,
        )
    return text


def _chat_message_content_text(content: Any) -> str:
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, Sequence) and not isinstance(content, (str, bytes, bytearray)):
        parts: list[str] = []
        for item in content:
            if isinstance(item, Mapping):
                item_text = item.get("text")
                if isinstance(item_text, str):
                    parts.append(item_text)
            elif isinstance(item, str):
                parts.append(item)
        return "\n".join(parts)
    return str(content)


def _minimax_m3_reasoning_evidence(message: Mapping[str, Any], raw_content: str) -> dict[str, Any]:
    fields: list[str] = []
    for key in ("reasoning_content", "reasoningContent", "reasoning", "reasoning_details"):
        value = message.get(key)
        if value:
            fields.append(key)
    lower = raw_content.lower()
    if "<think>" in lower or "</think>" in lower:
        fields.append("content_think_markup")
    return {
        "adapter": MINIMAX_M3_ADAPTER_NAME,
        "contentEmpty": not raw_content.strip(),
        "reasoningPresent": bool(fields),
        "fields": fields,
    }


def _mobile_fast_degraded_spoken_response(transcript: str) -> str:
    return _shape_mobile_spoken_response(
        MODEL_DEGRADED_RESPONSE if transcript.strip() else NO_TRANSCRIPT_DEGRADED_RESPONSE,
        max_words=XANDER_FAST_MAX_WORDS,
    )


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
        "Operator said:\n"
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
    sentence = sentence.split(";", 1)[0].strip() or sentence
    words = sentence.split()
    if len(words) > max_words:
        sentence = " ".join(words[:max_words]).rstrip(",;:-") + "."
    if len(sentence) > XANDER_SPOKEN_MAX_CHARS:
        sentence = sentence[:XANDER_SPOKEN_MAX_CHARS].rsplit(" ", 1)[0].rstrip(",;:-") + "."
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
        stt_provider="not-run",
        timing={
            "transcriptTotalMs": 0,
            "sttLatencyMs": None,
            "sttProvider": "not-run",
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

    sanitized_payload = _sanitize_metrics_payload(payload)
    record = {
        "recordId": str(uuid.uuid4()),
        "receivedAtMs": int(time.time() * 1000),
        "remoteAddr": remote_addr,
        "payload": sanitized_payload,
        "timingSummary": _metrics_timing_summary(sanitized_payload),
    }
    path = Path(os.environ.get("OTOXAN_VOICE_METRICS_JSONL", METRICS_JSONL_DEFAULT))
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(record, sort_keys=True) + "\n")
    _safe_log(
        "voice-turn-metrics ok "
        f"turnId={turn_id} "
        f"success={turn.get('success')} "
        f"stage={turn.get('stage')} "
        f"totalMs={_nested_get(payload, 'totals', 'turnTotalMs')} "
        f"backendMs={_nested_get(payload, 'backend', 'roundTripMs')} "
        f"ttfaMs={_nested_get(payload, 'perceivedLatency', 'ttfaMs')} "
        f"postCaptureAckDelayMs={_nested_get(sanitized_payload, 'perceivedLatency', 'postCaptureAckDelayMs')} "
        f"ttfaCaptureMs={_nested_get(payload, 'perceivedLatency', 'breakdown', 'captureReadMs')} "
        f"playback={_nested_get(payload, 'playback', 'kind')} "
        f"path={path}"
    )
    return {"ok": True, "recordId": record["recordId"], "metricsPath": str(path)}


def latest_voice_turn_metrics() -> dict[str, Any]:
    recent = recent_voice_turn_metrics(limit=1)
    latest = recent["records"][0] if recent["records"] else None
    return {
        "ok": True,
        "count": recent["count"],
        "latest": latest,
        "historySummary": recent.get("historySummary"),
        "metricsPath": recent["metricsPath"],
        "corruptLineCount": recent["corruptLineCount"],
    }


def recent_voice_turn_metrics(limit: int = 20) -> dict[str, Any]:
    path = Path(os.environ.get("OTOXAN_VOICE_METRICS_JSONL", METRICS_JSONL_DEFAULT))
    bounded_limit = max(1, min(int(limit or 20), 100))
    if not path.exists():
        return {
            "ok": True,
            "count": 0,
            "records": [],
            "historySummary": _metrics_history_summary([]),
            "metricsPath": str(path),
            "corruptLineCount": 0,
            "privacy": "sanitized recursively: no raw audio, transcript text, assistant text, or base64 PCM fields",
        }
    records: list[dict[str, Any]] = []
    count = 0
    corrupt = 0
    with path.open("r", encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            count += 1
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                corrupt += 1
                continue
            _attach_metrics_timing_summary(record)
            _attach_metrics_record_summary(record)
            records.append(record)
            if len(records) > bounded_limit:
                records.pop(0)
    records.reverse()
    return {
        "ok": True,
        "count": count,
        "records": records,
        "historySummary": _metrics_history_summary(records),
        "metricsPath": str(path),
        "corruptLineCount": corrupt,
        "privacy": "sanitized recursively: no raw audio, transcript text, assistant text, or base64 PCM fields",
    }


def recent_hardware_sweep_summaries(limit: int = 20) -> dict[str, Any]:
    """Return run-sheet-shaped recent hardware evidence without raw audio or transcript text."""
    recent = recent_voice_turn_metrics(limit=limit)
    summaries = [_hardware_sweep_summary(record) for record in recent.get("records", [])]
    return {
        "ok": recent.get("ok", True),
        "count": recent.get("count", 0),
        "summaries": summaries,
        "historySummary": _hardware_sweep_history_summary(summaries),
        "realtimeVadComparison": _realtime_vad_sweep_comparison(summaries),
        "metricsPath": recent.get("metricsPath"),
        "corruptLineCount": recent.get("corruptLineCount", 0),
        "privacy": "summary-only: no pcm16Mono16kBase64, transcript text, assistant text, or raw audio is persisted or returned",
    }


def _hardware_sweep_summary(record: Mapping[str, Any]) -> dict[str, Any]:
    payload = _mapping(record.get("payload"))
    turn = _mapping(payload.get("turn"))
    route = _mapping(payload.get("route"))
    capture = _mapping(payload.get("capture"))
    backend = _mapping(payload.get("backend"))
    playback = _mapping(payload.get("playback"))
    perceived_latency = _mapping(payload.get("perceivedLatency"))
    verdict = _mapping(payload.get("verdict"))
    totals = _mapping(payload.get("totals"))
    timing_summary = _mapping(record.get("timingSummary"))
    scenario = _nested_get(payload, "sweep", "scenario") or _nested_get(payload, "hardwareSweep", "scenario") or "unknown"
    ttfa_ms = _first_present(_nested_get(perceived_latency, "ttfaMs"), _nested_get(timing_summary, "ttfaMs"))
    ack_ms = _first_present(_nested_get(perceived_latency, "postCaptureAckDelayMs"), _nested_get(timing_summary, "postCaptureAckDelayMs"))
    backend_ms = _first_present(_nested_get(backend, "roundTripMs"), _nested_get(timing_summary, "backendRoundTripMs"))
    total_ms = _first_present(_nested_get(totals, "turnTotalMs"), _nested_get(timing_summary, "turnTotalMs"))
    provider = _nested_get(verdict, "provider")
    turn_outcome_status = _nested_get(verdict, "turnOutcomeStatus")
    assistant_response_source = _nested_get(verdict, "assistantResponseSource")
    pass1_ready = _nested_get(verdict, "pass1Ready")
    pass1_status = _nested_get(verdict, "pass1Status")
    peak_amplitude = _first_present(_nested_get(capture, "peakAmplitude"), _nested_get(backend, "peak"), _nested_get(backend, "audioPeak"))
    rms = _first_present(_nested_get(capture, "rms"), _nested_get(backend, "rms"), _nested_get(backend, "audioRms"))
    summary = {
        "recordId": record.get("recordId"),
        "receivedAtMs": record.get("receivedAtMs"),
        "runId": _nested_get(turn, "turnId") or record.get("recordId") or "unknown",
        "scenario": scenario,
        "backendProviderObserved": provider,
        "turnOutcomeStatus": turn_outcome_status,
        "assistantResponseSource": assistant_response_source,
        "turnOutcomeEvidenceClass": _nested_get(verdict, "turnOutcomeEvidenceClass"),
        "inputName": _nested_get(route, "inputName"),
        "inputType": _nested_get(route, "inputType"),
        "outputName": _nested_get(route, "outputName"),
        "outputType": _nested_get(route, "outputType"),
        "wearableRouteActive": _nested_get(route, "wearableActiveAtCapture"),
        "captureDurationMs": _nested_get(capture, "actualMs"),
        "capturedBytes": _nested_get(capture, "capturedBytes"),
        "backendBytesReceived": _nested_get(backend, "bytesReceived"),
        "expectedBytesForDuration": _nested_get(capture, "expectedBytes"),
        "audioFormat": _first_present(_nested_get(capture, "audioFormat"), _nested_get(backend, "audioFormat"), SUPPORTED_FORMAT),
        "backendPeak": peak_amplitude,
        "backendRms": rms,
        "clientStopReason": _nested_get(capture, "stopReason"),
        "transcriptSource": _nested_get(verdict, "transcriptSource"),
        "sttProvider": _nested_get(verdict, "sttProvider"),
        "sttStatus": _nested_get(verdict, "sttStatus"),
        "sttLatencyMs": _nested_get(backend, "sttLatencyMs"),
        "mobileFastFailureReason": _nested_get(backend, "mobileFastFailureReason"),
        "xanderFastTimedOut": _nested_get(backend, "xanderFastTimedOut"),
        "xanderFallbackSessionStatus": _nested_get(backend, "xanderFallbackSessionStatus"),
        "xanderFallbackSkipped": _nested_get(backend, "xanderFallbackSkipped"),
        "xanderFallbackTimedOut": _nested_get(backend, "xanderFallbackTimedOut"),
        "xanderFallbackFailureReason": _nested_get(backend, "xanderFallbackFailureReason"),
        "pass1Ready": pass1_ready,
        "pass1Status": pass1_status,
        "ttfaMs": ttfa_ms,
        "postCaptureAckDelayMs": ack_ms,
        "backendRoundTripMs": backend_ms,
        "turnTotalMs": total_ms,
        "canonicalTimingTargetResult": _canonical_timing_target_result(ttfa_ms=ttfa_ms, ack_ms=ack_ms, backend_ms=backend_ms, total_ms=total_ms),
        "playbackKind": _nested_get(playback, "kind"),
        "assistantTextLength": _nested_get(verdict, "assistantTextLength"),
        "transcriptLength": _nested_get(verdict, "transcriptLength"),
        "runDisposition": _hardware_sweep_disposition(payload, scenario=scenario, provider=provider, pass1_ready=pass1_ready, pass1_status=pass1_status),
        "realtimeVadDiagnostic": _realtime_vad_diagnostic(scenario=scenario, peak=peak_amplitude),
        "privacy": "summary-only; raw audio and spoken text omitted",
    }
    return {key: value for key, value in summary.items() if value is not None}


def _realtime_vad_diagnostic(*, scenario: Any, peak: Any) -> dict[str, Any]:
    """Compare the realtime energy VAD threshold against one sweep row without making it authoritative."""
    peak_value = _int_or_none(peak)
    detected = None if peak_value is None else peak_value >= REALTIME_VAD_DIAGNOSTIC_PEAK_THRESHOLD
    scenario_text = str(scenario or "unknown").lower()
    reject_scenario = scenario_text in {"silence", "clipped", "clipped/too-short", "too-short"}
    if detected is None:
        comparison = "unknown-no-peak"
    elif reject_scenario and detected:
        comparison = "diagnostic-would-trigger-on-reject-scenario"
    elif reject_scenario:
        comparison = "diagnostic-stays-quiet-on-reject-scenario"
    elif detected:
        comparison = "diagnostic-detects-speech-threshold"
    else:
        comparison = "diagnostic-misses-speech-threshold"
    return {
        "diagnosticOnly": True,
        "provider": REALTIME_VAD_DIAGNOSTIC_PROVIDER,
        "peakThreshold": REALTIME_VAD_DIAGNOSTIC_PEAK_THRESHOLD,
        "endSilenceChunks": REALTIME_VAD_DIAGNOSTIC_END_SILENCE_CHUNKS,
        "observedPeak": peak_value,
        "wouldDetectSpeech": detected,
        "comparison": comparison,
        "policy": "compare against sweep evidence only; do not use this VAD result as default commit policy or hardware proof",
    }


def _realtime_vad_sweep_comparison(summaries: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    diagnostics = [_mapping(summary.get("realtimeVadDiagnostic")) for summary in summaries]
    known = [diagnostic for diagnostic in diagnostics if diagnostic.get("wouldDetectSpeech") is not None]
    trigger_count = sum(1 for diagnostic in known if diagnostic.get("wouldDetectSpeech") is True)
    quiet_count = sum(1 for diagnostic in known if diagnostic.get("wouldDetectSpeech") is False)
    reject_trigger_count = sum(
        1
        for diagnostic in known
        if diagnostic.get("comparison") == "diagnostic-would-trigger-on-reject-scenario"
    )
    speech_miss_count = sum(
        1
        for diagnostic in known
        if diagnostic.get("comparison") == "diagnostic-misses-speech-threshold"
    )
    recommendation = "keep-diagnostic"
    if known and reject_trigger_count == 0 and speech_miss_count == 0:
        recommendation = "keep-diagnostic-hardware-comparison-clean"
    return {
        "diagnosticOnly": True,
        "provider": REALTIME_VAD_DIAGNOSTIC_PROVIDER,
        "peakThreshold": REALTIME_VAD_DIAGNOSTIC_PEAK_THRESHOLD,
        "endSilenceChunks": REALTIME_VAD_DIAGNOSTIC_END_SILENCE_CHUNKS,
        "runsCompared": len(known),
        "wouldDetectSpeechCount": trigger_count,
        "wouldStayQuietCount": quiet_count,
        "rejectScenarioTriggerCount": reject_trigger_count,
        "speechScenarioMissCount": speech_miss_count,
        "recommendation": recommendation,
        "policy": "non-authoritative comparison; /voice-turn push-to-talk and pass1 evidence remain the hardware gate",
    }


def _hardware_sweep_disposition(payload: Mapping[str, Any], *, scenario: str, provider: Any, pass1_ready: Any, pass1_status: Any) -> str:
    route = _mapping(payload.get("route"))
    verdict = _mapping(payload.get("verdict"))
    input_type = str(route.get("inputType") or "")
    output_type = str(route.get("outputType") or "")
    wearable = route.get("wearableActiveAtCapture")
    transcript_source = str(verdict.get("transcriptSource") or "")
    stt_status = str(verdict.get("sttStatus") or "")
    scenario_text = str(scenario or "").lower()
    real_route = bool(wearable) and ("TYPE_BLE_HEADSET" in {input_type, output_type} or "TYPE_BLUETOOTH_SCO" in {input_type, output_type})
    if not real_route:
        return "invalid-route"
    if provider not in {XANDER_PROVIDER, MOBILE_FAST_PROVIDER}:
        return "invalid-provider"
    if pass1_ready is True and pass1_status == "real-speech-proven":
        return "accept"
    if scenario_text in {"silence", "clipped", "clipped/too-short", "too-short"} and pass1_status != "real-speech-proven":
        return "expected-reject"
    if transcript_source in {"debug", "route-evidence-fallback", "proof"} or stt_status in {"", "empty", "not-run"}:
        return "invalid-debug"
    return "unexpected-fail"


def _canonical_timing_target_result(*, ttfa_ms: Any, ack_ms: Any, backend_ms: Any, total_ms: Any) -> str:
    checks = [
        _target_check(ttfa_ms, TIMING_CONTRACT_TARGETS["ttfaMs"]),
        _target_check(ack_ms, TIMING_CONTRACT_TARGETS["postCaptureAckDelayMs"]),
        _target_check(backend_ms, TIMING_CONTRACT_TARGETS["backendRoundTripMs"]),
        _target_check(total_ms, TIMING_CONTRACT_TARGETS["turnTotalMs"]),
    ]
    known = [check for check in checks if check != "unknown"]
    if not known:
        return "unknown"
    if "miss" in known and len(known) < len(checks):
        return "mixed"
    if "miss" in known:
        return "miss"
    if len(known) < len(checks):
        return "mixed"
    return "pass"


def _target_check(value: Any, target: int) -> str:
    try:
        return "pass" if int(value) <= target else "miss"
    except (TypeError, ValueError):
        return "unknown"


def _first_present(*values: Any) -> Any:
    for value in values:
        if value is not None:
            return value
    return None


def _int_or_none(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


SENSITIVE_METRICS_KEYS = {
    "transcript",
    "assistantText",
    "rawTranscript",
    "rawAssistantText",
    "transcriptText",
    "assistantResponseText",
    "pcm16Mono16kBase64",
    "audioBase64",
    "rawAudio",
    "rawAudioBase64",
    "audioPcmBase64",
    "ttsPcm16Mono16kBase64",
}


def _sanitize_metrics_payload(payload: Mapping[str, Any]) -> dict[str, Any]:
    # Do not persist raw spoken transcript/assistant text or raw/base64 audio even if a future client
    # accidentally sends it nested inside transport, debug, sweep, or vendor evidence objects.
    data = _recursive_sanitize_metrics_value(json.loads(json.dumps(payload)))
    contract = data.get("timingContract")
    if not isinstance(contract, dict):
        data["timingContract"] = _default_timing_contract()
    else:
        contract.setdefault("name", TIMING_CONTRACT_NAME)
        contract.setdefault("version", TIMING_CONTRACT_VERSION)
        contract.setdefault("clock", TIMING_CONTRACT_CLOCK)
        targets = contract.get("targets")
        if not isinstance(targets, dict):
            contract["targets"] = dict(TIMING_CONTRACT_TARGETS)
        else:
            for key, value in TIMING_CONTRACT_TARGETS.items():
                targets.setdefault(key, value)
    perceived_latency = data.get("perceivedLatency")
    if isinstance(perceived_latency, dict) and perceived_latency.get("postCaptureAckDelayMs") is None:
        breakdown = perceived_latency.get("breakdown")
        if isinstance(breakdown, dict) and breakdown.get("postCaptureDispatchMs") is not None:
            perceived_latency["postCaptureAckDelayMs"] = breakdown.get("postCaptureDispatchMs")
    return data


def _recursive_sanitize_metrics_value(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {
            str(key): _recursive_sanitize_metrics_value(nested)
            for key, nested in value.items()
            if str(key) not in SENSITIVE_METRICS_KEYS
        }
    if isinstance(value, list):
        return [_recursive_sanitize_metrics_value(item) for item in value]
    return value


def _default_timing_contract() -> dict[str, Any]:
    return {
        "name": TIMING_CONTRACT_NAME,
        "version": TIMING_CONTRACT_VERSION,
        "clock": TIMING_CONTRACT_CLOCK,
        "targets": dict(TIMING_CONTRACT_TARGETS),
    }


def _attach_metrics_record_summary(record: dict[str, Any]) -> None:
    if isinstance(record.get("summary"), dict):
        return
    payload = record.get("payload")
    payload = payload if isinstance(payload, Mapping) else {}
    turn = _mapping(payload.get("turn"))
    route = _mapping(payload.get("route"))
    backend = _mapping(payload.get("backend"))
    verdict = _mapping(payload.get("verdict"))
    timing_summary = _mapping(record.get("timingSummary"))
    record["summary"] = {
        "turnId": _nested_get(turn, "turnId") or record.get("recordId") or "unknown",
        "stage": _nested_get(turn, "stage"),
        "success": _nested_get(turn, "success"),
        "routeName": _nested_get(route, "inputName"),
        "provider": _nested_get(verdict, "provider"),
        "pass1Status": _nested_get(verdict, "pass1Status"),
        "pass1Ready": _nested_get(verdict, "pass1Ready"),
        "transcriptSource": _nested_get(verdict, "transcriptSource"),
        "sttStatus": _nested_get(verdict, "sttStatus"),
        "sttLatencyMs": _nested_get(backend, "sttLatencyMs"),
        "mobileFastFailureReason": _nested_get(backend, "mobileFastFailureReason"),
        "xanderFallbackFailureReason": _nested_get(backend, "xanderFallbackFailureReason"),
        "ttfaMs": _nested_get(timing_summary, "ttfaMs"),
        "postCaptureAckDelayMs": _nested_get(timing_summary, "postCaptureAckDelayMs"),
        "backendRoundTripMs": _nested_get(timing_summary, "backendRoundTripMs"),
        "turnTotalMs": _nested_get(timing_summary, "turnTotalMs"),
        "timingTargetResult": _canonical_timing_target_result(
            ttfa_ms=_nested_get(timing_summary, "ttfaMs"),
            ack_ms=_nested_get(timing_summary, "postCaptureAckDelayMs"),
            backend_ms=_nested_get(timing_summary, "backendRoundTripMs"),
            total_ms=_nested_get(timing_summary, "turnTotalMs"),
        ),
        "privacy": "summary-only; raw audio and spoken text omitted",
    }
    record["summary"] = {key: value for key, value in record["summary"].items() if value is not None}


def _metrics_history_summary(records: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    summaries = []
    for record in records:
        summary = _mapping(record.get("summary"))
        if not summary:
            mutable = dict(record)
            _attach_metrics_record_summary(mutable)
            summary = _mapping(mutable.get("summary"))
        summaries.append(summary)
    success_count = sum(1 for summary in summaries if summary.get("success") is True)
    failure_count = sum(1 for summary in summaries if summary.get("success") is False)
    timing_results: dict[str, int] = {}
    pass1_statuses: dict[str, int] = {}
    for summary in summaries:
        timing_result = str(summary.get("timingTargetResult") or "unknown")
        timing_results[timing_result] = timing_results.get(timing_result, 0) + 1
        pass1_status = str(summary.get("pass1Status") or "unknown")
        pass1_statuses[pass1_status] = pass1_statuses.get(pass1_status, 0) + 1
    latest = summaries[0] if summaries else {}
    return {
        "recordsSummarized": len(summaries),
        "latestTurnId": latest.get("turnId"),
        "latestStage": latest.get("stage"),
        "latestSuccess": latest.get("success"),
        "successCount": success_count,
        "failureCount": failure_count,
        "unknownSuccessCount": len(summaries) - success_count - failure_count,
        "timingTargetResults": timing_results,
        "pass1Statuses": pass1_statuses,
        "privacy": "aggregate counts and scalar timing only; no raw audio, transcript text, or assistant text",
    }


def _hardware_sweep_history_summary(summaries: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    dispositions: dict[str, int] = {}
    timing_results: dict[str, int] = {}
    for summary in summaries:
        disposition = str(summary.get("runDisposition") or "unknown")
        dispositions[disposition] = dispositions.get(disposition, 0) + 1
        timing = str(summary.get("canonicalTimingTargetResult") or "unknown")
        timing_results[timing] = timing_results.get(timing, 0) + 1
    latest = summaries[0] if summaries else {}
    return {
        "runsSummarized": len(summaries),
        "latestRunId": latest.get("runId"),
        "latestDisposition": latest.get("runDisposition"),
        "dispositions": dispositions,
        "timingTargetResults": timing_results,
        "privacy": "run-sheet aggregate only; raw audio and spoken text omitted",
    }


def _attach_metrics_timing_summary(record: dict[str, Any]) -> None:
    if not isinstance(record.get("timingSummary"), dict):
        payload = record.get("payload")
        record["timingSummary"] = _metrics_timing_summary(payload if isinstance(payload, Mapping) else {})


def _metrics_timing_summary(payload: Mapping[str, Any]) -> dict[str, Any]:
    perceived_latency = _mapping_get(payload, "perceivedLatency")
    breakdown = _mapping_get(perceived_latency, "breakdown")
    return {
        "turnTotalMs": _nested_get(payload, "totals", "turnTotalMs"),
        "backendRoundTripMs": _nested_get(payload, "backend", "roundTripMs"),
        "ttfaMs": _nested_get(perceived_latency, "ttfaMs"),
        "postCaptureAckDelayMs": (
            _nested_get(perceived_latency, "postCaptureAckDelayMs")
            if _nested_get(perceived_latency, "postCaptureAckDelayMs") is not None
            else _nested_get(breakdown, "postCaptureDispatchMs")
        ),
        "localAckKind": _nested_get(perceived_latency, "localAckKind"),
        "localAckStartMs": _nested_get(perceived_latency, "localAckStartMs"),
        "localAckTotalMs": _nested_get(perceived_latency, "localAckTotalMs"),
        "assistantPlaybackStartMs": _nested_get(perceived_latency, "assistantPlaybackStartMs"),
        "backendResponseReadyMs": _nested_get(perceived_latency, "backendResponseReadyMs"),
        "routeSelectMs": _nested_get(breakdown, "routeSelectMs"),
        "captureReadMs": _nested_get(breakdown, "captureReadMs"),
        "postCaptureDispatchMs": _nested_get(breakdown, "postCaptureDispatchMs"),
        "backendWaitAfterReleaseMs": _nested_get(breakdown, "backendWaitAfterReleaseMs"),
    }


def _mapping_get(value: Any, key: str) -> Mapping[str, Any]:
    if isinstance(value, Mapping):
        nested = value.get(key)
        if isinstance(nested, Mapping):
            return nested
    return {}


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


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
            self._send_json(
                200,
                {
                    "ok": True,
                    "service": "otoxan-mobile-voice-turn",
                    "streamTransport": stream_transport_descriptor(),
                },
            )
            return
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/voice-turn-metrics/latest":
            self._send_json(200, latest_voice_turn_metrics())
            return
        if parsed.path == "/voice-turn-metrics/recent":
            query = urllib.parse.parse_qs(parsed.query)
            raw_limit = query.get("limit", ["20"])[0]
            try:
                limit = int(raw_limit)
            except ValueError:
                limit = 20
            self._send_json(200, recent_voice_turn_metrics(limit=limit))
            return
        if parsed.path == "/hardware-sweep/recent":
            query = urllib.parse.parse_qs(parsed.query)
            raw_limit = query.get("limit", ["20"])[0]
            try:
                limit = int(raw_limit)
            except ValueError:
                limit = 20
            self._send_json(200, recent_hardware_sweep_summaries(limit=limit))
            return
        self._send_json(404, {"ok": False, "error": "not found"})

    def do_POST(self) -> None:  # noqa: N802 - stdlib handler API.
        if self.path == "/voice-turn-metrics":
            self._handle_metrics_post()
            return
        if self.path == EXPERIMENTAL_STREAM_ENDPOINT:
            self._handle_stream_post()
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
            _safe_log(
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
                f"xanderFastStatus={response.get('timing', {}).get('xanderFastStatus')} "
                f"xanderFastTimedOut={response.get('timing', {}).get('xanderFastTimedOut')} "
                f"transcriptSource={response.get('transcriptSource')} "
                f"pass1Status={response.get('pass1Status')} "
                f"input={response.get('routeEvidence', {}).get('inputName')} "
                f"type={response.get('routeEvidence', {}).get('inputType')}"
            )
            self._send_json(200, response)
        except json.JSONDecodeError:
            _safe_log("voice-turn error status=400 reason=invalid-json")
            self._send_json(400, {"ok": False, "error": "Invalid JSON"})
        except VoiceTurnError as exc:
            _safe_log(f"voice-turn error status={exc.status} reason={str(exc)[:160]}")
            self._send_json(exc.status, {"ok": False, "error": str(exc)})
        except Exception as exc:  # noqa: BLE001 - dinky dev server; return concise failure.
            _safe_log(f"voice-turn error status=500 reason={str(exc)[:160]}")
            self._send_json(500, {"ok": False, "error": str(exc)})

    def _handle_metrics_post(self) -> None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length).decode("utf-8") if length else "{}"
            payload = json.loads(body)
            response = handle_voice_turn_metrics(payload, remote_addr=self.client_address[0])
            self._send_json(200, response)
        except json.JSONDecodeError:
            _safe_log("voice-turn-metrics error status=400 reason=invalid-json")
            self._send_json(400, {"ok": False, "error": "Invalid JSON"})
        except VoiceTurnError as exc:
            _safe_log(f"voice-turn-metrics error status={exc.status} reason={str(exc)[:160]}")
            self._send_json(exc.status, {"ok": False, "error": str(exc)})
        except Exception as exc:  # noqa: BLE001 - dinky dev server; return concise failure.
            _safe_log(f"voice-turn-metrics error status=500 reason={str(exc)[:160]}")
            self._send_json(500, {"ok": False, "error": str(exc)})

    def _handle_stream_post(self) -> None:
        if not experimental_stream_transport_enabled():
            _safe_log("voice-stream blocked status=404 reason=experimental-flag-disabled")
            self._send_json(404, {"ok": False, "error": "not found", "experimentalFlag": EXPERIMENTAL_STREAM_TRANSPORT_ENV})
            return
        try:
            request_started = time.monotonic()
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length).decode("utf-8") if length else "{}"
            request_read_ms = _elapsed_ms(request_started)
            parse_started = time.monotonic()
            payload = json.loads(body)
            json_parse_ms = _elapsed_ms(parse_started)
            events = handle_voice_stream(payload)
            events[0].setdefault("timing", {})["httpRequestReadMs"] = request_read_ms
            events[0].setdefault("timing", {})["httpJsonParseMs"] = json_parse_ms
            response_event = next((event for event in events if event.get("type") == "response.completed"), {})
            response_voice_turn = response_event.get("voiceTurn", {})
            _safe_log(
                "voice-stream ok "
                f"provider={response_voice_turn.get('provider')} "
                f"bytes={response_voice_turn.get('bytesReceived')} "
                f"events={len(events)}"
            )
            self._send_ndjson(200, events)
        except json.JSONDecodeError:
            _safe_log("voice-stream error status=400 reason=invalid-json")
            self._send_json(400, {"ok": False, "error": "Invalid JSON"})
        except VoiceTurnError as exc:
            _safe_log(f"voice-stream error status={exc.status} reason={str(exc)[:160]}")
            self._send_json(exc.status, {"ok": False, "error": str(exc)})
        except Exception as exc:  # noqa: BLE001 - dinky dev server; return concise failure.
            _safe_log(f"voice-stream error status=500 reason={str(exc)[:160]}")
            self._send_json(500, {"ok": False, "error": str(exc)})

    def log_message(self, fmt: str, *args: object) -> None:
        _safe_log(f"{self.client_address[0]} - {fmt % args}")

    def _send_json(self, status: int, payload: Mapping[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            _safe_log(f"voice-turn client disconnected before status={status} response completed")

    def _send_ndjson(self, status: int, events: Sequence[Mapping[str, Any]]) -> None:
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/x-ndjson")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            for event in events:
                self.wfile.write(json.dumps(event, separators=(",", ":")).encode("utf-8") + b"\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            _safe_log(f"voice-stream client disconnected before status={status} response completed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    _safe_log(f"Otoxan Mobile voice-turn server -> http://{args.host}:{args.port}/voice-turn")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        _safe_log("Stopped.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
