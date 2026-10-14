# Licences and dependency policy (Build Map #7)

PRAMAAN is licensed under the **Mozilla Public License 2.0** (`LICENSE`), matching MOSIP's own repositories.

## The rule for models

> **A model may ship only if its weights' licence *and* its training data's terms both allow it.**

Code licensed MIT or Apache is not permission to ship weights trained on academic-only datasets (OULU-NPU, CASIA, MSU,
Replay-Attack, UniAttackData all require signed agreements or restrict use). Every file under `models/` must have a row
in the model table below, keyed by its file name — `./gradlew check` fails otherwise.

## Build and runtime dependencies

| Component | Version | Licence | Used for | Link |
|---|---|---|---|---|
| Gradle (wrapper) | 8.14.3 | Apache-2.0 | Build | https://github.com/gradle/gradle/blob/master/LICENSE |
| ONNX Runtime (Java) | 1.20.0 | MIT | Inference in `core` | https://github.com/microsoft/onnxruntime/blob/main/LICENSE |
| Jackson (databind, YAML) | 2.18.2 | Apache-2.0 | JSON / policy files | https://github.com/FasterXML/jackson-databind/blob/2.18/LICENSE |
| SnakeYAML (via Jackson YAML) | transitive | Apache-2.0 | YAML parsing | https://bitbucket.org/snakeyaml/snakeyaml/src/master/LICENSE.txt |
| jai-imageio-jpeg2000 (+ jai-imageio-core) | 1.3.0 | BSD-3 "w/nuclear disclaimer" (Sun) **and** the JJ2000 licence | JPEG 2000 decoding of the ISO face record in signed captures (`desktop`, #56) | https://github.com/jai-imageio/jai-imageio-jpeg2000 |
| OpenJFX (javafx-base, -graphics, -controls) | 11.0.2 | GPL-2.0 **with the Classpath Exception** | The capture screen and registration scaffold (`desktop` only; ADR 0003). The version MOSIP Registration Client 1.2.x pins, so no second JavaFX | https://github.com/openjdk/jfx/blob/master/LICENSE |
| ONNX Runtime (Android) | 1.20.0 | MIT | Inference in the Android AAR (`android/`, #79) | https://github.com/microsoft/onnxruntime/blob/main/LICENSE |
| AndroidX Test (ext-junit, runner) | 1.1.5 / 1.5.2 | Apache-2.0 | Android instrumentation tests only | https://developer.android.com/jetpack/androidx/releases/test |
| JUnit 4 | 4.13.2 | EPL-1.0 | Android tests only | https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt |
| JUnit 5 | 5.11.4 | EPL-2.0 | Tests only | https://github.com/junit-team/junit5/blob/main/LICENSE.md |

## Development tools (not shipped)

| Component | Version | Licence | Used for | Link |
|---|---|---|---|---|
| Python `onnx` | 1.23.0 | Apache-2.0 | Generating test fixtures (`tools/.venv`) | https://github.com/onnx/onnx/blob/main/LICENSE |
| TensorFlow CPU + tf2onnx | 2.21.0 / 1.17.0 | Apache-2.0 | Converting MediaPipe TFLite models (`tools/.venv-tf`, Python 3.12) | https://github.com/onnx/tensorflow-onnx |
| PyTorch (CPU) | 2.14.0 | BSD-3-Clause | Exporting MiniFASNet to ONNX (`tools/.venv`) | https://github.com/pytorch/pytorch/blob/main/LICENSE |
| Python ONNX Runtime | 1.30.0 | MIT | Export parity checks only; the Java runtime stays pinned at 1.20.0 | https://github.com/microsoft/onnxruntime/blob/main/LICENSE |
| FFmpeg (with libx264) | 8.1.1 | GPL-2.0-or-later as built (libx264 is GPL) | Run as an external program by `tools/degrade.py` to make corpus copies; never linked, bundled or shipped | https://ffmpeg.org/legal.html |
| Apache Maven | 3.9.16 | Apache-2.0 | Building MockMDS (`third_party/`) | https://maven.apache.org/ref/current/maven-model/license.html |
| MOSIP mosip-mock-services (MockMDS) | 1.3.0 | MPL-2.0 | Device simulator for development and tests | https://github.com/mosip/mosip-mock-services/blob/master/LICENSE |
| OpenCV (`opencv-python-headless`) | 5.0.0 | Apache-2.0 | Reference outputs for SFace parity (`tools/sface_reference.py`, `tools/.venv-tf`) | https://github.com/opencv/opencv/blob/4.x/LICENSE |
| MOSIP `kernel-cbeffutil-api` (its `cbeff.xsd` only) | 1.2.0.3 | MPL-2.0 (XSD text from the OASIS BIAS profile) | Schema validation test for BIR `Others` embedding; unzipped into `third_party/`, not a dependency | https://github.com/mosip/commons |

**jai-imageio-jpeg2000 — conditional.** It is not an OSI licence: JJ2000 grants use only in products conforming to
the JPEG 2000 standard (decoding a standard JPEG 2000 image is such a use) and warns that JPEG 2000 may be covered by
patents. MOSIP's own `io.mosip.biometric.util:biometrics-util:1.2.0.3` depends on exactly this artifact at 1.3.0, so
inside the Registration Client PRAMAAN uses the copy MOSIP already ships rather than adding a second one. Confirm with
MOSIP before any separate distribution (Bible Part 30, Q20).

## Models (`models/`)

| File | Weights licence | Training data | Verdict | Link |
|---|---|---|---|---|
| `face_detection_yunet_2023mar.onnx` | MIT (OpenCV Zoo, Shiqi Yu) | WIDER Face (per OpenCV Zoo) | **Usable** — face detection (#19). sha256 `8f2383e4...bc9ff` | https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet |
| `minifasnet_v2_2.7_80x80.onnx` | Apache-2.0 (Minivision) — our ONNX export of `2.7_80x80_MiniFASNetV2.pth` via `tools/export_minifasnet.py` | Undisclosed by Minivision | **Usable** — passive scorer (#24). Parity with PyTorch 3.3e-6. sha256 `eb0feb2c...7717b` | https://github.com/minivision-ai/Silent-Face-Anti-Spoofing |
| `mediapipe_face_landmarks_detector.onnx` | Apache-2.0 (MediaPipe FaceMesh V2 model card) — our tf2onnx conversion of `face_landmarks_detector.tflite` from `face_landmarker.task` | Google (per model card) | **Usable** for landmarks (#23). Parity with TFLite 1.5e-4 px. Not face recognition | https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker |
| `mediapipe_face_blendshapes.onnx` | Apache-2.0 (MediaPipe Blendshape V2) — our tf2onnx conversion | Google (per model card) | Converted and parity-checked (1.6e-6); wired as a second vote for smile and eyes, on the 146-point subset copied from MediaPipe's `face_blendshapes_graph.cc` | https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker |
| `minifasnet_v1se_4.0_80x80.onnx` | Apache-2.0 (Minivision) — our ONNX export of `4_0_0_80x80_MiniFASNetV1SE.pth` | Undisclosed by Minivision | **Usable** — passive scorer (#24). Parity 1.9e-6. sha256 `17246cc2...1aec4` | https://github.com/minivision-ai/Silent-Face-Anti-Spoofing |
| `face_recognition_sface_2021dec.onnx` | Apache-2.0 (OpenCV Zoo; MobileFaceNet trained with the SFace loss, Yaoyao Zhong) | **Not stated by the zoo.** The SFace paper trains on CASIA-WebFace, VGGFace2 and MS1MV2 — all research/non-commercial terms, MS-Celeb-1M withdrawn | **Usable for development**; production needs the training-data question answered (Bible Part 30, Q19) or a matcher the deployment already licenses (MOSIP BioSDK). Parity with OpenCV `FaceRecognizerSF`: crop diff 0.000, cosine 1.00000. sha256 `0ba9fbfa...34e79` | https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface |
| `u2netp.onnx` | Apache-2.0 (U²-Net-P, Xuebin Qin et al.); distributed as a release asset of the rembg project (MIT), 4 574 861 bytes, sha256 `309c8469...ddd8` | **Not checked.** U²-Net was trained on DUTS-TR, an academic saliency dataset whose commercial terms were not looked up | **Evaluation only** - compared against the no-model segmenter (`docs/person-segmentation.md`); **not** in the verified bundle, not in `operating-points.json`, not shipped. Downloaded from https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx on 2026-10-14 | https://huggingface.co/edgetools/u2netp |

Candidates already assessed (Bible Part 7, Part 47.5):

| Model | Weights licence | Training data | Verdict |
|---|---|---|---|
| MiniFASNet (Silent-Face-Anti-Spoofing) | Apache-2.0 | Undisclosed | **Usable** — first passive model (#24); only the smaller open model |
| YuNet, SFace (OpenCV Zoo) | MIT / Apache-2.0 | WIDER Face / not stated (see the model table) | YuNet usable; SFace development-only until Q19 |
| MediaPipe Face Landmarker (FaceMesh V2) | **Apache-2.0** (model card: "LICENSED UNDER Apache License, Version 2.0") | — | Usable for landmarks; model card out-of-scope: life-critical decisions, and it is not face recognition — we use it only for action/geometry cues. Conversion status: see #23 |
| DINOv2 | Apache-2.0 (code and weights) | — | Usable as a server-side backbone |
| SAFAS, PSCF | No licence file found | Academic datasets | Architecture reference only — reimplement and retrain |
| FLIP | Unclear | Academic datasets | Architecture reference only |

## Test fixtures

| File | Origin | Licence |
|---|---|---|
| `core/src/test/resources/models/smoke_affine.onnx` | Generated by `tools/make_smoke_model.py` (y = 2x + 1) | Ours, MPL-2.0 |
| `sbi/testdata/discovery.json`, `deviceinfo.json`, `rcapture-face.request.json`, `capture-meta.json` | Captured from MockMDS | Derived from MPL-2.0 test data; no biometrics |
| `sbi/testdata/mockmds-rootca.cer` | MockMDS development root CA certificate (public), exported from its keystore | MPL-2.0 test data; certificate only, no private key |
| `sbi/testdata/rcapture-face.json`, `stream-sample.mjpeg` | Captured from MockMDS | **Not committed** — contain MOSIP's test photo of a real person |
