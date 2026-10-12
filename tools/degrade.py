# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""Degradation gauntlet (Build Map #66; Bible Part 21.2).

Makes rural-camera copies of every clip in a corpus manifest with FFmpeg, and writes a derived manifest in the
same format so the harness runner (#67) takes them as they are:

    python tools/degrade.py --manifest corpus/manifest.csv --out corpus/derived
    python tools/degrade.py --manifest corpus/manifest.csv --out corpus/derived --variants crf38,480p,flicker50

Variants (all by default):
  crf23, crf30, crf38   a real H.264 encode at that CRF, decoded back to frames
  720p, 480p            downscale; skipped when the clip is not taller than the target (never an upscale)
  noise                 temporal + uniform sensor noise
  gamma07, gamma14      gamma 0.7 (darker mid-tones) and 1.4 (washed out)
  dim                   low light: brightness down, gamma down
  flicker50             50 Hz mains: a 100 Hz brightness ripple, sampled at the clip frame rate (it aliases,
                        which is exactly what a fluorescent-lit webcam records)

A derived clip keeps its source row (label, PAIS, person, lighting, eyewear, fps) and names the variant in the
device column as "<device>~<variant>". The person id is kept, so a split of the corpus by person carries over:
degrade both halves, tune on one, evaluate on the other — never tune on degraded copies of evaluation people.

Needs only the Python standard library and an ffmpeg with libx264 on PATH. Outputs are corpus data: keep them
out of git (corpus/ and data/ are gitignored).
"""

import argparse
import csv
import os
import shutil
import subprocess
import sys
import tempfile

HEADER = "clip,label,pais,person,lighting,eyewear,device,fps"

FILTERS = {
    "noise": "noise=alls=12:allf=t+u",
    "gamma07": "eq=gamma=0.7",
    "gamma14": "eq=gamma=1.4",
    "dim": "eq=brightness=-0.12:gamma=0.85",
    "flicker50": "eq=brightness='0.08*sin(2*PI*100*t)':eval=frame",
}
CRF = {"crf23": 23, "crf30": 30, "crf38": 38}
HEIGHTS = {"720p": 720, "480p": 480}
ALL = list(CRF) + list(HEIGHTS) + list(FILTERS)


def frames_of(clip):
    """The clip's JPEG frames, in order: a directory of .jpg/.jpeg files, or a multipart M-JPEG file."""
    if os.path.isdir(clip):
        names = sorted(n for n in os.listdir(clip) if n.lower().endswith((".jpg", ".jpeg")))
        out = []
        for n in names:
            with open(os.path.join(clip, n), "rb") as f:
                out.append(f.read())
        return out
    with open(clip, "rb") as f:
        data = f.read()
    out, at = [], 0
    while True:
        start = data.find(b"\xff\xd8\xff", at)
        if start < 0:
            break
        end = data.find(b"\xff\xd9", start)
        if end < 0:
            break
        out.append(data[start:end + 2])
        at = end + 2
    return out


def jpeg_height(jpeg):
    """Height from the first SOF marker, or 0 if none is found."""
    i = 2
    while i + 9 < len(jpeg):
        if jpeg[i] != 0xFF:
            i += 1
            continue
        marker = jpeg[i + 1]
        if marker in (0xC0, 0xC1, 0xC2):
            return int.from_bytes(jpeg[i + 5:i + 7], "big")
        length = int.from_bytes(jpeg[i + 2:i + 4], "big")
        i += 2 + length
    return 0


def run(cmd, stdin=None):
    p = subprocess.run(cmd, input=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        raise RuntimeError("ffmpeg failed: " + " ".join(cmd) + "\n" + p.stderr.decode(errors="replace")[-800:])


def degrade(frames, fps, variant, out_dir):
    """Writes the variant of these frames to out_dir as %05d.jpg; returns False when the variant does not apply."""
    if variant in HEIGHTS:
        if jpeg_height(frames[0]) <= HEIGHTS[variant]:
            return False
    os.makedirs(out_dir, exist_ok=True)
    source = b"".join(frames)
    pipe_in = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "image2pipe", "-framerate", str(fps),
               "-c:v", "mjpeg", "-i", "-"]
    target = os.path.join(out_dir, "%05d.jpg")
    if variant in CRF:
        with tempfile.TemporaryDirectory() as tmp:
            mp4 = os.path.join(tmp, "encoded.mp4")
            run(pipe_in + ["-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2", "-c:v", "libx264", "-crf", str(CRF[variant]),
                           "-pix_fmt", "yuv420p", mp4], source)
            run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", mp4, "-q:v", "2", target])
        return True
    vf = "scale=-2:%d" % HEIGHTS[variant] if variant in HEIGHTS else FILTERS[variant]
    run(pipe_in + ["-vf", vf, "-q:v", "2", target], source)
    return True


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--out", required=True, help="directory for derived clips and manifest-derived.csv")
    ap.add_argument("--variants", default=",".join(ALL), help="comma-separated subset of: " + ", ".join(ALL))
    a = ap.parse_args(argv)
    if shutil.which("ffmpeg") is None:
        sys.exit("ffmpeg is not on PATH")
    variants = [v.strip() for v in a.variants.split(",") if v.strip()]
    unknown = [v for v in variants if v not in ALL]
    if unknown:
        sys.exit("unknown variant(s): " + ", ".join(unknown))

    base = os.path.dirname(os.path.abspath(a.manifest))
    out = os.path.abspath(a.out)
    os.makedirs(out, exist_ok=True)
    with open(a.manifest, newline="", encoding="utf-8") as f:
        lines = f.read().splitlines()
    if not lines or lines[0].strip() != HEADER:
        sys.exit(a.manifest + ": first line must be exactly '" + HEADER + "'")

    rows, made, skipped = [], 0, 0
    for row in csv.reader(l for l in lines[1:] if l.strip() and not l.lstrip().startswith("#")):
        clip, label, pais, person, lighting, eyewear, device, fps = [c.strip() for c in row]
        frames = frames_of(os.path.join(base, clip))
        if not frames:
            print("no frames, skipped: " + clip, file=sys.stderr)
            continue
        stem = clip.replace("\\", "/").rstrip("/").replace("/", "_").rsplit(".", 1)[0]
        for v in variants:
            target = os.path.join(out, v, stem)
            if degrade(frames, float(fps), v, target):
                rel = os.path.relpath(target, out).replace("\\", "/")
                rows.append([rel, label, pais, person, lighting, eyewear, device + "~" + v, fps])
                made += 1
            else:
                skipped += 1
    with open(os.path.join(out, "manifest-derived.csv"), "w", newline="", encoding="utf-8") as f:
        f.write(HEADER + "\n")
        for r in rows:
            f.write(",".join(r) + "\n")
    print("wrote %d derived clip(s) to %s (%d skipped: no upscales)" % (made, out, skipped))


if __name__ == "__main__":
    main(sys.argv[1:])
