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
A bundle URL (a zip with the four converted models, hosted on Google Drive, a GitHub Release or anywhere HTTPS) can be given with
--bundle-url, the PRAMAAN_MODELS_URL environment variable, or the first non-comment line of config/models-bundle-url.txt. Only files whose
name is in the table and whose SHA-256 matches are taken from it; every other entry in the zip is ignored, and no path inside the
zip is ever used to decide where a file goes.

Uses only the Python standard library.
"""
import argparse
import hashlib
import io
import json
import os
import sys
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
TABLE = ROOT / "config" / "operating-points.json"
MODELS = ROOT / "models"
# Not in models/: the licence check treats every file there as a model that needs a row in docs/licences.md.
URL_FILE = ROOT / "config" / "models-bundle-url.txt"

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


def bundle_url(explicit=None, url_file=URL_FILE):
    """The bundle URL from the argument, the environment, or config/models-bundle-url.txt; None if none is set."""
    if explicit:
        return explicit
    if os.environ.get("PRAMAAN_MODELS_URL"):
        return os.environ["PRAMAAN_MODELS_URL"].strip()
    f = Path(url_file)
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                return line
    return None


MAX_MEMBER_BYTES = 200 * 1024 * 1024


def fetch_bundle(url, models_dir, table, wanted, opener=urllib.request.urlopen):
    """Downloads a zip and takes from it only the files in wanted whose SHA-256 matches the table. Returns the names placed."""
    with opener(url) as resp:
        data = resp.read()
    try:
        zf = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        print("  REFUSED: %s is not a zip file (a Drive link may need to be a direct-download link)" % url)
        return []
    placed = []
    with zf:
        for info in zf.infolist():
            name = PurePosixPath(info.filename.replace("\\", "/")).name  # the name only: a path in the zip decides nothing
            if name not in wanted or info.is_dir() or info.file_size > MAX_MEMBER_BYTES:
                continue
            blob = zf.read(info)
            if hashlib.sha256(blob).hexdigest() != table[name]:
                print("  REFUSED: %s in the bundle does not match the table's SHA-256" % name)
                continue
            dest = Path(models_dir) / name
            tmp = Path(str(dest) + ".part")
            tmp.write_bytes(blob)
            tmp.replace(dest)
            placed.append(name)
            print("ok       %s (from the bundle)" % name)
    return placed


def run(models_dir, table, check_only, opener=urllib.request.urlopen, bundle=None):
    models_dir.mkdir(parents=True, exist_ok=True)
    missing = 0
    pending = []
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
            pending.append(name)
    if pending and bundle:
        print("fetching the bundle for %d file(s)" % len(pending))
        try:
            got = set(fetch_bundle(bundle, models_dir, table, set(pending), opener))
        except OSError as e:
            print("  could not download the bundle: %s" % e)
            got = set()
        pending = [n for n in pending if n not in got]
    for name in pending:
        print("missing  %s: %s; there is no public download. Put the bundle URL in config/models-bundle-url.txt (or pass "
              "--bundle-url), regenerate it (models/README.md), or copy it in by hand." % (name, OURS.get(name, "not a model this script knows")))
        missing += 1
    print("%d of %d model files verified" % (len(table) - missing, len(table)))
    return 0 if missing == 0 else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true", help="verify only; download nothing")
    ap.add_argument("--models-dir", type=Path, default=MODELS)
    ap.add_argument("--bundle-url", help="a zip with the models we converted ourselves (see the module docstring)")
    args = ap.parse_args(argv)
    return run(args.models_dir, expected_hashes(), args.check, bundle=bundle_url(args.bundle_url))


if __name__ == "__main__":
    sys.exit(main())
