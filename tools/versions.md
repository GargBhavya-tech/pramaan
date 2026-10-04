# Toolchain and runtime versions

Build Map #2 asks for these in writing. Update this file whenever a version changes, and say why.

## Pinned versions

| Component | Version | Where pinned | Why this version |
|---|---|---|---|
| Gradle | 8.14.3 | `gradle/wrapper/gradle-wrapper.properties` (with SHA-256) | Oldest 8.x line that runs on JDK 24; compatible with Android Gradle Plugin 8.x for Phase 12; project-local, so it can't clash with other projects |
| Bytecode target | Java 11 (`--release 11`) | root `build.gradle.kts` | The MOSIP desktop Registration Client 1.2.x runs on Java 11 |
| ONNX Runtime (Java) | **1.20.0** | `gradle/libs.versions.toml` | See below — newer versions fail to load on common JDKs |
| JUnit | 5.11.4 | `gradle/libs.versions.toml` | |
| Jackson | 2.18.2 | `gradle/libs.versions.toml` | |
| Android Gradle Plugin | 8.5.2 | `android/build.gradle.kts` (separate build) | Already in this machine's Gradle cache; the 8.x line MOSIP's Android mock SBI uses (8.2.2). Runs on this JDK 24 with Gradle 8.14.3 |
| Android SDK | compileSdk 34, build-tools 35.0.0, minSdk 26 | `android/pramaan-android/build.gradle.kts` | Installed platforms and build tools; minSdk as MOSIP's Android mock SBI |
| ONNX Runtime (Android) | 1.20.0 | `android/pramaan-android/build.gradle.kts` | Same pin as the JVM build; `onnxruntime-android` replaces `onnxruntime` in the AAR |
| JUnit 4 / AndroidX Test | 4.13.2 / ext-junit 1.1.5, runner 1.5.2 | `android/pramaan-android/build.gradle.kts` | Android tests only |
| OpenJFX | 11.0.2 | `gradle/libs.versions.toml` (`desktop` only) | The version MOSIP Registration Client 1.2.x pins (`registration/pom.xml`, `openjfx.version`). Declared per OS classifier and non-transitively, because OpenJFX POMs name the classifier through a Maven profile property Gradle does not evaluate. Runs on this JDK 24 (render test) |
| Python `onnx` (tools only) | 1.23.0 | `tools/.venv` (not global) | Generates test fixtures; isolated so it can't disturb other Python projects |
| PyTorch CPU (tools only) | 2.14.0 | `tools/.venv` | Exports MiniFASNet to ONNX |
| Python ONNX Runtime (tools only) | 1.30.0 | `tools/.venv` | Export parity checks; Java stays on 1.20.0 |
| FFmpeg (tools only) | 8.1.1 (gyan.dev full build, with libx264) | on PATH, not bundled | `tools/degrade.py` (#66); any build with libx264 works |

## Why ONNX Runtime is pinned to 1.20.0

On 2026-09-22, ONNX Runtime 1.21.0 and every later release (tested: 1.21.0, 1.21.1, 1.22.0, 1.23.2, 1.24.3, 1.29.0) failed on
this machine with `UnsatisfiedLinkError: ... onnxruntime.dll: A dynamic link library (DLL) initialization routine failed`.
1.19.2 and 1.20.0 loaded and passed.

Cause: those releases need the Microsoft Visual C++ runtime **14.40 or newer** loaded in the process. Windows here has
14.50 installed, but the JDK ships its own copies in `bin\` and Java loads them first:

| DLL | `C:\Windows\System32` | JDK 24.0.1 `bin\` |
|---|---|---|
| msvcp140.dll | 14.50.35719.0 | **14.36.32532.0** |
| vcruntime140.dll | 14.50.35719.0 | 14.36.32532.0 |

This matters beyond this laptop: the MOSIP Registration Client ships its own Java runtime, which can carry the same kind
of older DLLs. Pinning 1.20.0 avoids a failure in the field that would look like "liveness is broken".

**Before bumping:** run `./gradlew :core:test` on the target Java runtime (the one the Registration Client actually ships),
not just the developer JDK. `OnnxModel` turns this failure into an explicit `IllegalStateException` that names the cause.

## Measured ONNX Runtime latency (Build Map #2)

`./gradlew :harness:onnxSmoke`, smoke fixture (`y = 2x + 1`, [1, 4]), 200 warm runs.

| Date | Machine | Java | ORT | Cold start (load + 1st run) | Warm p50 / p95 |
|---|---|---|---|---|---|
| 2026-09-22 | Intel Core i7-1355U, 15.6 GB, Windows 11 | 24.0.1 | 1.20.0 | 995 ms | 0.29 / 1.03 ms |

**Consequence:** cold start (~1 s) is almost entirely one-time native-library extraction and environment setup. The
engine must initialise ONNX Runtime when the application starts, never lazily on the first capture — otherwise the first
resident of the day waits an extra second. Real model latencies (MiniFASNet, YuNet, landmarks) replace this row in #28.

## Gradle on JDK 24

Gradle prints `WARNING: A restricted method in java.lang.System has been called` on JDK 24. It comes from Gradle's own
native-platform library, not from this project, and is harmless.

## Measured pipeline latency (Build Map #28)

`./gradlew :harness:pipelineBench` on the MockMDS stream fixture (63 frames, 640x360-class JPEG, 21 fps), single thread.

| Date | Machine | Stage | p50 | p95 |
|---|---|---|---|---|
| 2026-09-23 | Intel Core i7-1355U, Java 24, ORT 1.20.0 | decode (ImageIO JPEG) | 23-34 ms | 60-69 ms |
| | | detect (YuNet 640x640) | 46-47 ms | 74-75 ms |
| | | quality | 2.3 ms | 11-12 ms |
| | | passive (MiniFASNet V2 + V1SE) | 22 ms | 67 ms |

**Consequences:** about 73 ms per frame before passive, so this laptop keeps up with roughly 13 fps; faster devices
lose frames to the drop-oldest queue (#13), which is the intended behaviour. The biggest cost is YuNet at 640x640 —
the first optimisation for low-end hardware (#83) is a smaller detector input or detecting every other frame while
tracking in between.
