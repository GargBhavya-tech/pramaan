# ADR 0002 — Face landmarks on desktop Java (Build Map #23 spike)

- **Status:** Accepted — desktop landmarks work through ONNX (2026-09-23)
- **Date:** 2026-09-23

## Why this matters

Blink/eyes-closed (#43), smile (#44), head-turn yaw (#45) and the flatness cue (#32) need dense landmarks.
YuNet gives only five points (eyes, nose, mouth corners) — enough for a coarse yaw and for face size,
not for eyelids or mouth shape. MediaPipe Face Landmarker (FaceMesh V2, **Apache-2.0**, 478 points incl. iris, plus
52 blendshapes) is the target, but MediaPipe Tasks has **no desktop Java API**. Android (Mode D) can use MediaPipe
Tasks directly; desktop (Mode C) needs another path.

## What was tried

| Route | Result |
|---|---|
| `tflite2onnx` 0.4.1 on `face_landmarks_detector.tflite` (from `face_landmarker.task`) | **Failed**: `IndexError` in its layout transform. The tool is unmaintained and does not handle this graph |
| `tf2onnx --tflite` in the Python 3.14 tools venv | **Not possible**: no TensorFlow wheels for Python 3.14 |

## Decision

1. **Next route:** a separate `tools/.venv-tf` on Python 3.12 (installed on this machine) with TensorFlow + `tf2onnx`,
   converting `face_landmarks_detector.tflite` and `face_blendshapes.tflite` to ONNX (opset <= 17, IR <= 10 for the
   pinned Java ONNX Runtime 1.20).
2. **Parity check against the original TFLite model** using `ai-edge-litert` (available for Python 3.14), the same way
   MiniFASNet was verified against PyTorch (max difference < 1e-4 on real face crops).
3. **Fallbacks, in order:** a pre-converted ONNX FaceMesh with verified provenance and licence; MediaPipe C++ through
   JNI; and, as a last resort, a desktop that ships passive + physics cues with head-turn only (coarse yaw from YuNet's
   five points) while the full challenge set runs on Android (Mode D).

## Consequences

- Nothing in Phases 2-3 is blocked: detection, quality, tracking and passive scoring do not need dense landmarks.
- The desktop challenge detectors (#43-#45) wait on this conversion. Android does not.

## Result (2026-09-23)

| Step | Outcome |
|---|---|
| `tools/.venv-tf` (Python 3.12, TensorFlow CPU 2.21, tf2onnx 1.17), `tf2onnx.convert --tflite ... --opset 13` | Both models converted |
| Landmarks: ONNX vs original TFLite on a real face crop | max difference **1.5e-4 px** on a 256 px crop; face-presence output identical |
| Blendshapes: ONNX vs TFLite | max difference **1.6e-6** |
| Agreement with an independent model | MediaPipe nose tip and right iris land within a quarter of the inter-ocular distance of YuNet's own points (`RealLandmarksTest`) |

**Contract:** input 256x256 RGB NHWC in [0, 1], a square crop of 1.5x the YuNet box; outputs 478x3 landmarks in crop
pixels and a face-presence logit (`OnnxFaceMeshLandmarker`).

**Blendshapes (wired, 2026-09-26):** `OnnxBlendshapeLandmarker` feeds `Blendshapes.SUBSET` — the 146 indices of
`kLandmarksSubsetIdxs`, with the 52 names of `kBlendshapeNames`, copied from MediaPipe's
`mediapipe/tasks/cc/vision/face_landmarker/face_blendshapes_graph.cc` — as a `[1, 146, 2]` tensor of x, y in frame
pixels (`LandmarksToTensorCalculator` scales normalised x, y by the image size, so frame pixels are the same thing).
The model's own signature confirms it: input `[1, 146, 2]` float, output `[52]`. On the real test face both
eyeBlink scores are low (0.13, 0.06) and the rest come in symmetric left/right pairs (`RealLandmarksTest`).
The engine uses them only when the operating-point table binds the model, and only as a second vote:
`mouthSmileLeft/Right` >= 0.5 and each up >= 0.3 can replace the 12% mouth-width test but never the deformation
tests (#44); `eyeBlinkLeft/Right` >= 0.5 and up >= 0.3 over the person's own passive-window median count as closed
(#43). Which side MediaPipe calls "Left" is not relied on: the two scores are combined with the same both/either rule
as the two EARs, and the vote is OR-ed with the geometric verdict as a whole.
