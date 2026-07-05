#!/usr/bin/env python3
"""Smoke-test an Otoxan Mobile voice-turn endpoint."""

from __future__ import annotations

import argparse
import base64
import json
import sys
import urllib.request


PCM_BYTES = b"\x01\x02" * 160


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("url", help="voice-turn endpoint URL")
    parser.add_argument("--expect-provider", default="", help="optional exact provider value to require")
    args = parser.parse_args()

    payload = {
        "format": "pcm_s16le_16khz_mono",
        "pcm16Mono16kBase64": base64.b64encode(PCM_BYTES).decode("ascii"),
        "routeEvidence": {
            "inputName": "Ray-Ban Meta",
            "inputType": "TYPE_BLE_HEADSET",
            "outputName": "Ray-Ban Meta",
            "outputType": "TYPE_BLE_HEADSET",
            "wearableActive": True,
            "message": "make smoke-backend",
        },
    }
    req = urllib.request.Request(
        args.url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read())

    tts_bytes = base64.b64decode(data.get("ttsPcm16Mono16kBase64", ""), validate=True)
    summary = {
        "endpoint": args.url,
        "ok": data.get("ok"),
        "provider": data.get("provider"),
        "bytesReceived": data.get("bytesReceived"),
        "audioFormat": data.get("audioFormat"),
        "transcript": data.get("transcript"),
        "assistantText": data.get("assistantText"),
        "tts_bytes": len(tts_bytes),
    }
    print(json.dumps(summary, indent=2))

    failures = []
    if data.get("ok") is not True:
        failures.append("ok was not true")
    if args.expect_provider and data.get("provider") != args.expect_provider:
        failures.append(f"provider {data.get('provider')!r} != expected {args.expect_provider!r}")
    if data.get("bytesReceived") != len(PCM_BYTES):
        failures.append(f"bytesReceived {data.get('bytesReceived')!r} != sent {len(PCM_BYTES)}")
    if data.get("audioFormat") != "pcm_s16le_16khz_mono":
        failures.append(f"audioFormat {data.get('audioFormat')!r} mismatch")
    if not str(data.get("transcript", "")).strip():
        failures.append("transcript missing")
    if not str(data.get("assistantText", "")).strip():
        failures.append("assistantText missing")
    if not tts_bytes:
        failures.append("ttsPcm16Mono16kBase64 decoded to empty bytes")

    if failures:
        for failure in failures:
            print(f"SMOKE FAIL: {failure}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
