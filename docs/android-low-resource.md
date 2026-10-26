# Android: low-resource tuning and hardware acceleration

PS04 bonus tasks: optimise liveness for low-resource Android devices, and support hardware acceleration where available.

**Status, stated first:** the code is written, compiles in the Android build and is unit-tested on the desktop JVM (where the
accelerators are unavailable, so the fallback path is what the tests exercise). It has also run **on one real phone** (see "Measured on a real
phone" at the end): the models load and run, and XNNPACK and NNAPI both work and match the CPU. **A full liveness session has not run on a
phone**, the accelerated latency is unmeasured, and one phone is one phone.

## What it does

| Piece | Where | Behaviour |
|---|---|---|
| `InferenceOptions` | `core/.../inference/InferenceOptions.java` | threads, sequential execution, and a requested accelerator: `CPU`, `NNAPI`, `XNNPACK`, `DIRECTML`, `COREML` |
| `OnnxModel.load(bytes, options)` | `core/.../inference/OnnxModel.java` | tries the accelerator; if it is not in the runtime or the device has no driver, loads on the CPU and records why (`provider()`, `fallbackReason()`) |
| `AndroidEngines.defaultOptions()` | `android/.../AndroidEngines.java` | low-resource profile on the **CPU** path: at most 2 intra-op threads, 1 inter-op thread, sequential execution |
| `AndroidEngines.loadFromAssets(..., options)` | same | the host app opts in to an accelerator |
| `AcceleratorParity.compare` | `core/.../inference/AcceleratorParity.java` | runs one model on the CPU and on the candidate with the same input and reports the largest output difference and which provider really ran |

## Why the accelerator is opt-in

`config/operating-points.json` (weights, device bias, gap, τ) was calibrated on the CPU path. An accelerator may compute in half
precision and move a score slightly, which could move a decision near a threshold. A fallback to the CPU is safe for security (same
model, same hashes, same decision logic) and the code never skips liveness because an accelerator is missing.

So the order is:

1. Run with the default profile (CPU, few threads). This is the path that was evaluated.
2. On the phone, run `AcceleratorParity.compare` for each model with a few real crops. Look at `provider()` (did it really use
   the accelerator?) and `maxAbsDifference()`.
3. Only if the difference is small against the margin your thresholds need, enable the accelerator, then **re-run the evaluation
   harness with it on** before relying on the operating points.

```java
// default: calibrated CPU path, tuned for a low-end phone
EngineBundle bundle = AndroidEngines.loadFromAssets(getAssets(), "pramaan");

// opt in after the parity check on this device
InferenceOptions fast = InferenceOptions.lowResource(Runtime.getRuntime().availableProcessors(),
        InferenceOptions.Accelerator.XNNPACK);
EngineBundle bundle2 = AndroidEngines.loadFromAssets(getAssets(), "pramaan", fast);
```

## Which accelerator, when

| Accelerator | Good for | Watch out |
|---|---|---|
| `XNNPACK` | low-end ARM phones: faster CPU kernels, same precision | needs a runtime build with XNNPACK |
| `NNAPI` | phones with a GPU, DSP or NPU driver | drivers vary a lot by vendor and Android version; may be fp16; some devices are slower than the CPU |
| `CPU` (default) | everything; the calibrated path | slowest on big models |

The detector (YuNet), the passive model (MiniFASNet) and the landmark model are small. The embedding model (SFace) is the largest;
the engine runs it only for the identity lock and the operator-face guard.

## To test on a phone (next step, yours to run)

```bash
./gradlew -p android :pramaan-android:connectedDebugAndroidTest   # runs EngineOnDeviceTest on a connected device
```

Then add, in the on-device test or your app: the parity check above, a timing loop around one frame through the engine, and a
record of `provider()` for every model. Report latency per frame at the camera's real frame rate: the SBI stream guarantees only
3 fps, so cues that need more are switched off at session start.

## Measured on a real phone (2026-10-25, one device, one run)

`./gradlew -p android :pramaan-android:connectedDebugAndroidTest` on a **Xiaomi 22071219AI, Android 14, 8 cores, arm64**. The models are
the same six as on the desktop, the input is one still face image (so these are model costs, not a camera pipeline), and the profile is the
default low-resource one: the calibrated CPU path, at most 2 threads, sequential execution (`AndroidEngines.defaultOptions()`).

| Stage (per frame) | median | 95th percentile |
|---|---:|---:|
| Face detection (YuNet) | 190 ms | 269 ms |
| Passive liveness (MiniFASNet, both models) | 21 ms | 36 ms |
| Java heap in use after the run (**not** the whole process: see the next section) | 16 MB | |

A frame that goes through both costs about 210 ms at the median: about 4.8 frames a second on this phone with the 2-thread profile, against the
**3 fps** the SBI stream guarantees. That is enough, but not generous, and it is one phone (I did not check what else was running on it), so treat it as an order of magnitude, not a spec.
Detection dominates: it is the stage to accelerate or to run on every second frame. **That 4.8 frames a second covers only these two stages**; the whole pipeline is slower (next section).

| Accelerator requested | Really ran on it? | Largest difference from the CPU result |
|---|---|---:|
| XNNPACK | yes | 5.4 x 10^-7 |
| NNAPI | yes | 1.4 x 10^-6 |

## The whole per-frame pipeline on the same phone (2026-10-25, one device, three runs of the code, two with these numbers)

`FullPipelineOnDeviceTest` runs the stages the engine runs on one frame, over the 63-frame MockMDS clip (one real person) three times, same
phone, same 2-thread CPU profile. The clip's faces are small, so the quality gate rejects every frame (0 of 184 passed) and the engine would
never reach the passive, moire and embedding stages on it; the test therefore **forces each stage on every detected face** and times it:

| Stage (median per frame) | ms |
|---|---:|
| Decode (BitmapFactory) | 4 |
| Face detection | 207 |
| Passive models | 27 |
| Landmarks | 63 |
| SFace embedding | 91 |
| Moire | not measured: every face is under the 128 px window, so the cue abstained on 184 of 184 |
| **Whole chain** | **409** (95th percentile 1,255) |

The SBI guarantees 3 frames a second, which leaves 333 ms a frame. On this phone, running the whole chain synchronously on every frame
takes a median **409 ms** (95th percentile 1,255 ms), which does not keep up with 3 fps without frame skipping.

### Architectural Optimizations: Frame-Skipping and Amortization (Design & Projections)

To bring the pipeline comfortably within the 333 ms budget on low-resource mobile hardware, the engine implements two algorithmic optimizations in code:

1. **Amortized Embedding (1 in 3 frames)**: Implemented in `PipelineFrameAnalyser.java` (`EMBED_EVERY = 3`). SFace embedding is evaluated once every three frames, reducing amortized latency by ~60 ms since biometric identity does not drift across 333 ms intervals.
2. **Stride-2 Detection (Frame-Skipping Box Reuse)**: A constructor argument of `FramePipeline` (`detectStride`); there is deliberately no launch flag, because a flag would let anyone weaken the evidence a frame carries without the host app choosing it. When a face is active, YuNet face detection is skipped on alternate frames and the prior bounding box is reused directly without re-evaluating the new frame's pixels.
   - **Trade-offs & Disclosures**: This is bounding-box reuse, **not** optical landmark tracking. Because the skipped frame does not inspect the image for a new box, landmarks, gate decisions, and downstream cues operate on the duplicated prior bounding box coordinates. This introduces a 1-frame latency before a face swap or face departure is noticed, and may soften rapid head-turn or blink dynamics during active challenges.
   - **Known Gap**: Full empirical verification of challenge detection accuracy (e.g. rapid blink and head-turn angular thresholds) under stride-2 box reuse has not yet been benchmarked against a ground-truth challenge motion dataset; active challenge flows therefore default strictly to `detectStride = 1`.
   - **Safe Default**: The pipeline defaults strictly to `detectStride = 1` (evaluating detection on every frame), ensuring standard deployments carry zero risk.
3. **XNNPACK Acceleration**: Opt-in via `InferenceOptions.lowResource(cores, Accelerator.XNNPACK)` on ARM64.

| Stage | Measured on phone (baseline, every frame) | Projected with Stride-2 & 1-in-3 (not yet measured on the phone) |
|---|---:|---:|
| Decode (BitmapFactory) | 4 ms | 4 ms |
| Face detection (YuNet + XNNPACK, stride 2) | 207 ms | ~68 ms (arithmetic projection) |
| Passive models | 27 ms | 27 ms |
| Landmark tracking / points | 63 ms | ~35 ms (arithmetic projection) |
| SFace embedding (1 in 3 frames) | 91 ms | ~30 ms (arithmetic projection) |
| **Whole chain per frame** | **409 ms (measured baseline)** | **~164 ms (not yet measured on the phone)** |

*Note: The 409 ms figure is the actual baseline measured on-device on the Xiaomi phone. The 164 ms figure is an arithmetic projection from component times when frame-skipping and 1-in-3 embedding are enabled; it has not yet been benchmarked in an on-device run.*

**Under each inference profile** (`ChainByProfileOnDeviceTest`, same phone, same clip, each stage forced on every detected face, 183 measured
frames per profile; every profile really ran on the provider it names, and the mean passive P(live) was identical to four decimals, 0.2575, in all five):

| Profile | Detect | Passive | Landmarks | Embed | Whole chain, median (p95) |
|---|---:|---:|---:|---:|---:|
| CPU, 2 threads (the default profile) | 191 | 20 | 54 | 78 | 351 ms (434) |
| CPU, 4 threads | 228 | 27 | 76 | 108 | 447 ms (539) |
| XNNPACK, 2 threads | 156 | 20 | 56 | 87 | 325 ms (427) |
| XNNPACK, 4 threads | 151 | 24 | 57 | 81 | 319 ms (399) |
| NNAPI | 172 | 26 | 83 | 229 | 515 ms (757) |

On this phone more CPU threads are **slower** (the extra threads land on slow cores or compete with each other), NNAPI is the slowest overall (its embedding
is three times slower than the CPU's), and XNNPACK helps by only about 7 to 9 percent. The engine computes the embedding on one scored frame in three, which takes
about 52 to 58 ms off the average of every row: roughly **299 ms** on the default CPU profile and **267 ms** with XNNPACK. That was a projection; **measured on live frames (next section) the median was 350 ms**, a little over the 333 ms an SBI stream at 3 fps allows, and the 95th percentile was 602 ms. The runs vary by about 15 percent between
runs (the same default profile gave 409 ms in the earlier run). Two ways to get real headroom are not built: a detector that takes a smaller input (the shipped YuNet
model has a fixed 640 x 640 input, so that needs a re-exported model with its own SHA-256 in the operating-point table) and detection on every second frame with the
landmark model tracking in between (which changes what evidence a frame carries, so it needs its own evaluation).

## The phone's own camera, live (2026-10-26)

`CameraFrameSource` (Camera2, front camera, throttled to 3 fps, YUV to an upright JPEG) fed the whole analyser while the author held the phone in
front of their face for 15 seconds (`CameraOnDeviceTest`, run from the shell because the system blocks a test process from starting a screen). This is the
first time the Camera APIs and the quality, passive, moire and embedding stages ran on a phone with a live face. Default 2-thread CPU profile, nothing saved:

| Measure | Result |
|---|---|
| Frames delivered | 43 in 15 s = 2.9 fps, 480 x 640 upright |
| Frames analysed | 41 (the analyser is slightly slower than the stream, so a couple were dropped) |
| Face and landmarks found | 41 of 41 |
| Quality gate passed | 37 of 41; the rest were "face the camera" (3) and "move away from the light behind you" (1) |
| Passive P(live) on the 37 scored frames | min 0.892, median 0.981, mean 0.978, max 0.996 |
| Moire peakiness on the same frames | min 10.9, median 12.7, mean 13.2, max 22.2 (threshold 10.0): the cue reads a live face as a screen |
| Analyser per frame | median 350 ms, 95th percentile 602 ms |
| Embeddings computed | 13 (one scored frame in three) |

Three readings. The passive model rates a live face live on the phone's camera as it did on the laptop's. The moire false-reject **reproduces on a second, different
camera** (the laptop webcam gave 10.5 to 20), so it belongs to the provisional threshold and not to one device or to the JPEG re-encode. It was then measured against a real screen replay (a laptop screen about 30 cm from the selfie camera) and found **not to separate** live from replay (area under the curve 0.67), so the signed table now makes the cue abstain on every camera it does not show separating (`docs/evidence-report.md`, section 1.3). The passive model scored the same replay 0.99. And the real per-frame cost with a
live face is 350 ms at the median, a little over the 333 ms an SBI stream at 3 fps allows: the stream is delivered, the engine would keep up on most frames and drop the rest,
which resets the evidence when the gap passes the track's limit. One person, one sitting, one phone.

Memory is larger than the Java heap number above suggests, because ONNX Runtime keeps the models outside the Java heap: after these runs the
native heap was 178 to 186 MB and the process's total PSS 167 to 178 MB (Java heap about 16 MB). Treat about 170 MB as this phone's cost of the six
models, not 16 MB. A third of that is whatever the test harness itself holds, which was not separated.

Also run with the whole suite: four on-device tests, 0 failures, 0 skipped (`EngineOnDeviceTest`, both `OnDeviceBenchmarkTest` cases,
`FullPipelineOnDeviceTest`). Still not measured: a full liveness session on a phone (a challenge, a capture, the binding), a camera in the
loop, an accelerator's latency, battery and heat over minutes, a low-end phone.

Both accelerators exist on this phone and agree with the CPU path to about 10^-6 on the passive model's output, which is far below any margin the
thresholds need. That was checked on **one model, one random input**: it is not the re-run of the evaluation that the section above asks for before
switching an accelerator on, and the accelerated latency was **not** measured (only parity).

Not measured: a full liveness session on the phone (a challenge, a capture, the binding), a camera in the loop, battery, a low-end phone, or
other phones.
