# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
"""
Reference outputs for the Java SFace path (Build Map #56), computed with OpenCV's own FaceDetectorYN and
FaceRecognizerSF so the Java alignment and embedding can be checked for parity, not trusted.

Inputs (gitignored, see docs/sbi-notes.md): sbi/testdata/stream-sample.mjpeg (first frame) and
sbi/testdata/rcapture-face.json (the ISO 19794-5 face record inside the signed capture).
Outputs (gitignored, biometric-derived, never commit): third_party/parity/sface/
    reference.json   landmarks, embeddings, cosine similarities
    aligned_stream.png, aligned_capture.png   OpenCV's 112x112 aligned crops

Run with the Python 3.12 venv:  tools/.venv-tf/Scripts/python.exe tools/sface_reference.py
"""
import base64
import json
import os
import struct

import cv2
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "third_party", "parity", "sface")
YUNET = os.path.join(ROOT, "models", "face_detection_yunet_2023mar.onnx")
SFACE = os.path.join(ROOT, "models", "face_recognition_sface_2021dec.onnx")


def first_stream_frame():
    data = open(os.path.join(ROOT, "sbi", "testdata", "stream-sample.mjpeg"), "rb").read()
    start = data.index(b"\xff\xd8")
    end = data.index(b"\xff\xd9", start) + 2
    return cv2.imdecode(np.frombuffer(data[start:end], np.uint8), cv2.IMREAD_COLOR)


def capture_image():
    d = json.load(open(os.path.join(ROOT, "sbi", "testdata", "rcapture-face.json")))
    payload = d["biometrics"][0]["data"].split(".")[1]
    p = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    iso = base64.urlsafe_b64decode(p["bioValue"] + "=" * (-len(p["bioValue"]) % 4))
    record_length = struct.unpack(">I", iso[8:12])[0]
    start = iso.index(b"\x00\x00\x00\x0cjP  ")
    img = cv2.imdecode(np.frombuffer(iso[start:record_length], np.uint8), cv2.IMREAD_COLOR)
    return img, start, record_length, len(iso)


def detect(img):
    h, w = img.shape[:2]
    det = cv2.FaceDetectorYN.create(YUNET, "", (w, h), 0.6, 0.3, 5000)
    _, faces = det.detect(img)
    if faces is None or len(faces) == 0:
        raise SystemExit("no face found")
    return faces[np.argmax(faces[:, 14])]


def main():
    os.makedirs(OUT, exist_ok=True)
    rec = cv2.FaceRecognizerSF.create(SFACE, "")
    out = {"opencv": cv2.__version__}
    feats = {}
    stream = first_stream_frame()
    capture, jp2_start, record_length, iso_length = capture_image()
    out["capture_iso"] = {"jp2Offset": jp2_start, "recordLength": record_length, "isoLength": iso_length,
                          "width": capture.shape[1], "height": capture.shape[0]}
    for name, img in (("stream", stream), ("capture", capture)):
        face = detect(img)
        aligned = rec.alignCrop(img, face)
        cv2.imwrite(os.path.join(OUT, "aligned_%s.png" % name), aligned)
        feat = rec.feature(aligned).flatten()
        feats[name] = feat
        out[name] = {
            "box": [float(v) for v in face[0:4]],
            "landmarks": [float(v) for v in face[4:14]],
            "score": float(face[14]),
            "embedding": [float(v) for v in feat],
        }
    out["cosine_stream_capture"] = float(rec.match(feats["stream"].reshape(1, -1), feats["capture"].reshape(1, -1),
                                                   cv2.FaceRecognizerSF_FR_COSINE))
    json.dump(out, open(os.path.join(OUT, "reference.json"), "w"), indent=1)
    print("cosine(stream, capture) =", out["cosine_stream_capture"])
    print("capture", out["capture_iso"])
    print("stream landmarks", out["stream"]["landmarks"])


if __name__ == "__main__":
    main()
