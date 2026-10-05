# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Capture real SBI payloads from a running device service into sbi/testdata/ (Build Map #3).

Start MOSIP's MockMDS in Registration mode first (see docs/sbi-notes.md), then from the repo root:

    python tools/capture_sbi_fixtures.py [--port 4501] [--stream-seconds 3]

Standard library only. Writes:
    discovery.json, deviceinfo.json, rcapture-face.json   raw responses
    rcapture-face.request.json                            the exact request sent (includes the nonce)
    stream-sample.mjpeg                                   a few seconds of the M-JPEG stream
    capture-meta.json                                     port, nonce, timings, stream byte and frame counts

The mock's face image is MOSIP's public test photo of a real person, so rcapture-face.json and
stream-sample.mjpeg are gitignored and must never be committed (docs/licences.md).
"""

import argparse
import datetime
import json
import pathlib
import secrets
import time
import urllib.error
import urllib.request

OUT = pathlib.Path("sbi/testdata")


def call(port, method, path, body, timeout=30):
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=json.dumps(body).encode("utf-8"),
        method=method,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def digital_id_type(digital_id):
    """Discovery returns digitalId as base64 JSON (unsigned before registration); read its 'type'."""
    import base64
    try:
        padded = digital_id + "=" * (-len(digital_id) % 4)
        return json.loads(base64.urlsafe_b64decode(padded)).get("type")
    except (ValueError, TypeError):
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=4501)
    ap.add_argument("--stream-seconds", type=float, default=3.0)
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)

    discovery = call(args.port, "MOSIPDISC", "/device", {"type": "Biometric Device"})
    (OUT / "discovery.json").write_bytes(discovery)
    devices = json.loads(discovery)
    face = next((d for d in devices if digital_id_type(d.get("digitalId", "")) == "Face"), None)
    if face is None:
        raise SystemExit("no device whose digitalId type is 'Face' in the discovery response")

    (OUT / "deviceinfo.json").write_bytes(call(args.port, "MOSIPDINFO", "/info", {}))

    # 128-bit session nonce as 32 lowercase hex characters: SBI allows only letters, digits and
    # hyphens in transactionId (4-50 chars), so base64url would be rejected (Build Map #18).
    nonce = secrets.token_hex(16)
    request = {
        "env": "Staging",
        "purpose": "Registration",
        "specVersion": "0.9.5",
        "timeout": "10000",
        "captureTime": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "transactionId": nonce,
        "bio": [{
            "type": "Face",
            "count": "1",
            "bioSubType": [],
            "exception": [],
            "requestedScore": "40",
            "deviceId": str(face["deviceId"]),
            "deviceSubId": "0",
            "previousHash": "",
        }],
        "customOpts": None,
    }
    (OUT / "rcapture-face.request.json").write_text(json.dumps(request, indent=2), encoding="utf-8")
    t0 = time.time()
    rcapture = call(args.port, "RCAPTURE", "/capture", request, timeout=60)
    rcapture_ms = (time.time() - t0) * 1000
    (OUT / "rcapture-face.json").write_bytes(rcapture)

    stream_bytes = b""
    stream_req = urllib.request.Request(
        f"http://127.0.0.1:{args.port}/stream",
        data=json.dumps({"deviceId": str(face["deviceId"]), "deviceSubId": "0", "timeout": "10000"}).encode(),
        method="STREAM",
        headers={"Content-Type": "application/json"},
    )
    deadline = time.time() + args.stream_seconds
    try:
        with urllib.request.urlopen(stream_req, timeout=args.stream_seconds + 5) as resp:
            content_type = resp.headers.get("Content-Type")
            while time.time() < deadline:
                chunk = resp.read1(65536)
                if not chunk:
                    break
                stream_bytes += chunk
    except (urllib.error.URLError, TimeoutError) as e:
        content_type = f"error: {e}"
    (OUT / "stream-sample.mjpeg").write_bytes(stream_bytes)
    jpeg_frames = stream_bytes.count(b"\xff\xd8\xff")

    meta = {
        "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "port": args.port,
        "faceDeviceId": str(face["deviceId"]),
        "nonce": nonce,
        "rcaptureMillis": round(rcapture_ms),
        "streamContentType": content_type,
        "streamSeconds": args.stream_seconds,
        "streamBytes": len(stream_bytes),
        "streamJpegFrames": jpeg_frames,
        "streamFps": round(jpeg_frames / args.stream_seconds, 2),
    }
    (OUT / "capture-meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
