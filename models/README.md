# models/

The model files the engine runs live here and are **not committed** (`*.onnx` is gitignored). Why: a model may ship only if
its weights' licence *and* its training data's terms both allow it (`docs/licences.md`), and every file here needs a row in
that table, keyed by its exact file name (`./gradlew check` fails otherwise).

**Without these files the engine refuses to start** (it fails closed: `EngineUnavailableException`, "a model will not load" or
a hash mismatch), and the tests that need them skip themselves. So a fresh clone builds and passes its tests, with about 20
skips, but cannot run the scaffold until the six files below are placed here.

## Fetching and checking them

```bash
python tools/fetch_models.py --check     # verify what is here against config/operating-points.json; downloads nothing
python tools/fetch_models.py             # download YuNet and SFace from the OpenCV Zoo (hash-checked), verify all six
```

The script refuses any file whose SHA-256 differs from the table and never overwrites a file that is already there. The other four
files are our own conversions and have no public URL: regenerate them (below) or copy them from the models bundle; the script says
which are missing. Its tests (`python -m unittest tools/test_fetch_models.py`) run against a local server and download nothing.

## The verified bundle (six files)

`config/operating-points.json` binds every one of these to its SHA-256: swap a file for another and the engine will not start
until the table is regenerated. Check yours with `sha256sum models/*.onnx` (Git Bash, Linux, macOS) or
`Get-FileHash models\*.onnx -Algorithm SHA256` (PowerShell).

| File | Role | Size (bytes) | SHA-256 |
|---|---|---:|---|
| `face_detection_yunet_2023mar.onnx` | face detection | 232,589 | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` |
| `mediapipe_face_landmarks_detector.onnx` | 478-point landmarks | 4,921,027 | `b8b8ea16213c5f5f93517cc4d9a13f22f9859588851de64a0914e1d66f02c9f8` |
| `mediapipe_face_blendshapes.onnx` | expression scores (a second vote for smile and eyes) | 1,882,694 | `baafa07fdf2bc17d3ca3f1079c315b8d0da73e55863b6c0a0fce700aa595ac95` |
| `minifasnet_v1se_4.0_80x80.onnx` | passive liveness scorer | 1,742,663 | `17246cc267b4b3d23e49966f2062650dc4e6fe3f1a3c7ecc3ae7b0f67ad1aec4` |
| `minifasnet_v2_2.7_80x80.onnx` | passive liveness scorer | 1,743,495 | `eb0feb2c6896cf52755d71b38888ea1ef56ceb266d9583ba64de30ea66f7717b` |
| `face_recognition_sface_2021dec.onnx` | face embedding (identity continuity, operator-face check) | 38,696,353 | `0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79` |

## Where each comes from, and under what licence

| File | Source | Weights licence | Caveat |
|---|---|---|---|
| YuNet | OpenCV Zoo, `models/face_detection_yunet/face_detection_yunet_2023mar.onnx`: https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet | MIT | trained on WIDER Face (per OpenCV Zoo) |
| SFace | OpenCV Zoo, `models/face_recognition_sface/face_recognition_sface_2021dec.onnx`: https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface | Apache-2.0 | **Development use only until the training-data question is answered** (Bible Part 30, Q19): the SFace paper trains on research-only datasets. Production needs a matcher the deployment already licenses (MOSIP BioSDK) |
| MiniFASNet (both) | **our ONNX export** of Minivision's `2.7_80x80_MiniFASNetV2.pth` and `4_0_0_80x80_MiniFASNetV1SE.pth`: https://github.com/minivision-ai/Silent-Face-Anti-Spoofing | Apache-2.0 | training data undisclosed by Minivision |
| MediaPipe landmarks and blendshapes | **our tf2onnx conversion** of `face_landmarks_detector.tflite` and `face_blendshapes.tflite` from MediaPipe's `face_landmarker.task`: https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker | Apache-2.0 (model card) | the model card lists life-critical decisions as out of scope and says it is not face recognition; we use it only for geometry and action cues |

Drop in a downloaded YuNet or SFace and compare the SHA-256 above. If it differs, you have a different build of the model and
the engine will refuse it, which is the intended behaviour.

### Regenerating the two we converted

- **MiniFASNet:** put Minivision's repository at `third_party/Silent-Face-Anti-Spoofing`, create the tools environment
  (PyTorch CPU 2.14, `onnx` 1.23; see `tools/versions.md`), then
  `tools/.venv/Scripts/python tools/export_minifasnet.py`. The script writes both files and checks parity against PyTorch
  to 1e-4 (measured: 3.3e-6 and 1.9e-6).
- **MediaPipe:** extract the two `.tflite` files from `face_landmarker.task` (it is a zip), then convert in a separate
  Python 3.12 environment with TensorFlow CPU 2.21 and `tf2onnx` 1.17 (`tf2onnx.convert --tflite ... --opset 13`). Why a
  separate environment and what failed first: `docs/adr/0002-landmarks.md`. Parity with TFLite: 1.5e-4 px (landmarks),
  1.6e-6 (blendshapes).

A re-export is **not guaranteed to be byte-identical** to the files hashed above (framework versions can change the bytes),
and then the table's hashes must be regenerated with the harness; the numbers in the table are provisional anyway.

## Not in the bundle

| File | Why it is not |
|---|---|
| `u2netp.onnx` (4,574,861 bytes, SHA-256 `309c8469...ddd8`) | **Evaluation only.** It was used once to compare person-segmentation routes (`docs/person-segmentation.md`). Its training data's commercial terms were not checked, it is not in `operating-points.json`, and it is never loaded by the engine |

## Other model-shaped files

`core/src/test/resources/models/smoke_affine.onnx` is the one `.onnx` that **is** committed: a synthetic y = 2x + 1 fixture made
by `tools/make_smoke_model.py`, so the inference wrapper can be tested with no real model.
