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

The SBI guarantees 3 frames a second, which leaves 333 ms a frame. **On this phone the whole chain run on every frame does not keep up**
(409 ms), and the 95th percentile is 3 to 4 times the median (one run saw 1,255 ms: the cause was not investigated, thermal throttling and
other apps are candidates). The engine now computes the embedding on one scored frame in three, which would take about 60 ms off the average
(about 350 ms), still a little over 333 ms. Options, not yet built or measured: detect on every second frame and track in between, run the
detector on an accelerator (XNNPACK and NNAPI ran and matched the CPU, but their latency was never timed), or a smaller detector input.
Until then, do not claim that a phone runs the full liveness pipeline in real time at 3 fps.

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
