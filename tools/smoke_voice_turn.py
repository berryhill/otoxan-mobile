#!/usr/bin/env python3
"""Smoke-test an Otoxan Mobile voice-turn endpoint."""

from __future__ import annotations

import base64
import json
import sys
import urllib.request


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: smoke_voice_turn.py <voice-turn-url>")
    url = sys.argv[1]
    payload = {
        "format": "pcm_s16le_16khz_mono",
        "pcm16Mono16kBase64": base64.b64encode(b"\x01\x02" * 160).decode("ascii"),
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
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read())
    summary = {
        "endpoint": url,
        "ok": data.get("ok"),
        "transcript": data.get("transcript"),
        "assistantText": data.get("assistantText"),
        "provider": data.get("provider"),
        "tts_bytes": len(base64.b64decode(data.get("ttsPcm16Mono16kBase64", ""))),
    }
    print(json.dumps(summary, indent=2))
    if data.get("ok") is not True:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
