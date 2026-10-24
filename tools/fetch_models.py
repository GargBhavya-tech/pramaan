#!/usr/bin/env python3
"""Fetches and verifies the model files the engine runs (see models/README.md).

    python tools/fetch_models.py --check          # verify what is in models/ against the table, download nothing
    python tools/fetch_models.py                  # download the files that have a public source, then verify all six

The authoritative hash of every model is in config/operating-points.json ("models"): the engine refuses to start if a file's
SHA-256 differs. This script never writes a file whose hash does not match, and never overwrites a file that already matches.

Two of the six (YuNet, SFace) come straight from the OpenCV Zoo. The other four are OUR conversions (MiniFASNet from Minivision's
PyTorch weights, the two MediaPipe models from TFLite): there is no public URL for them, so the script says how to regenerate
them (models/README.md, "Regenerating the two we converted") or where to copy them from, and exits non-zero until they are there.

SFace is development use only until its training-data terms are answered (docs/licences.md, Bible Part 30).
Uses only the Python standard library.
"""
import argparse
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TABLE = ROOT / "config" / "operating-points.json"
MODELS = ROOT / "models"

ZOO = "https://github.com/opencv/opencv_zoo/raw/main/models/"
SOURCES = {
    "face_detection_yunet_2023mar.onnx": ZOO + "face_detection_yunet/face_detection_yunet_2023mar.onnx",
    "face_recognition_sface_2021dec.onnx": ZOO + "face_recognition_sface/face_recognition_sface_2021dec.onnx",
}
OURS = {
    "minifasnet_v1se_4.0_80x80.onnx": "our ONNX export of Minivision's 4_0_0_80x80_MiniFASNetV1SE.pth",
    "minifasnet_v2_2.7_80x80.onnx": "our ONNX export of Minivision's 2.7_80x80_MiniFASNetV2.pth",
    "mediapipe_face_landmarks_detector.onnx": "our tf2onnx conversion of MediaPipe's face_landmarks_detector.tflite",
    "mediapipe_face_blendshapes.onnx": "our tf2onnx conversion of MediaPipe's face_blendshapes.tflite",
}


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def expected_hashes(table_path=TABLE):
    with open(table_path, encoding="utf-8") as f:
        return dict(json.load(f)["models"])


def fetch(url, dest, sha256, opener=urllib.request.urlopen):
    """Downloads url to dest only if the bytes match sha256. Returns True on success; never leaves a wrong file behind."""
    tmp = Path(str(dest) + ".part")
    try:
        with opener(url) as resp, open(tmp, "wb") as out:
            h = hashlib.sha256()
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                h.update(chunk)
                out.write(chunk)
        if h.hexdigest() != sha256:
            print("  REFUSED: the download's SHA-256 is %s, the table wants %s" % (h.hexdigest(), sha256))
            return False
        tmp.replace(dest)
        return True
    finally:
        if tmp.exists():
            tmp.unlink()


def run(models_dir, table, check_only, opener=urllib.request.urlopen):
    models_dir.mkdir(parents=True, exist_ok=True)
    missing = 0
    for name, want in sorted(table.items()):
        path = models_dir / name
        if path.exists() and sha256_file(path) == want:
            print("ok       %s" % name)
            continue
        if path.exists():
            print("WRONG    %s: its SHA-256 differs from the table (a different build of the model; the engine will refuse it)" % name)
            missing += 1
            continue
        if check_only:
            print("missing  %s" % name)
            missing += 1
            continue
        if name in SOURCES:
            print("fetching %s" % name)
            if fetch(SOURCES[name], path, want, opener):
                print("ok       %s" % name)
                continue
            missing += 1
        else:
            print("missing  %s: %s; there is no public download. Regenerate it (models/README.md) or copy it from the "
                  "models bundle." % (name, OURS.get(name, "not a model this script knows")))
            missing += 1
    print("%d of %d model files verified" % (len(table) - missing, len(table)))
    return 0 if missing == 0 else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="verify only; download nothing")
    ap.add_argument("--models-dir", type=Path, default=MODELS)
    args = ap.parse_args(argv)
    return run(args.models_dir, expected_hashes(), args.check)


if __name__ == "__main__":
    sys.exit(main())
